(ns funnyqt.query
  "Generic functions like quantified expressions."
  (:use [funnyqt.utils :only [error to-oset into-oset]]
        [funnyqt.protocols :only [adj-internal adjs-internal
                                  adj*-internal adjs*-internal]])
  (:require clojure.set))

;;# Comprehensions

(defmacro for*
  "Exactly like `clojure.core/for`, but allows :let as first binding form,
  too."
  [seq-exprs body-expr]
  (let [[bind exp] seq-exprs]
    (if (= bind :let)
      `(let ~exp
         (for ~(vec (rest (rest seq-exprs)))
           ~body-expr))
      `(for ~seq-exprs
         ~body-expr))))

;;# Quantified Expressions

(def ^{:doc "Returns logical true, iff `pred` holds forall elements in `coll`."}
  forall? every?)

(defn exists?
  "Returns logical true, iff `pred` holds at least for one element in `coll`."
  [pred coll]
  (some pred coll))

(defn exists1?
  "Returns logical true, iff `pred` holds for exactly one element in `coll`."
  [pred coll]
  (let [s (filter pred coll)]
    ;; There must be one and no other
    (and (seq s) (not (next s)))))

;;# Sequence Functions

(defn member?
  "Returns true, iff `e` is a member of `coll`."
  [e ^java.util.Collection coll]
  (if (seq coll)
    (.contains coll e)
    false))

(defn the
  "Returns the only element of seq `s` (which satisfies `pred`) and errors if
  `s` contains more or less elements."
  ([s]
     (if-let [f (first s)]
       (if (next s)
         (error (format "seq contains more than one element!"))
         f)
       (error (format "seq contains zero elements!"))))
  ([pred s]
     (the (filter pred s))))

(defn- pred-seq-internal
  [s p acc]
  (lazy-seq
   (if (seq s)
     (pred-seq-internal (rest s)
                        (first s)
                        (conj acc [p (first s)]))
     acc)))

(defn pred-seq
  "Predecessor Seq: Returns a lazy seq of pairs of seq `s`s elements.
  Each pair has the form [elems-predecessor-in-s elem]."
  [s]
  (pred-seq-internal s nil []))

(defn- succ-seq-internal
  [s acc]
  (lazy-seq
   (if (seq s)
     (succ-seq-internal (rest s)
                        (conj acc [(first s) (fnext s)]))
     acc)))

(defn succ-seq
  "Successor Seq: Returns a lazy seq of pairs of seq `s`s elements.
  Each pair has the form [elem elems-successor-in-s]."
  [s]
  (succ-seq-internal s []))

;;# Logical (higher-order) funs

(defn xor
  "Logical XOR: returns true, iff exactly one argument is true.
  (xor) returns false."
  ([] false)
  ([f & r]
     (loop [t false, f (conj r f)]
       (if(seq f)
         (let [fv (first f)]
           (cond
            (and t fv)       false
            (and (not t) fv) (recur true (rest f))
            :else            (recur t (rest f))))
         t))))

(defn xor-fn
  "Takes a seq of predicates `ps` and returns a varargs function that returns
  logical true, iff exactly one of the predicates returns true."
  [& ps]
  (fn [& args]
    (loop [good false, p ps]
      (if (seq p)
        (let [r (apply (first p) args)]
          (if (and good r)
            false  ;; second true value, so stop it!
            (recur (or r good) (rest p))))
        good))))

(defn and-fn
  "Takes a seq of predicates `ps` and returns a varargs function that returns
  logical true, iff all predicates return true.
  If no predicate is given, the returned fn returns constantly true."
  [& ps]
  (if (seq ps)
    (fn [& args]
      (apply (apply every-pred ps) args))
    (constantly true)))

(defn nand-fn
  "Takes a seq of predicates `ps` and returns a varargs function that returns
  logical true, iff at least one predicate returns false.
  If no predicate is given, the returned fn returns constantly false."
  [& ps]
  (complement (apply and-fn ps)))

(defn or-fn
  "Takes a seq of predicates `ps` and returns a varargs function that returns
  logical true, iff at least one of the predicates returns true.
  If no predicate is given, the returned fn returns constantly false."
  [& ps]
  (if (seq ps)
    (fn [& args]
      (apply (apply some-fn ps) args))
    (constantly false)))

(defn nor-fn
  "Takes a seq of predicates `ps` and returns a varargs function that returns
  logical true, iff none of the predicate returns true.
  If no predicate is given, the returned fn returns constantly true."
  [& ps]
  (complement (apply or-fn ps)))

;;# Sorting

(defn seq-compare
  "Returns a sequence comparator function that compares 2 sequences element by
  element according to the given comparators `cmps`, i.e., the first 2 elements
  are compared with the first comparator, the second 2 elements with the second
  comparator, and so on.  Evaluates only as many comparators as are needed to
  distinguish the sequences, i.e., evaluates the comparators until one returns
  non-zero.

  `cmps` must be comparator functions that get 2 elements and returns 0, if the
  elements are equal, a negative integer, if the first is \"smaller\", or a
  positive integer, if the second is \"smaller\".

  If all comparators evaluate to 0, then the hash-codes are used as a last
  resort.

  Example: Sort a seq of 3-tuples of form [number number string] with
  descending order of the first component, ascending order of second component,
  and ascending orded of third components.  Clearly, for numbers - is a valid
  comparator, and for the strings we use compare which sorts lexicographically.

    (sort (seq-compare #(- %2 %1) - compare)
          [[1 10 \"b\"] [3 7 \"b\"] [1 2 \"b\"] [1 10 \"c\"] [3.0 17 \"a\"]])
    ;=> ([3 7 \"b\"] [3.0 17 \"a\"] [1 2 \"b\"] [1 10 \"b\"] [1 10 \"c\"])"
  [& cmps]
  (fn [a b]
    (or (first (remove zero? (map #(%1 %2 %3) cmps a b)))
        (- (hash a) (hash b)))))

;;# Adjacencies

;; Those are defined in funnyqt.query.tg and funnyqt.query.emf

(defn adj
  "Traverses `roles` starting at `elem`, and returns the target object.
  Errors if a role is undefined, intermediate targets are nil, or there are
  more elements that can be reached that way.
  For EMF EObjects, only single-valued references are allowed."
  [elem & roles]
  (adj-internal elem roles))

(defn adj*
  "Like `adj`, but doesn't error if some role is not defined.  In that case, it
  simply returns nil.
  For EMF EObjects, only single-valued references are allowed."
  [elem & roles]
  (adj*-internal elem roles))

(defn adjs
  "Traverses `roles` starting at `elem`, and returns the seq of target objects.
  Errors if a role is undefined or intermediate targets are nil."
  [elem & roles]
  (adjs-internal elem roles))

(defn adjs*
  "Like `adjs`, but doesn't error if some role is not defined.  In that case,
  it simply returns nil."
  [elem & roles]
  (adjs*-internal elem roles))


;;# Regular Path Expressions

(def ^{:dynamic true
       :doc "Path application function.
  Will be bound dynamically by the different `reachables' functions."}
  *p-apply*)

(def ^{:dynamic true
       :doc "Path restriction function.
  Will be bound dynamically by the different `reachables' functions."}
  *p-restr*)

(defn p-seq
  "Path sequence starting at `v` and traversing `p`.
  `v` may be a vertex or a seq of vertices.
  `p` is a varargs seq of path descriptions."
  [v & p]
  (if (seq p)
    (recur (*p-apply* v (first p)) (rest p))
    (to-oset v)))

(defn p-opt
  "Path option starting at `v` and maybe traversing `p`.
  `v` may be a vertex or a seq of vertices.
  `p` is a path description."
  [v p]
  (into-oset v (*p-apply* v p)))

(defn p-alt
  "Path alternative starting at `v` and traversing one of `p`.
  `v` may be a vertex or a seq of vertices.
  `p` is a varags seq of the alternative path descriptions."
  [v & p]
  (to-oset (mapcat #(*p-apply* v %) p)))

(defn p-*
  "Path iteration starting at `v` and traversing `p` zero or many times.
  `v` may be a vertex or a seq of vertices.
  `p` is a path description."
  ([v p]
     (p-* v p (to-oset v)))
  ([v p ret]
     (let [n (clojure.set/difference
              (to-oset (*p-apply* v p))
              ret)]
       (if (seq n)
         (recur n p (into-oset ret n))
         ret))))

(defn p-+
  "Path iteration starting at `v` and traversing `p` one or many times.
  `v` may be a vertex or a seq of vertices.
  `p` is a path description."
  [v p]
  (p-* v p (ordered.set/ordered-set)))

(defn p-exp
  "Path exponent starting at `v` and traversing `p` `n` times, or at least `l`
  and at most `p` times.
  `v` may be a vertex or a seq of vertices.
  `n` or `l` and `v` are integers with `l` <= `b`.
  `p` is a path description."
  ([v l u p]
     {:pre [(<= l u) (>= l 0) (>= u 0)]}
     (loop [i (- u l), s (p-exp v l p)]
       (if (pos? i)
         (let [ns (into s (*p-apply* s p))]
           (if (= (count s) (count ns))
             s
             (recur (dec i) ns)))
         s)))
  ([v n p]
     {:pre [(>= n 0)]}
     (if (zero? n)
       (to-oset v)
       (recur (*p-apply* v p) (dec n) p))))

(defn p-restr
  "Path restriction concerning `ts` and `pred` on each object in `objs`.
  ts is a type specification (see `type-matcher` and `eclass-matcher`), `pred`
  a predicate."
  ([objs ts]
     (*p-restr* objs ts identity))
  ([objs ts pred]
     (*p-restr* objs ts pred)))
