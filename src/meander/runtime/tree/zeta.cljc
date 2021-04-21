(ns meander.runtime.tree.zeta
  (:refer-clojure :exclude [test
                            resolve])
  (:require [meander.tree.zeta :as m.tree]))

(defn none []
  (m.tree/data :meander.zeta/none))

(defn bind [f m-state]
  (let [identifier (m.tree/identifier)]
    (m.tree/bind identifier m-state (f identifier))))

(defn call
  ([f then]
   (then (m.tree/call f [])))
  ([f a & rest]
   (let [then (last rest)
         args (into [a] (butlast rest))]
     (then (m.tree/call f args)))))

(defn get-object [state then]
  (let [identifier (m.tree/identifier)]
    (m.tree/let identifier (m.tree/get-object state) (then identifier))))

(defn set-object
  {:style/indent 2}
  [state object then]
  (let [identifier (m.tree/identifier)]
    (m.tree/let identifier object
      (then (m.tree/set-object state identifier)))))

(defn dispense
  {:style/indent 2}
  [state id unfold pass fail]
  (let [unfold-pass (fn [x new]
                      (set-object (assoc state id new) x pass))
        unfold-fail (fn [x]
                      (fail state))
        entry (find state id)
        old (if entry (val entry) (none))]
    (unfold old unfold-pass unfold-fail)))

(defn join
  [a thunk-b]
  (m.tree/pick (a) (thunk-b)))

(defn pick
  [a thunk-b]
  (m.tree/pick (a) (thunk-b)))

(defn save
  {:style/indent 2}
  [state id fold pass fail]
  (get-object state
              (fn [new]
                (let [fold-pass (fn [new]
                                  (m.tree/do-let (m.tree/set-binding state (m.tree/identifier id) new)
                                    pass))
                      fold-fail (fn [x]
                                  (fail state))]
                  (m.tree/do-let (m.tree/get-binding state (m.tree/identifier id) (none))
                    (fn [old]
                      (fold old new fold-pass fold-fail)))))))

(defn scan
  [f xs]
  (let [identifier (m.tree/identifier)]
    (m.tree/scan identifier xs (f identifier))))

(defn star [f state]
  (let [identifier (m.tree/identifier)]
    (m.tree/star state identifier (fn g [state] (f g identifier)))))

(defn test [test then else]
  (let [identifier (m.tree/identifier)]
    (m.tree/let identifier test
      (m.tree/test identifier (then) (else)))))

(defn with [state mapping then]
  (let [identifier (m.tree/identifier)]
    (m.tree/with state mapping identifier
      (then identifier))))

;; Smart constructors
;; ---------------------------------------------------------------------

(def ^{:dynamic true}
  *bindings* {})

(defn do-let
  "Like `m.tree/do-let` but updates `*bindings*` before calling f and
  does not create an `m.tree/let?` if x is either an
  `m.tree/identifier?`, `m.tree/data?`, `m.tree/code?`, or
  `m.tree/state?`."
  {:style/indent 1}
  [x f]
  (if (or (m.tree/identifier? x)
          (m.tree/data? x)
          (m.tree/code? x)
          (m.tree/state? x))
    (f x)
    (m.tree/do-let x
      (fn [identifier]
        (binding [*bindings* (assoc *bindings* identifier x)]
          (f identifier))))))

(defn resolve
  [node not-found]
  (let [bindings *bindings*]
    (if (m.tree/identifier? node)
      (loop [x (get bindings node not-found)]
        (cond
          (= x not-found)
          not-found

          (m.tree/identifier? x)
          (recur (get bindings x not-found))

          :else
          x))
      not-found)))

(defn reverse-resolve [node]
  (some (fn [[identifier other-node]]
          (if (= other-node node)
            identifier))
          *bindings*))

(def %true
  "Tree node representing `true`."
  (m.tree/data true))

(def %false
  "Tree node representing `false`."
  (m.tree/data false))

(def %=
  "Tree node representing the host function `=`."
  (m.tree/code `=))

(def %any?
  "Tree node representing the host function `any?`."
  (m.tree/code `any?))

(defn smart-call
  ([f then]
   (do-let (m.tree/call f []) then))
  ([f a & rest]
   (let [then (last rest)
         args (into [a] (butlast rest))]
     (if (or (= f %any?)
             (and (= f %=) (apply = args)))
       (then %true)
       (do-let (m.tree/call f args) then)))))

(def ^{:dynamic true}
  *true* #{})

(def ^{:dynamic true}
  *false* #{})

(defn smart-test
  {:style/indent 1}
  [test then else]
  (let [resolved-test (resolve test test)]
    (cond
      (= resolved-test %true)
      (then)

      (= resolved-test %false)
      (else)

      :else
      (do-let test
        (fn [identifier]
          (m.tree/test identifier (then) (else)))))))

(defn smart-bind [f m-state]
  (cond
    ;; (bind x (pass state) e)
    ;; ----------------------- BindPass
    ;;     (let x state e)
    (m.tree/pass? m-state)
    (do-let (:state m-state) f)

    ;; (bind x (fail state) e)
    ;; ----------------------- BindFail
    ;;      (fail state)
    (m.tree/fail? m-state)
    m-state

    ;; (bind x (let y e1 e2) e3)
    ;; ------------------------- CommuteBindLet
    ;; (let y e1 (bind x e2 e3))
    ;; (m.tree/let? (:expression m-state))
    ;; (let [let-node (:expression m-state)]
    ;;   (m.tree/let (:identifier let-node) (:expression let-node)
    ;;     (smart-bind f (:body let-node))))

    ;;      (bind x (test e1 e2 e3) e4)
    ;; --------------------------------------- CommuteBindTest
    ;; (test e1 (bind x e2 e4) (bind x e3 e4))
    (m.tree/test? (:expression m-state))
    (let [test-node (:expression m-state)]
      (smart-test (:test test-node)
        (fn [] (smart-bind f (:then test-node)))
        (fn [] (smart-bind f (:else test-node)))))

    :else
    (bind f m-state)))

(defn smart-list [state]
  (let [resolved-state (or (reverse-resolve state) (resolve state state))]
    (cond
      (m.tree/state? resolved-state)
      (:bindings resolved-state)

      :else
      (m.tree/get-bindings state))))

(defn smart-take
  {:style/indent 1}
  [state then]
  (let [resolved-state (resolve state state)]
    (cond
      (m.tree/state? resolved-state)
      (do-let (get resolved-state :object) then)

      (m.tree/set-object? resolved-state)
      (do-let (:value resolved-state) then )

      :else
      (let [get-object (m.tree/get-object state)]
        (if-some [identifier (reverse-resolve get-object)]
          (then identifier)
          (do-let get-object then))))))

(defn smart-give
  {:style/indent 2}
  [state object then]
  (do-let object
    (fn [x]
      (do-let (if (m.tree/state? state)
                (assoc state :object x)
                (m.tree/set-object state x))
        then))))

(defn smart-get-binding [state identifier none]
  (cond
    (m.tree/state? state)
    (smart-get-binding (:bindings state) identifier none)

    (m.tree/bindings? state)
    (get state identifier none)

    (m.tree/set-object? state)
    (smart-get-binding (:state state) identifier none)

    :else
    (m.tree/get-binding state identifier none)))

(defn smart-set-binding [state identifier new]
  (cond
    (and (m.tree/state? state) (m.tree/bindings? (:bindings state)))
    (update-in state [:bindings :bindings] assoc identifier new)

    (m.tree/set-object? state)
    (update state :state smart-set-binding identifier new)

    :else
    (m.tree/set-binding state identifier new)))

(defn smart-save
  {:style/indent 2}
  [state id fold pass fail]
  (let [id (m.tree/identifier id)]
    (smart-take state
      (fn [new]
        (let [fold-pass (fn [new]
                          (do-let (smart-set-binding state id new) pass))
              fold-fail (fn [x]
                          (fail state))]
          (do-let (smart-get-binding state id (none))
            (fn [old]
              (fold old new fold-pass fold-fail))))))))

(defn smart-pick
  [thunk-a thunk-b]
  (let [a (thunk-a)]
    (cond
      (m.tree/fail? a)
      (thunk-b)

      (m.tree/pass? a)
      a

      :else
      (m.tree/pick a (thunk-b)))))

;; Runtimes
;; ---------------------------------------------------------------------

(defn df-one [{:keys [meander.zeta/optimize?]}]
  (if (false? optimize?)
    {:bind bind
     :call call
     :data m.tree/data
     :dual m.tree/dual
     :eval m.tree/code
     :fail m.tree/fail
     ;; :find m.runtime.eval.common/resolve-reference
     :give set-object
     :list m.tree/get-bindings
     :load dispense
     :make m.tree/fabricate
     :mint m.tree/mint
     :none (none)
     :pass m.tree/pass
     :pick pick
     :join join
     :save save
     :scan scan
     :seed m.tree/seed
     :star star
     :take get-object
     :test test
     :with with}
    ;; Optimized
    {:bind smart-bind
     :call smart-call
     :data m.tree/data
     :dual m.tree/dual
     :eval m.tree/code
     :fail m.tree/fail
     ;; :find m.runtime.eval.common/resolve-reference
     :give smart-give
     :list smart-list
     :load dispense
     :make m.tree/fabricate
     :mint m.tree/mint
     :none (none)
     :pass m.tree/pass
     :pick smart-pick
     :join smart-pick
     :save smart-save
     :scan scan
     :seed m.tree/seed
     :star star
     :take smart-take
     :test smart-test
     :with with}))