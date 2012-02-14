(ns funnyqt.emf.core
  "Core functions for accessing and manipulating EMF models."
  (:use funnyqt.utils)
  (:use funnyqt.generic)
  (:import
   [org.eclipse.emf.ecore.xmi.impl XMIResourceImpl]
   [org.eclipse.emf.common.util URI]
   [org.eclipse.emf.ecore EcorePackage EPackage EObject EModelElement EClassifier EClass
    EDataType EEnumLiteral EEnum EFactory ETypedElement EAnnotation EAttribute]))

(add-long-doc! "TODO")

;;* Code

;;** Metamodel

(def ^org.eclipse.emf.ecore.EPackage$Registry
  epackage-registry org.eclipse.emf.ecore.EPackage$Registry/INSTANCE)

(defn- register-epackages
  [pkgs]
  (doseq [^EPackage p pkgs]
    (.put epackage-registry (.getNsURI p) p)
    (let [subs (.getESubpackages p)]
      (when (seq subs)
        (register-epackages subs)))))

(defn load-metamodel
  "Loads the Ecore metamodel from the ecore file `f'.
  Returns as seq of (usually one) root EPackages.
  All EPackages are registered recursively."
  [f]
  (let [uri (URI/createURI f)
        res (XMIResourceImpl. uri)]
    (doto res
      (.load (.getDefaultLoadOptions res)))
    (let [pkgs (seq (.getContents res))]
      (register-epackages pkgs)
      pkgs)))

(def ^:dynamic *ns-uris* nil)
(defmacro with-ns-uris [uris & body]
  `(binding [*ns-uris* ~uris]
     ~@body))

(defn epackages
  "The lazy seq (pkg subpkg...).
  If no package is given, the lazy seq of all registered packages is returned."
  ([]
     (mapcat #(epackages (.getEPackage epackage-registry %))
             (or *ns-uris* (keys epackage-registry))))
  ([^EPackage pkg]
     (when pkg
       (cons pkg (map epackages (.getESubpackages pkg))))))

(defn eclassifiers
  "The lazy seq of EClassifiers."
  []
  (mapcat (fn [^EPackage ep]
            (.getEClassifiers ep))
          (epackages)))

(defn eclasses
  "The lazy seq of EClasses."
  []
  (filter #(instance? EClass %) (eclassifiers)))

(defn eclassifier
  "Returns the eclassifier with the given `name'."
  [name]
  (some (fn [^EClassifier ec]
          (when (= (.getName ec) (clojure.core/name name))
            ec))
        (eclassifiers)))

;;** Model

(defn load-model
  "Loads an EMF model from the XMI file `f'.
  Returns a seq of the models top-level elements."
  [f]
  (let [uri (URI/createURI f)
        res (XMIResourceImpl. uri)]
    (doto res
      (.load (.getDefaultLoadOptions res)))
    (seq (.getContents res))))

(defn- type-matcher-1
  "Returns a matcher for elements Foo, !Foo, Foo!, !Foo!."
  [c]
  (let [v     (type-with-modifiers (name c))
        neg   (v 0)
        qname (v 1)
        exact (v 2)
        ^EClassifier type  (eclassifier qname)]
    (cond
     (and (not neg) (not exact)) (fn [^EClass x] (.isInstance type x))
     (and (not neg) exact)       (fn [^EClass x] (identical? type (.eClass x)))
     (and neg       (not exact)) (fn [^EClass x] (not (.isInstance type x)))
     :default                    (fn [^EClass x] (not (identical? type (.eClass x)))))))

(defn type-matcher
  "Returns a matcher for either nil, !Foo!, [Foo Bar! !Baz], [:and 'Foo 'Bar],
  or [:or 'Foo 'Bar].  In a collection spec, the first element may be one of
  the keywords :or (default), :nor, :and, :nand, or :xor with the usual logic
  semantics."
  [ts]
  (cond
   (nil? ts)   identity
   (fn? ts)    ts
   (qname? ts) (type-matcher-1 ts)
   (coll? ts)  (if (seq ts)
                  (let [f (first ts)
                        [op r] (case f
                                 :and  [every-pred (next ts)]
                                 :nand [nand-fn    (next ts)]
                                 :or   [some-fn    (next ts)]
                                 :nor  [nor-fn     (next ts)]
                                 :xor  [xor-fn     (next ts)]
                                 [some-fn    ts])
                        t-matchers (map type-matcher r)]
                    (apply op t-matchers))
                  ;; Empty collection given: (), [], that's also ok
                  identity)
   :else (RuntimeException.
          (format "Don't know how to create a type matcher for %s" ts))))

(defprotocol EObjects
  (eobjects-internal [this tm]
    "Returns a seq of all directly and indirectly contained EObjects
  whose type matches the type-matcher tm."))

(extend-protocol EObjects
  EObject
  (eobjects-internal [this tm]
    (filter tm (cons this (iterator-seq (.eAllContents this)))))
  clojure.lang.ISeq
  (eobjects-internal [this tm]
    (mapcat #(eobjects-internal % tm) this)))

(defn eobjects
  ([x]
     (eobjects-internal x identity))
  ([x ts]
     (eobjects-internal x (type-matcher ts))))


;;** Printing

;;*** Normal toString() output

;; TODO: We don't handle EFactories, ETypedElements, and EAnnotations yet.

(defn- feature-str
  "Returns a description of enabled features `fs'.
  fs => [test-val desc-str]*"
  ([fs]
     (feature-str [] fs))
  ([s fs]
     (if (seq fs)
       (let [[f n] (first fs)]
         (recur (if f (conj s n) s)
                (rest fs)))
       (when-let [r (seq s)]
         (str " " r)))))

(defmethod print-method EClass
  [^EClass ec ^java.io.Writer out]
  (.write out
          (str "#<EClass "
               (.getName ec)
               (feature-str
                [[(.isAbstract ec)  :abstract]
                 [(.isInterface ec) :interface]])
               ">")))

(defmethod print-method EDataType
  [^EDataType edt ^java.io.Writer out]
  (.write out
          (str "#<EDataType " (.getName edt)
               (feature-str
                [[(.isSerializable edt) :serializable]])
               ">")))

(defmethod print-method EPackage
  [^EPackage ep ^java.io.Writer out]
  (.write out
          (str "#<"
               (if (instance? EcorePackage ep)
                 "EcorePackage "
                 "EPackage ")
               (.getName ep)
               (let [m (apply hash-map
                              (mapcat (fn [[v k]]
                                        (when v
                                          [k v]))
                                      [[(.getNsPrefix ep) :nsPrefix]
                                       [(.getNsURI ep)    :nsURI]]))]
                 (when (seq m)
                   (str " " m)))
               ">")))

(defmethod print-method EEnumLiteral
  [^EEnumLiteral el ^java.io.Writer out]
  (.write out
          (str "#<EEnumLiteral"
               (-> el .getEEnum .getName)
               "/"
               (.getLiteral el)
               ">")))

(defmethod print-method EObject
  [^EObject eo ^java.io.Writer out]
  (.write out
          (if (or (instance? EFactory      eo)
                  (instance? ETypedElement eo)
                  (instance? EAnnotation   eo))
            ;; Usual toString() for those
            (str eo)
            ;; Custom toString() for the others
            (let [ec (.eClass eo)]
              (str "#<"
                   (-> ec .getName)
                   (let [m (apply hash-map
                                  (mapcat (fn [^EAttribute attr]
                                            [(keyword (.getName attr)) (.eGet eo attr)])
                                          (seq (.getEAttributes ec))))]
                     (when (seq m)
                       (str " " m)))
                   ">")))))


