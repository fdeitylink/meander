(ns meander.epsilon
  (:refer-clojure :exclude [and find keyword let not or symbol])
  #?(:clj
     (:require [clojure.core :as clj]
               [clojure.spec.alpha :as s]
               [meander.match.epsilon :as r.match]
               [meander.match.syntax.epsilon :as r.match.syntax]
               [meander.strategy.epsilon :as r]
               [meander.syntax.epsilon :as r.syntax]
               [meander.substitute.epsilon :as r.subst]
               [meander.substitute.syntax.epsilon :as r.subst.syntax])
     :cljs
     (:require [cljs.core :as clj]
               [cljs.spec.alpha :as s :include-macros true]
               [meander.match.epsilon :as r.match :include-macros true]
               [meander.match.syntax.epsilon :as r.match.syntax :include-macros true]
               [meander.strategy.epsilon :as r :include-macros true]
               [meander.syntax.epsilon :as r.syntax]
               [meander.substitute.epsilon :as r.subst :include-macros true]
               [meander.substitute.syntax.epsilon :as r.subst.syntax :include-macros true]))
  #?(:clj (:import (clojure.lang ExceptionInfo)))
  #?(:cljs (:require-macros [meander.epsilon])))

;; ---------------------------------------------------------------------
;; Match, Find, Search

(defmacro match
  {:arglists '([x & clauses])
   :style/indent :defn}
  [& args] `(r.match/match ~@args))

(defmacro find
  {:arglists '([x & clauses])
   :style/indent :defn}
  [& args] `(r.match/find ~@args))

(defmacro search
  {:arglists '([x & clauses])
   :style/indent :defn}
  [& args] `(r.match/search ~@args))

;; ---------------------------------------------------------------------
;; Substitute

(defmacro subst
  {:arglists '([x & clauses])
   :style/indent :defn}
  [& args] `(r.subst/substitute ~@args))

;; ---------------------------------------------------------------------
;; Rewrite

(def ^{:arglists '([x])
       :private true}
  compile-rewrite
  (r/rewrite
   [?x (!match !substitution ...)]
   (`find ?x . !match (`subst !substitution) ...)

   _
   [:error "rewrite expects and odd number of arguments"]))

(defmacro rewrite
  {:style/indent :defn}
  [x & clauses]
  (r.match/match (compile-rewrite [x clauses])
    [:error ?error-message]
    (throw (ex-info ?error-message {}))

    ?form
    ?form))

(s/fdef rewrite
  :args (s/cat :x any?
               :clauses (s/* (s/cat :match any?
                                    :substitution any?)))
  :ret any?)

(def ^{:arglists '([x])
       :private true}
  compile-rewrites
  (r/rewrite
   [?x (!match !substitution ...)]
   (`r.match/search ?x . !match (`r.subst/substitute !substitution) ...)

   _
   [:error "rewrite expects and odd number of arguments"]))

(defmacro rewrites
  {:style/indent :defn}
  [x & clauses]
  (r.match/match (compile-rewrites [x clauses])
    [:error ?error-message]
    (throw (ex-info ?error-message {}))

    ?form
    ?form))

(s/fdef rewrites
  :args (s/cat :x any?
               :clauses (s/* (s/cat :match any?
                                    :substitution any?)))
  :ret any?)

;; ---------------------------------------------------------------------
;; Syntax extensions

(r.syntax/defsyntax and
  "Pattern matching operator which matches when `pattern` and,
  optionally, all of `patterns` match."
  [pattern & patterns]
  (case (::r.syntax/phase &env)
    :meander/match
    `(r.match.syntax/and ~pattern ~@patterns)
    ;; else
    &form))

(r.syntax/defsyntax or
  "Pattern matching operator which matches when either `pattern` or,
  opitionally, one of `patterns` match."
  [pattern & patterns]
  (case (::r.syntax/phase &env)
    :meander/match
    `(r.match.syntax/or ~pattern ~@patterns)
    ;; else
    &form))

(r.syntax/defsyntax not
  "Pattern matching operator which matches when `pattern` does not
  match."
  [pattern]
  (case (::r.syntax/phase &env)
    :meander/match
    `(r.match.syntax/not ~pattern)
    ;; else
    &form))

(r.syntax/defsyntax let
  {:style/indent 1}
  ([binding-patterns]
   (case (::r.syntax/phase &env)
     :meander/match
     (r.match/match binding-patterns
       [_ _ ...]
       (reduce
        (fn [?inner [?pattern ?expression]]
          (subst (`r.match.syntax/let ?pattern ?expression ?inner)))
        '_
        (reverse (partition 2 binding-patterns)))

       _
       (throw (ex-info "The second argument to let must be a vector with an number or elements"
                       {:form &form
                        :meta (meta &form)})))
     ;; else
     &form))
  ([binding-patterns target-pattern]
   (case (::r.syntax/phase &env)
     :meander/match
     (r.match/match binding-patterns
       [_ _ ...]
       (reduce
        (fn [?inner [?pattern ?expression]]
          (subst (`r.match.syntax/let ?pattern ?expression ?inner)))
        target-pattern
        (reverse (partition 2 binding-patterns)))

       _
       (throw (ex-info "The second argument to let must be a vector with an number or elements"
                       {:form &form
                        :meta (meta &form)})))

     ;; else
     &form)))

(s/fdef let
  :args (s/cat :binding-patterns (s/and vector? (s/cat :pattern any? :expression any?))
               :target-pattern (s/? any?))
  :ret seq?)

(r.syntax/defsyntax pred
  "Pattern matching operator which successfully matches whenever the
  target of pattern matching applied to `expr` returns a truthy
  value.

      (match 1
        (pred odd?)
        :okay)
      ;; => :okay

  Optionally, additional patterns `patterns` may be passed in which
  case they will be treated as an `and` pattern against the target of
  pattern matching.

      (match 1
        (pred odd? ?x)
        :okay)

  is the same as

      (match 1
        (and (pred odd?) ?x)
        :okay)"
  {:arglists '([expr] [expr & patterns])}
  ([p]
   (case (::r.syntax/phase &env)
     :meander/match
     `(r.match.syntax/pred ~p)

     ;; else
     &form))
  ([p pattern]
   (case (::r.syntax/phase &env)
     :meander/match
     `(r.match.syntax/pred ~p ~pattern)

     ;; else
     &form))
  ([p pattern & patterns]
   (case (::r.syntax/phase &env)
     :meander/match
     `(r.match.syntax/pred ~p (r.match.syntax/and ~pattern ~@patterns))

     ;; else
     &form)))

(r.syntax/defsyntax seqable
  "Pattern matching operator which matches the `seq` of anything that
  is `seqable?` against

      (p1 ,,, pn)

  where the sequence `p1` through `pn` is equal to `patterns`."
  [& patterns]
  (case (::r.syntax/phase &env)
    :meander/match
    `(r.match.syntax/pred seqable? (r.match.syntax/apply seq ~patterns))

    ;; else
    &form))

(r.syntax/defsyntax scan
  "Pattern matching operator which matches the `seq` of `seqable?`
  forms of the shape

      (_ ... p1 ,,, pn . _ ...)

  or `vectors?` of the form

      [_ ... p1 ,,, pn . _ ...]

  where the sequence `p1` through `pn` is equal to `patterns`."
  [& patterns]
  (case (::r.syntax/phase &env)
    :meander/match
    (clj/let [patternc (count patterns)
          [as as-pattern] (drop (- patternc 2) patterns)
          inner (if (= :as as)
                  `(~@'(_ ...) ~@patterns ~@'(. _ ...) :as ~as-pattern)
                  `(~@'(_ ...) ~@patterns ~@'(. _ ...)))]
      `(r.match.syntax/or
        [~@inner]
        ;; Prevent producing the same search results twice when
        ;; the target is a vector.
        (r.match.syntax/and (r.match.syntax/not (r.match.syntax/pred vector?))
                            (seqable ~@inner))))

    ;; else
    &form))

(r.syntax/defsyntax separated
  "Pattern matching operator which matches the `seq` of `seqable?`
  forms of the shape

      (_ ... p1 ,,, . _ ... pn . _ ...)

  or `vectors?` of the form

      [_ ... p1 ,,, . _ ... pn . _ ...]

  where the sequence `p1` through `pn` is equal to `patterns`."
  [& patterns]
  (case (::r.syntax/phase &env)
    :meander/match
    (clj/let [inner `(~@'(_ ...) ~@(mapcat cons patterns (repeat '(. _ ...))))]
      `(r.match.syntax/or
        [~@inner]
        ;; Prevent producing the same search results twice when
        ;; the target is a vector.
        (r.match.syntax/and (r.match.syntax/not (r.match.syntax/pred vector?))
                            (seqable ~@inner))))

    ;; else
    &form))

(r.syntax/defsyntax app
  "Pattern matching operator which applies pattern matching the result
  applying `f` to the current value being matched."
  ([f pattern]
   (case (::r.syntax/phase &env)
     :meander/match
     `(r.match.syntax/apply ~f ~pattern)

     :meander/substitute
     `(r.subst.syntax/apply ~f ~pattern)

     ;;
     &form))
  ([f pattern & patterns]
   (case (::r.syntax/phase &env)
     :meander/match
     `(r.match.syntax/apply ~f (r.match.syntax/and ~pattern ~@patterns))

     :meander/substitute
     `(r.subst.syntax/apply (partial apply ~f) [~pattern ~@patterns])

     ;; else
     &form)))

(r.syntax/defsyntax guard
  "Pattern matching operator which succeeds whenever `pred-expr`
  returns a truthy result. `pred-expr` is evaluated by Clojure."
  ([pred-expr]
   (case (::r.syntax/phase &env)
     :meander/match
     `(r.match.syntax/guard ~pred-expr)

     ;; else
     &form)))

(r.syntax/defsyntax re
  "Pattern matching operator which matches strings which match the
   regular expression `regex-pattern` with `re-matches`. Optionally, a
   second argument `capture-pattern` can be passed which will be
   matched against the result of the underlying `re-matches` call.

     (match \"foo\"
       (re #\"...\")
       true)
     ;; =>
     true

     (match \"foo\"
       (re #\"(.)(.)(.)\" [?0 ?1 ?2 ?3])
       [?0 ?1 ?2 ?3])
     ;; =>
     [\"foo\" \"f\" \"o\" \"o\"]"
  ([regex-pattern]
   (case (::r.syntax/phase &env)
     :meander/match
     `(r.match.syntax/re ~regex-pattern)
     ;; else
     &form))
  ([regex-pattern capture-pattern]
   (case (::r.syntax/phase &env)
     :meander/match
     `(r.match.syntax/re ~regex-pattern ~capture-pattern)
     ;; else
     &form)))

(r.syntax/defsyntax $
  "Pattern matching and substitution operator.

  When used as a pattern matching operator will attempt match
  `pattern` against some value that is a subtree of the target of
  pattern matching. Optionally, `context-pattern` (should be a
  variable pattern) can be passed and will be applied against a
  function. This function accepts one argument and when invoked with
  some value `x` will return the original, root target updated with
  the `x` installed at the place where `pattern` matched successfully.


      (match [:A 2]
        ($ (pred number? ?x))
        ?x)
      ;; => 2

      (match [:A 2]
        ($ ?context (pred number? ?x))
        (?context 3))
      ;; => [:A 3]"
  ([pattern]
   (case (::r.syntax/phase &env)
     (:meander/match :meander/substitute)
     `(r.syntax/$ ~pattern)

     ;; else
     &form))
  ([context-pattern pattern]
   (case (::r.syntax/phase &env)
     (:meander/match :meander/substitute)
     `(r.syntax/$ ~context-pattern ~pattern)

     ;; else
     &form)))

(r.syntax/defsyntax $*
  ([context pattern]
   (case (::r.syntax/phase &env)
     :meander/match
     `(and (~'$ ?context# (and ~pattern ?pattern#))
           (let [~context (fn
                            ([]
                             (?context# ?pattern#))
                            ([f#]
                             (?context# (f# ?pattern#)))
                            ([f# & args#]
                             (?context# (apply f# ?pattern# args#))))]))
     :meander/substitute
     `(app ~context ~pattern)

     ;; else
     &form))
  ([context pattern & patterns]
   (case (::r.syntax/phase &env)
     :meander/match
     `(and (~'$ ?context# (and ~pattern ~@patterns ?pattern#))
           (let [~context (fn
                            ([]
                             (?context# ?pattern#))
                            ([f#]
                             (?context# (f# ?pattern#)))
                            ([f# & args#]
                             (?context# (apply f# ?pattern# args#))))]))
     :meander/substitute
     `(app ~context ~pattern ~@patterns)

     ;; else
     &form)))

(r.syntax/defsyntax symbol
  "Pattern matching and substitution operator.

   When used as a pattern matching operator it will match a symbol
   with `name` and, optionally, `namespace`.

     (match 'foo/bar
       (symbol ?name)
       ?name)
     ;; => \"bar\"

     (match :foo/bar
       (symbol ?namespace ?name)
       [?namespace ?name])
     ;; => [\"foo\" \"bar\"]

   When used as a substutition operator it will create a symbol with
   `name` and, optionally, `namespace` e.g. it behaves the same as
   `clojure.core/symbol` in the context of normal substitution
   behavior.

     (subst (symbol \"foo\" \"bar\"))
     ;; => 'foo/bar

     ;; clojure.core/let
     (let [!namespaces [\"foo\" \"foo\"]
           !names [\"bar\" \"baz\"]]
       (subst [(symbol !namespaces !names) ...]))
     ;; => ['foo/bar 'foo/baz]"
  ([name]
    (case (::r.syntax/phase &env)
     :meander/match
     `(and (pred symbol?) (app name ~name))

     :meander/substitute
     `(app clj/symbol ~name)

     ;; else
     &form))
  ([namespace name]
   (case (::r.syntax/phase &env)
     :meander/match
     `(and (pred symbol?)
           (app namespace ~namespace)
           (app name ~name))

     :meander/substitute
     `(app clj/symbol ~namespace ~name)

     ;; else
     &form)))

(r.syntax/defsyntax keyword
  "Pattern matching and substitution operator.

   When used as a pattern matching operator it will match a keyword
   with `name` and, optionally, `namespace`.

     (match :foo/bar
       (keyword ?name)
       ?name)
     ;; => \"bar\"

     (match :foo/bar
       (keyword ?namespace ?name)
       [?namespace ?name])
     ;; => [\"foo\" \"bar\"]

   When used as a substutition operator it will create a keyword with
   `name` and, optionally, `namespace` e.g. it behaves the same as
   `clojure.core/keyword` in the context of normal substitution
   behavior.

     (subst (keyword \"foo\" \"bar\"))
     ;; => :foo/bar

     ;; clojure.core/let
     (let [!namespaces [\"foo\" \"foo\"]
           !names [\"bar\" \"baz\"]]
       (subst [(keyword !namespaces !names) ...]))
     ;; => [:foo/bar :foo/baz]"
  ([name]
   (case (::r.syntax/phase &env)
     :meander/match
     `(and (pred keyword?) (app name ~name))

     :meander/substitute
     `(app clj/keyword ~name)

     ;; else
     &form))
  ([namespace name]
   (case (::r.syntax/phase &env)
     :meander/match
     `(and (pred keyword?)
           (app namespace ~namespace)
           (app name ~name))

     :meander/substitute
     `(app clj/keyword ~namespace ~name)

     ;; else
     &form)))

(r.syntax/defsyntax with
  {:style/indent 1}
  ([pattern-bindings body]
   (case (::r.syntax/phase &env)
     (:meander/match :meander/substitute)
     (meander.epsilon/find pattern-bindings
       ;; Even number of syntactically valid bindings.
       [(meander.epsilon/symbol nil (meander.epsilon/re #"%.+")) _ ...]
       `(r.syntax/with ~pattern-bindings ~body)

       ;; Even number of bindings; one is invalid.
       (r.syntax/with [%invalid-name (not (symbol nil (re #"%.+")))
                       %invalid-namespace (symbol (not nil) _)
                       %invalid-binding (or %invalid-name %invalid-namespace)]
         [_ _ ... (and %invalid-binding ?x) _ . _ _ ...])
       (ex-info "with binding form must be a simple symbol the name of which begins with \"%\""
                {:invalid-binding ?x
                 :form &form})

       ;; Invalid binding form.
       (not [_ _ ...])
       (ex-info "first argument to with must be a vector of the form [%<name> <pattern> ...]"
                {:invalid-bindings pattern-bindings
                 :form &form}))

     ;; else
     &form)))