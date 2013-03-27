(ns funnyqt.visualization
  "Model visualization functions.

Using the function `print-model`, TGraph and EMF models can be visualized,
either in a window or by printing them to PDF/PNG/JPG/SVG documents."
  (:use funnyqt.protocols)
  (:use funnyqt.query)
  (:use funnyqt.utils)
  (:require [funnyqt.emf :as emf])
  (:require [funnyqt.tg  :as tg])
  (:import (de.uni_koblenz.jgralab Vertex Edge Graph AttributedElement)
           (de.uni_koblenz.jgralab.schema Attribute EdgeClass AggregationKind)
           (org.eclipse.emf.ecore EObject EAttribute EReference)
           (funnyqt.emf_protocols EMFModel)))

;;* Visualization

;;** Generic stuff

(def ^{:private true, :dynamic true
       :doc "Only these objects (minus excluded ones) are printed."}
  *included*)

(def ^{:private true, :dynamic true
       :doc "Objects to be skipped from printing."}
  *excluded*)

(def ^{:private true, :dynamic true
       :doc "These objects are printed in color."}
  *marked*)

(def ^{:private true, :dynamic true
       :doc "Print class names fully qualified."}
  *print-qualified-names*)

(defn ^:private dot-included? [o]
  (and (or (not *included*) ;; Not set ==> all are included
           (*included* o))
       (not (*excluded* o))))

(defn ^:private dot-escape [s]
  (if s
    (let [r (-> (str s)
                (clojure.string/replace "<" "&lt;")
                (clojure.string/replace ">" "&gt;"))]
      (if (string? s)
        (str "\\\"" r "\\\"")
        r))
    "null"))

(defn ^:private dot-options [opts]
  (letfn [(update [m k v]
            (if (get m k)
              m
              (assoc m k v)))]
    (let [m (apply hash-map opts)
          ;; :name is special and no DOT attr, so remove it
          gname (or (:name m) "Model")
          m (dissoc m :name)
          ;; ditto for :include
          include (:include m)
          m (dissoc m :include)
          ;; ditto for :exclude
          exclude (:exclude m)
          m (dissoc m :exclude)
          ;; ditto for :mark
          mark (:mark m)
          m (dissoc m :mark)
          ;; ditto for :qualified-names
          qnames (:qualified-names m)
          m (dissoc m :qualified-names)
          ;; Add default values
          m (update m :ranksep 1.5)]
      (with-meta m
        {:name gname, :include include, :exclude exclude,
         :mark mark, :qualified-names qnames}))))

;;** EMF stuff

(def ^{:private true, :dynamic true
       :doc "Opposite refs: those are not dotted, cause we already
  printed them from the other direction."}
  *emf-opposite-refs*)

(defn ^:private emf-dot-id [o]
  (str "O" (Integer/toString (hash o) (Character/MAX_RADIX))))

(defn ^:private emf-dot-attributes [^EObject eo]
  (reduce str
          (for [^EAttribute attr (.getEAllAttributes (.eClass eo))
                :let [n (.getName attr)]]
            (str n " = " (dot-escape (emf/eget eo (keyword n))) "\\l"))))

(defn ^:private emf-dot-eobject [^EObject eo]
  (when (dot-included? eo)
    (let [h (emf-dot-id eo)]
      (str "  " h
           " [label=\"{{:" (if *print-qualified-names*
                            (qname eo)
                            (.getName (.eClass eo)))
           "}|"
           (emf-dot-attributes eo)
           "}\", shape=record, fontname=Sans, fontsize=14, "
           "color=" (if (*marked* eo)
                      "red" "black")
           "];\n"))))

(defn ^:private emf-dot-contentrefs [^EObject eo]
  (let [h (emf-dot-id eo)
        dist (atom [2.0 4.0])]
    (reduce str
            (for [^EReference ref (seq (.getEAllContainments (.eClass eo)))
                  :let [oref (.getEOpposite ref)
                        n (.getName ref)]
                  t (when-let [x (emf/eget eo ref)]
                      (if (coll? x) x [x]))
                  :when (dot-included? t)]
              (do
                (swap! dist (fn [[x y]] [y x]))
                (str "  " h " -> " (emf-dot-id t)
                     " [dir=both, arrowtail=diamond, fontname=Sans, "
                     "labelangle=0, labeldistance= " (first @dist) ", "
                     "label=\"                    \", "
                     "headlabel=\"" n "\""
                     (when oref
                       (str ", taillabel=\"" (.getName oref) "\""))
                     "];\n"))))))

(defn ^:private emf-dot-crossrefs [^EObject eo]
  (let [h (emf-dot-id eo)
        dist (atom  [2.0 4.0])]
    (reduce str
            (for [^EReference ref (.getEAllReferences (.eClass eo))
                  :when (not (member? ref @*emf-opposite-refs*))
                  :when (not (or (.isContainment ref)
                                 (.isContainer ref)))
                  :let [oref (.getEOpposite ref)]
                  t (emf/ecrossrefs eo ref)
                  :when (dot-included? t)
                  :let [h2 (emf-dot-id t)]]
              (do
                (when oref
                  (swap! *emf-opposite-refs* conj oref))
                (swap! dist (fn [[x y]] [y x]))
                (str "  " h " -> " h2
                     " [dir="
                     (if oref "none" "forward")
                     ", fontname=Sans, "
                     "labelangle=0, labeldistance= " (first @dist) ", "
                     "label=\"                    \", "
                     "headlabel=\"" (.getName ref) "\""
                     (when oref
                       (str ", taillabel=\"" (.getName oref) "\""))
                     "];\n"))))))

(defn ^:private emf-dot-ereferences [eo]
  (when (dot-included? eo)
    (str (emf-dot-contentrefs eo)
         (emf-dot-crossrefs eo))))

(defn emf-dot-model [m]
  (str
   (reduce str
           (map emf-dot-eobject
                (emf/eallobjects m)))
   (binding [*emf-opposite-refs* (atom #{})]
     (reduce str
             (map emf-dot-ereferences
                  (emf/eallobjects m))))))

;;** TGraph stuff

(defn tg-dot-attributes [^AttributedElement elem]
    (reduce str
            (for [^Attribute attr (.getAttributeList (.getAttributedElementClass elem))
                  :let [n (.getName attr)]]
              (str n " = " (dot-escape (tg/value elem (keyword n))) "\\l"))))

(defn tg-dot-vertex [^Vertex v]
  (when (dot-included? v)
    (str "  v" (tg/id v)
         " [label=\"{{v" (tg/id v) ": "
         (if *print-qualified-names*
           (qname v)
           (.getSimpleName (.getAttributedElementClass v)))
         "}|"
         (tg-dot-attributes v)
         "}\", shape=record, fontname=Sans, fontsize=14, "
         "color=" (if (*marked* v) "red" "black")
         "];\n")))

(defn tg-dot-edge [^Edge e]
  (when (and (not (*excluded* e))
             (dot-included? (tg/alpha e))
             (dot-included? (tg/omega e)))
    (let [^EdgeClass ec (.getAttributedElementClass e)
          fr (-> ec  .getFrom .getRolename)
          fak (-> ec .getFrom .getAggregationKind)
          tr (-> ec  .getTo   .getRolename)
          tak (-> ec .getTo   .getAggregationKind)]
      (str "  v" (tg/id (tg/alpha e)) " -> v" (tg/id (tg/omega e))
           " [id=e" (tg/id e) ", label=\"e" (tg/id e) ": "
           (if *print-qualified-names*
             (qname e)
             (.getSimpleName (.getAttributedElementClass e)))
           "\\l"
           (tg-dot-attributes e)
           "\", dir=both, fontname=Sans, "
           "labelangle=0, labeldistance=3.0, "
           "color=" (if (*marked* e) "red" "black")
           (when (seq fr) (str ", taillabel=\"" fr "\""))
           (when (seq tr) (str ", headlabel=\"" tr "\""))
           (condp = fak
             AggregationKind/COMPOSITE ", arrowhead=diamond"
             AggregationKind/SHARED    ", arrowhead=odiamond"
             AggregationKind/NONE      ", arrowhead=normal")
           (condp = tak
             AggregationKind/COMPOSITE ", arrowtail=diamond"
             AggregationKind/SHARED    ", arrowtail=odiamond"
             AggregationKind/NONE      ", arrowtail=none")
           "];\n"))))

(defn tg-dot-model [g]
  (str
   (reduce str
           (map tg-dot-vertex
                (tg/vseq g)))
   (reduce str
           (map tg-dot-edge
                (tg/eseq g)))))

;;** Main

(def dot-model-fns
  {EMFModel emf-dot-model
   Graph    tg-dot-model})

(defn ^:private dot-model [m opts]
  (let [opts (dot-options opts)]
    (when (and (:name (meta opts))
               (re-matches #"\S*(\s|[-])\S*" (:name (meta opts))))
      (errorf "The :name must not contain whitespace or hyphens: '%s'"
              (:name (meta opts))))
    (binding [*included* (when-let [included (:include (meta opts))]
                           (set included))
              *excluded* (set (:exclude (meta opts)))
              *marked*   (when-let [mark (:mark (meta opts))]
                           (cond
                            (fn? mark)   mark
                            (coll? mark) (set mark)
                            :else (errorf ":mark must be a function or collection but was a %s: %s."
                                          (class mark) mark)))
              *print-qualified-names* (:qualified-names (meta opts))]
      (str "digraph " (:name (meta opts)) " {"
           (clojure.string/join
            \,
            (for [[k v] opts]
              (str (name k) "=" v)))
           ";\n\n"
           (if-let [[_ f] (the (fn [[cls _]]
                                 (instance? cls m))
                               dot-model-fns)]
             (f m)
             (errorf "No dotting function defined for %s." (class m)))
           "}"))))

(defn print-model
  "Prints a visualization of model `m` (a TGraph or EMFModel) to the file `f`.
  The file type is determined by its extension (dot, xdot, ps, svg, svgz, png,
  gif, pdf) and defaults to PDF.  The extension `gtk` has a special meaning: in
  that case, no file is actually printed, but instead a GTK+ window showing the
  model is created.

  Additional `opts` may be specified.  Those are usually DOT Graph
  Attributes (http://www.graphviz.org/content/attrs), e.g.,

    (print-model m \"test.pdf\" :ranksep 2.2)

  Additionally, the non-DOT :name option may be used to give a name to the
  model, which affecs the title of the generated PDF for example:

    (print-model m \"test.pdf\" :ranksep 2.2 :name \"MyModel\")

  The :name must be a valid DOT ID, e.g., it must not contain whitespaces.

  An :include and an :exclude option may be given, both being seqs of model
  elements to include/exclude from printing.  If :include is nil, everything is
  included.  :exclude overrides :include.  A note for TGraphs: :include
  and :exclude usually specify only vertices.  Edges are printed if their alpha
  and omega vertex are printed.  To forbid printing of certain edges where
  alpha/omega are printed, it is possible to add them to :exclude, though.

  Furthermore, a :mark option is supported.  It is a seq of elements that
  should be highlighted.  It may also be a predicate that gets an element and
  returns true/false.

  If the option :qualified-names is set to true, the element types will be
  printed as fully qualified names.  The default is false, where only simple
  class names are printed."
  [m f & opts]
  (let [ds (dot-model m opts)
        suffix (second (re-matches #".*\.([^.]+)$" f))
        ;; Fallback to pdf on unknown extensions.
        lang (get #{"dot" "xdot" "ps" "svg" "svgz" "png" "gif" "pdf" "eps" "gtk"}
                  suffix "pdf")]
    (if (= lang "gtk")
      (println "Showing model in a GTK+ window.")
      (println "Printing model to" f))
    (let [r (clojure.java.shell/sh "dot" (str "-T" lang) "-o" f :in ds)]
      (when-not (zero? (:exit r))
        (errorf "Dotting failed: %s" (:err r))))))
