(ns meander.bsless.dev
  (:require
   [clojure.test :as t]))

(defprotocol Monad
  (-bind [this f])
  (-return [from this]))

;;; Left identity: return a >>= f ≡ f a
;;;
;;; Right identity: m >>= return ≡ m
;;;
;;; Associativity: (m >>= f) >>= g ≡ m >>= (\x -> f x >>= g)

(extend-protocol Monad
  clojure.lang.ISeq
  (-bind [this f] (mapcat f this))
  (-return [_ x] (list x))
  clojure.lang.IPersistentVector
  (-bind [this f] (into [] (mapcat f) this))
  (-return [_ x] [x])
  clojure.lang.Fn
  (-bind [this f] (fn [] (-bind (this) f)))
  (-return [_ x] (fn [] x)))

(t/deftest monad-laws
  (t/testing "left identity"
    (t/testing "ISeq"
      (let [f #(range %)
            a 4]
        (t/is (= (-bind (-return () a) f) (f a)))))
    #_
    (t/testing "Fn"
      (let [f #(range %)
            ctx (fn [])
            a '(4)]
        ((-bind (-return ctx a) f))
        #_(t/is (= (-bind (-return ctx a) f) (f a))))))
  (t/testing "Right identity"
    (t/testing "ISeq"
      (let [a '(1)]
        (t/is (= a (-bind a (partial -return ()))))))))

(comment
  (-bind '(1 2 3) (fn [x] (range x)))
  (-return '(1 2 3) 1)

  (=
   ((-bind (fn [] '(1 2 3)) (fn [x] (range x))))
   (-bind '(1 2 3) (fn [x] (range x)))))

(defmacro mdo*
  {:style/indent 1}
  [[binding expr] body]
  `(-bind ~expr (fn [~binding] ~body)))

(defmacro mdo
  {:style/indent 1}
  [bindings body]
  (if (seq bindings)
    `(mdo* ~(take 2 bindings) (mdo ~(drop 2 bindings) ~body))
    body))

(defprotocol MonadPlus
  (-mzero [this])
  (-mplus [this that]))

(defmacro mplus
  [this that]
  `(lazy-seq
    (-mplus ~this (lazy-seq ~that))))

(extend-protocol MonadPlus
  clojure.lang.ISeq
  (-mzero [this] ())
  (-mplus [this that] (concat this that))
  clojure.lang.IPersistentVector
  (-mzero [this] [])
  (-mplus [this that] (into this that))
  #_#_
  clojure.lang.Fn
  (-mplus [this that] (fn [] (-mplus that (this)))))

(comment
  (-mzero '(1 2 3))
  (-mplus '(1 2 3) '(4 5 6))
  (-mzero [1 2])
  (-mplus [1 2] [1 2])
  ((-mplus (fn [] [1]) [2])))

(defprotocol MSplit
  ;; msplit :: m a → m (Maybe (a, m a))
  (-msplit [this]))

(extend-protocol MSplit
  clojure.lang.ISeq
  (-msplit [this]
    (-return this (when-let [xs (seq this)]
                    [(first xs) (rest xs)])))
  clojure.lang.IPersistentVector
  (-msplit [this]
    (-return this (when (seq this)
                    [(first this) (subvec this 1)])))
  clojure.lang.Fn
  (-msplit [this] (-return this
                           (mdo* [xs (-msplit (this))]
                             [(first xs) (-return this (second xs))]))))

(comment
  (-msplit '(1 2 3 4))
  (-msplit '[1 2 3 4])
  (-msplit '())
  (-msplit '(1))
  ((second ((-msplit (fn [] '(1 2 3)))))))

(defprotocol ILogicM
  ;; interleave :: m a → m a → m a
  (-interleave [this that])

  ;; >>- :: m a → (a → m b) → m b
  (>>- [this f])

  ;; ifte :: m a → (a → m b) → m b → m b
  (-ifte [this then else])

  ;; once :: m a → m a
  (-once [this]))

(extend-protocol ILogicM
  Object
  (-interleave [sg1 sg2]
    (mdo [r (-msplit sg1)]
      (if-let [[sg11 sg12] r]
        (mplus (-return sg1 sg11) (-interleave sg2 sg12))
        sg2)))
  (>>- [sg g]
    (mdo [r (-msplit sg)]
      (if-let [[sg1 sg2] r]
        (-interleave (g sg1) (>>- sg2 g))
        (-mzero sg))))

  (-ifte [t th el]
    (mdo [r (-msplit t)]
      (if-let [[sg1 sg2] r]
        (-mplus (th sg1) (-bind sg2 th))
        el)))
  (-once [m]
    (mdo [r (-msplit m)]
      (if-let [[sg1] r]
        (-return m sg1)
        (-mzero m)))))

(comment
  (-interleave '(1 2 3) '(4 5 6))
  (take 42 (-interleave '(1 2 3) (range)))
  (-interleave '[1 2 3] '[4 5 6])
  (>>- '(a b c) (fn [_] (range 10))))

