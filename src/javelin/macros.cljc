(ns javelin.macros
  (:refer-clojure :exclude [dosync])
  (:require #?@(:cljs [[javelin.macros.js  :refer [hoist]]]
                :clj  [[javelin.macros.clj :refer [hoist]]
                       [net.cgrand.macrovich :as macro]]))
  #? (:cljs (:require-macros [net.cgrand.macrovich :as macro])))

(macro/deftime

(defn- destructure* [bindings]
  #? (:clj (clojure.core/destructure bindings)
      :cljs (cljs.core$macros/destructure bindings)))

(defn extract-syms
  "Extract symbols that will be bound by bindings, including autogenerated
  symbols produced for destructuring."
  [bindings]
  (map first (partition 2 (destructure* bindings))))

(defn extract-syms-without-autogen
  "Extract only the symbols that the user is binding from bindings, omitting
  any intermediate autogenerated bindings used for destructuring. A trick is
  used here taking advantage of the fact that gensym names are produced as a
  side effect -- successive calls to extract-syms are not redundant."
  [bindings]
  (let [syms1 (set (extract-syms bindings))
        syms2 (set (extract-syms bindings))]
    (seq (clojure.set/intersection syms1 syms2))))

(defn bind-syms
  "Given a binding form, returns a seq of the symbols that will be bound.

  (bind-syms '[{:keys [foo some.ns/bar] :as baz} baf & quux])
  ;=> (foo bar baz baf quux)"
  [form]
  (extract-syms-without-autogen [form nil]))

(defmacro with-let
  "Binds resource to binding and evaluates body.  Then, returns
  resource.  It's a cross between doto and with-open."
  [[binding resource] & body]
  `(let [~binding ~resource] ~@body ~binding))

(defn cell* [x env]
  (let [[f args] (hoist x env)]
    `((javelin.core/formula ~f) ~@args)))

(defmacro cell=
  "Creates a formula cell"
  ([expr] (cell* expr &env))
  ([expr f]
   (let [c (gensym)]
     `(with-let [~c (cell= ~expr)]
        ~(macro/case
           :cljs `(set! (.-update ~c) ~f)
           :clj  `(clojure.core/dosync
                    (ref-set (.-update ~c) ~f)))))))

(defmacro set-cell!=
  ([c expr]
   `(set-cell!= ~c ~expr nil))
  ([c expr updatefn]
   (let [[f args] (hoist expr &env)]
     `(javelin.core/set-formula! ~c ~f [~@args] ~updatefn))))

(defmacro defc
  ([sym expr] `(def ~sym (javelin.core/cell ~expr)))
  ([sym doc expr] `(def ~sym ~doc (javelin.core/cell ~expr))))

(defmacro defc=
  ([sym expr] `(def ~sym (cell= ~expr)))
  ([sym doc & [expr f]]
   (let [doc? (string? doc)
         f    (when-let [f' (if doc? f expr)] [f'])
         expr (if doc? expr doc)
         doc  (when doc? [doc])]
     `(def ~sym ~@doc (cell= ~expr ~@f)))))

(defmacro formula-of
  "ALPHA: this macro may change.

  Given a vector of dependencies and one or more body expressions, emits a
  form that will produce a formula cell. The dependencies must be names that
  will be re-bound to their values within the body. No code walking is done.
  The value of the formula cell is computed by evaluating the body expressions
  whenever any of the dependencies change.

  Note: the dependencies need not be cells.

  E.g.
      (def x 100)
      (def y (cell 200))
      (def z (cell= (inc y)))

      (def c (formula-of [x y z] (+ x y z)))

      (deref c) ;=> 501

      (swap! y inc)
      (deref c) ;=> 503
  "
  [deps & body]
  (assert (and (vector? deps) (every? symbol? deps))
          "first argument must be a vector of symbols")
  `((javelin.core/formula (fn [~@deps] ~@body)) ~@deps))

(defmacro formulet
  "ALPHA: this macro may change.

  Given a vector of binding-form/dependency pairs and one or more body
  expressions, emits a form that will produce a formula cell. Each binding
  form is bound to the value of the corresponding dependency within the body.
  No code walking is done. The value of the formula cell is computed by
  evaluating the body expressions whenever any of the dependencies change.

  Note: the dependency expressions are evaluated only once, when the formula
  cell is created, and they need not evaluate to javelin cells.

  E.g.
      (def a (cell 42))
      (def b (cell {:x 100 :y 200}))

      (def c (formulet [v (cell= (inc a))
                        w (+ 1 2)
                        {:keys [x y]} b]
                (+ v w x y)))

      (deref c) ;=> 346

      (swap! a inc)
      (deref c) ;=> 347
  "
  [bindings & body]
  (let [binding-pairs (partition 2 bindings)]
    (assert (and (vector? bindings) (even? (count binding-pairs)))
            "first argument must be a vector of binding pairs")
    `((javelin.core/formula (fn [~@(map first binding-pairs)] ~@body))
      ~@(map second binding-pairs))))

(defmacro -cell-let-1
  [[bindings c] & body]
  (let [syms  (bind-syms bindings)
        dcell `((javelin.core/formula (fn [~bindings] [~@syms])) ~c)]
    `(let [[~@syms] (javelin.core/cell-map identity ~dcell)] ~@body)))

(defmacro cell-let
  [[bindings c & more] & body]
  (if-not (seq more)
    `(-cell-let-1 [~bindings ~c] ~@body)
    `(-cell-let-1 [~bindings ~c]
                 (cell-let ~(vec more) ~@body))))

(defmacro dosync
  "Evaluates the body within a Javelin transaction. Propagation of updates
  to formula cells is deferred until the transaction is complete. Input
  cells *will* update during the transaction. Transactions may be nested."
  [& body]
  `(javelin.core/dosync* (fn [] ~@body)))

(defmacro cell-doseq
  "Takes a vector of binding-form/collection-cell pairs and one or more body
  expressions, similar to clojure.core/doseq. Iterating over the collection
  cells produces a sequence of items that may grow, shrink, or update over
  time. Whenever this sequence grows the body expressions are evaluated (for
  side effects) exactly once for each new location in the sequence. Bindings
  are bound to cells that refer to the item at that location.

  Consider:

      (def things (cell [{:x :a} {:x :b} {:x :c}]))

      (cell-doseq [{:keys [x]} things]
        (prn :creating @x)
        (add-watch x nil #(prn :updating %3 %4)))

      ;; the following is printed -- note that x is a cell:

      :creating :a
      :creating :b
      :creating :c

  Shrink things by removing the last item:

      (swap! things pop)

      ;; the following is printed (because the 3rd item in things is now nil,
      ;; since things only contains 2 items) -- note that the doit function is
      ;; not called (or we would see a :creating message):

      :updating :c nil

  Grow things such that it is one item larger than it ever was:

      (swap! things into [{:x :u} {:x :v}])

      ;; the following is printed (because things now has 4 items, so the 3rd
      ;; item is now {:x :u} and the max size increases by one with the new
      ;; item {:x :v}):

      :updating nil :u
      :creating :v

  A weird imagination is most useful to gain full advantage of all the features."
  [bindings & body]
  (if (= 2 (count bindings))
    `(javelin.core/cell-doseq*
       ((javelin.core/formula seq) ~(second bindings))
       (fn [item#] (cell-let [~(first bindings) item#] ~@body)))
    (let [pairs   (partition 2 bindings)
          lets    (->> pairs (filter (comp (partial = :let) first)) (mapcat second))
          binds*  (->> pairs (take-while (complement (comp keyword? first))))
          mods*   (->> pairs (drop-while (complement (comp keyword? first))) (mapcat identity))
          syms    (->> binds* (mapcat (comp bind-syms first)))
          exprs   (->> binds* (map second))
          gens    (take (count exprs) (repeatedly gensym))
          fors    (-> (->> binds* (map first)) (interleave gens) (concat mods*))]
      `(javelin.core/cell-doseq*
         ((javelin.core/formula (fn [~@gens] (for [~@fors] [~@syms]))) ~@exprs)
         (fn [item#] (cell-let [[~@syms] item#, ~@lets] ~@body))))))

)
