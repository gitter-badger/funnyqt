(ns funnyqt.polyfns
  "Polymorphic functions dispatching on types of model elements.
Every polyfn must be declared first, and then arbitrary many implementations
for several metamodel types may be provided.  A polyfn must have a model
element as first argument which is used to dispatch among implementations.

Every polyfn has to be declared once using `declare-polyfn`, and then
implementations for specific types map be provided using `defpolyfn`.  When a
polyfn is called, the most specific implementation for the model element type
is invoked.  In case of multiple inheritance, a class inheriting two different
implementations is an error.  It must provide an own implementation in order
to remove ambiguities.

Example
-------

Let's consider our metamodel has the types TypeA and TypeB, and a TypeC that
extends both TypeA and TypeB.  Furthermore, TypeD extends TypeC.  Lastly,
there's a TypeE with no inheritance relationships.

  ;; Declare a polyfn
  (declare-polyfn foo [elem ...]
    ;; Optional default behavior
    (str \"Don't know how to handle \" elem))

  ;; Define implementations for several types
  (defpolyfn foo TypeA [elem ...] ...)
  (defpolyfn foo TypeB [elem ...] ...)
  (defpolyfn foo TypeC [elem ...] ...)

Then, (foo objOfTypeA) invokes the first implementation, (foo objOfTypeB)
invokes the second implementation, both (foo objOfTypeC) and (foo objOfTypeD)
invoke the third implementation, and (foo objOfTypeE) invokes the default
behavior.  If no optional default behavior is specified, an exception is
thrown.

An impl can also be defined for many types at once.  In that case, a list of
types is provided:

  (defpolyfn foo (TypeX TypeY TypeZ) [elem ...] ...)"
  (:require [clojure.tools.macro :as tm]
            [funnyqt.utils       :as u]
            [funnyqt.generic     :as g]))

;;# Utility protocols

(defn find-polyfn-impl [spec-map t]
  (or (spec-map (g/qname t))
      (let [impls (map (partial find-polyfn-impl spec-map)
                       (g/mm-direct-super-classes t))]
        (if (fnext impls)
          (u/errorf "Multiple polyfn impls for type %s." (g/qname t))
          (first impls)))))

(defn build-polyfn-dispatch-table [polyfn-var cls]
  (let [meta-map (meta polyfn-var)
        spec-map (deref (::polyfn-spec-table meta-map))
        dispatch-map-atom (::polyfn-dispatch-table meta-map)]
    (let [dm (apply hash-map (mapcat (fn [c]
                                       (when-let [pfn (find-polyfn-impl spec-map c)]
                                         [c pfn]))
                                     (g/mm-classes cls)))]
      (reset! dispatch-map-atom dm))))

;;# Polyfns

(defmacro declare-polyfn
  "Decares a polymorphic function dispatching on a model element type.
  `name` is the name of the new polyfn, an optional `doc-string` may be
  provided.  The argument list's first element must be the model element on
  whose type the dispatch is done.  Polymorphic functions for several metamodel
  types are provided later using `defpolyfn`.  If an optional `body` is
  provided, this is executed if no implementation for `model-elem`s type was
  added using `defpolyfn`.  The default behavior in that case (i.e., `body`
  omitted) is to throw an exception.

  By default, when a polyfn is called for the very first time a dispatch table
  is computed which maps metamodel classes to the implementation for that type.
  If the metamodel changes afterwards, then the dispatch table might be wrong
  and needs to be recomputed which will happen if one reset!s
  the ::polyfn-dispatch-table metadata atom to nil.  One can also omit building
  a dispatch table by adding :no-dispatch-table metadata to the polyfn name.
  In that case, the implementation is computed with each call and never
  cached."

  {:arglists '([name doc-string? [model-elem & more] & body])}
  [name & more]
  (let [[name more] (tm/name-with-attributes name more)
        argvec      (first more)
        body        (next more)
        type-var    (gensym "type__")]
    `(defn ~name ~(assoc (meta name)
                    ::polyfn-spec-table     `(atom {})
                    ::polyfn-dispatch-table `(atom nil))
       ~argvec
       ~(if (:no-dispatch-table (meta (resolve name)))
          `(if-let [f# (find-polyfn-impl @(::polyfn-spec-table (meta #'~name))
                                         (g/mm-class ~(first argvec)))]
             (f# ~argvec)
             (do
               ~@(or body
                     `[(u/errorf "No polyfn implementation defined for type %s"
                                 (print-str (g/mm-class ~(first argvec))))])))
          `(let [meta-map# (meta #'~name)
                 ~type-var (g/mm-class ~(first argvec))
                 call-impl# (fn [dispatch-map#]
                              (if-let [f# (dispatch-map# ~type-var)]
                                (f# ~@argvec)
                                (do
                                  ~@(or body
                                        `[(u/errorf "No polyfn implementation defined for type %s"
                                                    (print-str ~type-var))]))))]
             (if-let [dispatch-map# (deref (::polyfn-dispatch-table meta-map#))]
               (call-impl# dispatch-map#)
               (do
                 (build-polyfn-dispatch-table #'~name ~type-var)
                 (call-impl# (deref (::polyfn-dispatch-table meta-map#))))))))))

(defmacro defpolyfn
  "Defines an implementation of the polyfn `name` for objects of type `type`
  or objects of `type1`, `type2`, etc.
  The polyfn has to be already declared using `declare-polyfn`.  `type` is a
  fully qualified type name that is used to check if the polyfn implementation
  is matching the type of `model-elem`.  The arity of the argument vector has
  to match the one of the corresponding `declare-polyfn`."

  {:arglists '([name type [model-elem & more] & body]
                 [name (type1 type2 ...) [model-elem & more] & body])}
  [name & more]
  (let [[name more]   (tm/name-with-attributes name more)
        [types more]   [(first more) (next more)]
        [argvec body] [(first more) (next more)]]
    (when-not (find (meta (resolve name)) ::polyfn-spec-table)
      (u/errorf "#'%s is not declared as a polyfn." name))
    `(do
       ~@(for [type (if (seq? types) types [types])]
           (let [^String n    (clojure.core/name type)]
             (when-not (symbol? type)
               (u/errorf "The type given to a defpolyfn must be a symbol but was %s (%s)."
                         type (class type)))
             (when (or (.startsWith n "!")
                       (.endsWith n "!"))
               (u/errorf "The type given to defpolyfn must be a plain qname symbol but was %s."
                         type))
             ;; Update the specs
             `(swap! (::polyfn-spec-table (meta (var ~name)))
                     assoc '~type (fn ~argvec ~@body))))
       ;; Reset the dispatch table
       (reset! (::polyfn-dispatch-table (meta (var ~name))) nil))))
