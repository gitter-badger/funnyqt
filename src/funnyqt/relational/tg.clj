(ns funnyqt.relational.tg
  (:require [clojure.core.logic           :as ccl]
            [clojure.core.logic.protocols :as cclp]
            [funnyqt.tg                   :as tg]
            [funnyqt.generic              :as g]
            [funnyqt.query                :as q]
            [funnyqt.utils                :as u]
            [funnyqt.relational           :as rel]
            [funnyqt.relational.tmp-elem  :as tmp]
            [funnyqt.relational.util      :as ru]
            [funnyqt.relational.internal  :as ri]
            clojure.java.io)
  (:import
   (de.uni_koblenz.jgralab Graph Vertex Edge AttributedElement)
   (de.uni_koblenz.jgralab.schema AggregationKind Schema Domain RecordDomain
                                  AttributedElementClass NamedElement
                                  GraphClass VertexClass EdgeClass Attribute
                                  GraphElementClass IncidenceClass)
   (funnyqt.relational.tmp_elem WrapperElement)))

(defn kind-aec-tup-from-spec [g spec]
  (let [aecfn (fn [ts]
                (tg/attributed-element-class
                 g (second (u/type-with-modifiers (name ts)))))
        kindfn #(if (tg/vertex-class? %) :element :relationship)]
    (cond
     (symbol? spec) (let [aec (aecfn spec)]
                      [(kindfn aec) aec])
     (vector? spec) (let [aecs  (set (map aecfn spec))
                          kinds (set (map kindfn aecs))]
                      [kinds aecs])
     :else (u/errorf "Unknown spec %s." spec))))

(defn tmp-typeo [g e t]
  (fn [a]
    (let [ge (cclp/walk a e)
          gt (cclp/walk a t)]
      (cond
       (not (ru/ground? gt))
       (u/errorf "tmp-typeo: type must be ground.")

       (not (or (ru/fresh? ge)
                (tmp/tmp-or-wrapper-element? ge)))
       (u/errorf "tmp-typeo: e must be fresh or a ground Wrapper/TmpElement but was %s." ge)

       (ru/ground? ge)
       (let [[kind aec] (kind-aec-tup-from-spec g gt)]
         (if (and (tmp/set-type ge gt)
                  (tmp/set-kind ge kind))
           (ccl/succeed a)
           (ccl/fail a)))

       :else (let [[kind aec] (kind-aec-tup-from-spec g gt)
                   seqfn (cond
                          (= kind :element)        tg/vseq
                          (= kind :relationship)   tg/eseq
                          :else (fn [g gt]
                                  (concat (tg/vseq g gt) (tg/eseq g gt))))]
               (ccl/to-stream
                (->> (map #(ccl/unify a e %)
                          (concat
                           ;; Existing vertices/edges wrapped
                           (map (partial tmp/make-wrapper g e)
                                (seqfn g gt))
                           ;; One new vertex/edge tmp element
                           [(tmp/make-tmp-element g kind gt)]))
                     (remove not))))))))

(defn typeo
  "A relation where in graph `g`, vertex or edge `e` has the type `t`, a graph
  element class name.  In fact, `t` may be any type specification (see
  `funnyqt.generic/type-matcher`).  The graph `g` must be ground."
  [g e t]
  (if tmp/*make-tmp-elements*
    (tmp-typeo g e t)
    (fn [a]
      (let [ge (cclp/walk a e)
            gt (cclp/walk a t)]
        (cond
         (or (and (ru/ground? ge) (not (tg/attributed-element? ge)))
             (and (ru/ground? gt) (not (or (symbol? gt) (coll? gt)))))
         (ccl/fail a)

         (and (ru/ground? ge) (ru/ground? gt))
         (if (g/has-type? ge gt) (ccl/succeed a) (ccl/fail a))

         (ru/ground? ge)
         (ccl/unify a t (g/qname ge))

         (ru/ground? gt)
         (if (symbol? gt)
           ;; Ok, here we can determine if its a vertex or an edge class
           (let [[_ tn _] (u/type-with-modifiers (name gt))
                 aec      (tg/attributed-element-class g tn)]
             (if (tg/vertex-class? aec)
               (ccl/to-stream
                (->> (map #(ccl/unify a e %) (tg/vseq g gt))
                     (remove not)))
               (ccl/to-stream
                (->> (map #(ccl/unify a e %) (tg/eseq g gt))
                     (remove not)))))
           (ccl/to-stream
            (->> (map #(ccl/unify a e %)
                      (concat (tg/vseq g gt)
                              (tg/eseq g gt)))
                 (remove not))))

         :else (ccl/to-stream
                (->> (for [elem (concat (tg/vseq g) (tg/eseq g))]
                       (ccl/unify a [e t] [elem (g/qname elem)]))
                     (remove not))))))))

(defn tmp-vertexo [g v]
  (fn [a]
    (let [gv (cclp/walk a v)]
      (cond
       (not (or (ru/fresh? gv)
                (tmp/tmp-or-wrapper-element? gv)))
       (u/errorf "tmp-vertexo: v must be fresh or a ground Wrapper/TmpElement but was %s."
                 gv)

       (ru/ground? gv)
       (if (tmp/set-kind gv :element)
         (ccl/succeed a)
         (ccl/fail a))

       :else (ccl/to-stream
              (->> (map #(ccl/unify a v %)
                        (concat
                         ;; Existing vertices wrapped
                         (map (partial tmp/make-wrapper g v)
                              (tg/vseq g))
                         ;; One new vertex tmp element
                         [(tmp/make-tmp-element g :element)]))
                   (remove not)))))))

(defn vertexo
  "A relation where `v` is a vertex in graph `g`.
  `g` has to be ground."
  [g v]
  (if tmp/*make-tmp-elements*
    (tmp-vertexo g v)
    (fn [a]
      (let [gv (cclp/walk a v)]
        (if (ru/ground? gv)
          (if (and (tg/vertex? gv) (tg/contains-vertex? g gv))
            (ccl/succeed a)
            (ccl/fail a))
          (ccl/to-stream
           (->> (map #(ccl/unify a v %) (tg/vseq g))
                (remove not))))))))

(defn tmp-edgeo [g e alpha omega]
  (fn [a]
    (let [ge     (cclp/walk a e)
          galpha (cclp/walk a alpha)
          gomega (cclp/walk a omega)]
      #_(println (format "(tmp-edgeo g %s %s %s)" ge galpha gomega))
      (cond
       (not (or (ru/fresh? ge) (tmp/tmp-or-wrapper-element? ge)))
       (u/errorf "tmp-edgeo: e must be fresh or a ground Wrapper/TmpElement but was %s."
                 ge)

       (not (or (ru/fresh? galpha) (tmp/tmp-or-wrapper-element? galpha)))
       (u/errorf "tmp-edgeo: alpha must be fresh or a ground Wrapper/TmpElement but was %s."
                 galpha)

       (not (or (ru/fresh? gomega) (tmp/tmp-or-wrapper-element? gomega)))
       (u/errorf "tmp-edgeo: omega must be fresh or a ground Wrapper/TmpElement but was %s."
                 gomega)

       (tmp/wrapper-element? ge)
       (ccl/unify a [alpha omega]
                    (let [edge (.wrapped-element ^WrapperElement ge)]
                      [(tmp/make-wrapper g alpha (tg/alpha edge))
                       (tmp/make-wrapper g omega (tg/omega edge))]))

       (and (ru/fresh? ge) (tmp/wrapper-element? galpha) (tmp/wrapper-element? gomega))
       (ccl/to-stream
        (->> (map (fn [ed]
                    (ccl/unify a e ed))
                  (concat
                   (map (partial tmp/make-wrapper g e)
                        (filter
                         #(= (.wrapped-element ^WrapperElement gomega) (tg/omega %))
                         (tg/iseq (.wrapped-element ^WrapperElement galpha) nil :out)))
                   [(doto (tmp/make-tmp-element g :relationship)
                      (tmp/set-source alpha)
                      (tmp/set-target omega))]))
             (remove not)))

       (and (tmp/tmp-element? ge) (tmp/wrapper-element? galpha) (tmp/wrapper-element? gomega))
       (if (and (tmp/set-source ge alpha)
                (tmp/set-target ge omega))
         (ccl/succeed a)
         (ccl/fail a))

       (and (ru/fresh? ge) (tmp/wrapper-element? galpha))
       (ccl/to-stream
        (->> (map (fn [ed-om]
                    (ccl/unify a [e omega] ed-om))
                  (concat
                   (map (fn [ed]
                          [(tmp/make-wrapper g e ed)
                           (tmp/make-wrapper g omega (tg/omega ed))])
                        (tg/iseq (.wrapped-element ^WrapperElement galpha) nil :out))
                   [(let [ed (tmp/make-tmp-element g :relationship)]
                      (tmp/set-source ed alpha)
                      (tmp/set-target ed omega)
                      [ed gomega])]))
             (remove not)))

       (and (tmp/tmp-element? ge) (tmp/wrapper-element? galpha))
       (if (and (tmp/set-source ge alpha)
                (tmp/set-target ge omega))
         (ccl/succeed a)
         (ccl/fail a))

       (and (ru/fresh? ge) (tmp/wrapper-element? gomega))
       (ccl/to-stream
        (->> (map (fn [ed-al]
                    (ccl/unify a [e alpha] ed-al))
                  (concat
                   (map (fn [ed]
                          [(tmp/make-wrapper g e ed)
                           (tmp/make-wrapper g alpha (tg/alpha ed))])
                        (tg/iseq (.wrapped-element ^WrapperElement gomega) nil :in))
                   [(let [ed (tmp/make-tmp-element g :relationship)]
                      (tmp/set-source ed alpha)
                      (tmp/set-target ed omega)
                      [ed galpha])]))
             (remove not)))

       (and (tmp/tmp-element? ge) (tmp/wrapper-element? gomega))
       (if (and (tmp/set-source ge alpha)
                (tmp/set-target ge omega))
         (ccl/succeed a)
         (ccl/fail a))

       :else (u/errorf "Can't handle (tmp-edgeo %s %s %s %s)" g ge galpha gomega)))))

(defn edgeo
  "A relation where `e` is an edge in graph `g` from `alpha` to `omega`.
  `g` has to be ground."
  [g e alpha omega]
  (if tmp/*make-tmp-elements*
    (tmp-edgeo g e alpha omega)
    (fn [a]
      (let [ge     (cclp/walk a e)
            galpha (cclp/walk a alpha)
            gomega (cclp/walk a omega)]
        (cond
         (or (and (ru/ground? ge) (not (tg/edge? ge)))
             (and (ru/ground? galpha) (not (tg/vertex? galpha)))
             (and (ru/ground? gomega) (not (tg/vertex? gomega))))
         (ccl/fail a)

         (ru/ground? ge)
         (ccl/unify a [alpha omega] [(tg/alpha ge) (tg/omega ge)])

         (ru/ground? galpha)
         (ccl/to-stream
          (->> (map #(ccl/unify a [e omega] [% (tg/omega %)])
                    (tg/iseq galpha nil :out))
               (remove not)))

         (ru/ground? gomega)
         (ccl/to-stream
          (->> (map #(ccl/unify a [e alpha] [% (tg/alpha %)])
                    (tg/iseq gomega nil :in))
               (remove not)))

         :else (ccl/to-stream
                (->> (for [edge (tg/eseq g)]
                       (ccl/unify a [e alpha omega]
                                  [edge (tg/alpha edge) (tg/omega edge)]))
                     (remove not))))))))

(defn ^:private attribute-list
  "Gets the list of ccl/all attributes of ae's attributed element class."
  [ae]
  (seq (.getAttributeList (tg/attributed-element-class ae))))

(defn valueo
  "A relation where graph `g`s attributed element `ae` has value `val` for its
  `at` attribute.

  The parameter `may-override` specifies that the attribute value may be
  overridden when transforming into the direction of `g` in a bidirectional
  transformation.  (You normally shouldn't use that parameter directly, but use
  the API generated by `generate-schema-relations`.)"
  ([g ae at val]
     (valueo g ae at val false))
  ([g ae at val may-override]
     (if tmp/*make-tmp-elements*
       (ri/tmp-valueo g ae at val may-override)
       (fn [a]
         (let [gae  (cclp/walk a ae)
               gat  (cclp/walk a at)
               gval (cclp/walk a val)]
           (cond
            (or (and (ru/ground? gae) (not (tg/attributed-element? gae)))
                (and (ru/ground? gat) (not (keyword? gat)))
                (and (ru/ground? gae) (ru/ground? gat)
                     (not (.getAttribute (tg/attributed-element-class gae)
                                         (name gat)))))
            (ccl/fail a)

            (and (ru/ground? gae) (ru/ground? gat))
            (ccl/unify a val (tg/value gae gat))

            (ru/ground? gae)
            (ccl/to-stream
             (->> (for [^Attribute attr (attribute-list gae)
                        :let [an (keyword (.getName attr))]]
                    (ccl/unify a [at val] [an (tg/value gae an)]))
                  (remove not)))

            :else (ccl/to-stream
                   (->> (for [elem (concat (tg/vseq g) (tg/eseq g))
                              ^Attribute attr (attribute-list elem)
                              :let [an (keyword (.getName attr))]]
                          (ccl/unify a [ae at val] [elem an (tg/value elem an)]))
                        (remove not)))))))))

(defn adjo
  "A relation where vertex `rv` is in the `role` role of vertex `v` in graph
  `g`.

  The parameter `may-override` specifies that in case of a single-valued role,
  the referenced vertex may be overridden when transforming into the direction
  of `g` in a bidirectional transformation.  (You normally shouldn't use that
  parameter directly, but use the API generated by
  `generate-schema-relations`.)"
  ([g v role rv]
     (adjo g v role rv false))
  ([g v role rv may-override]
     (if tmp/*make-tmp-elements*
       (ri/tmp-adjo g v role rv may-override)
       (fn [a]
         (let [edge-class-roles (fn [^EdgeClass ec from-or-to]
                                  (remove empty? (.getAllRoles (if (= :to from-or-to)
                                                                 (.getTo ec)
                                                                 (.getFrom ec)))))
               gv    (cclp/walk a v)
               grole (cclp/walk a role)
               grv   (cclp/walk a rv)]
           (cond
            ;; Ground lvars must have the expected types
            (or (and (ru/ground? gv) (not (tg/vertex? gv)))
                (and (ru/ground? grole) (not (keyword? grole)))
                (and (ru/ground? grv)   (not (tg/vertex? grv))))
            (ccl/fail a)

            (and (ru/ground? gv) (ru/ground? grole))
            (ccl/to-stream
             (->> (for [refed (funnyqt.generic/adjs* gv grole)]
                    (ccl/unify a [rv] [refed]))
                  (remove not)))

            (ru/ground? gv)
            (ccl/to-stream
             (->> (for [e (tg/iseq gv)
                        rn (edge-class-roles (tg/attributed-element-class e)
                                             (if (tg/normal-edge? e) :to :from))
                        :when rn
                        :let [rn (keyword rn)]]
                    (ccl/unify a [role rv] [rn (tg/that e)]))
                  (remove not)))

            (ru/ground? grv)
            (ccl/to-stream
             (->> (for [e (tg/iseq grv)
                        rn (edge-class-roles (tg/attributed-element-class e)
                                             (if (tg/normal-edge? e) :from :to))
                        :when rn
                        :let [rn (keyword rn)]]
                    (ccl/unify a [v role] [(tg/that e) rn]))
                  (remove not)))

            :else (ccl/to-stream
                   (->> (for [s (tg/vseq g)
                              e (tg/iseq s)
                              rn (if (ru/ground? grole)
                                   [grole]
                                   (edge-class-roles (tg/attributed-element-class e)
                                                     (if (tg/normal-edge? e) :to :from)))
                              :when rn
                              :let [rn (keyword rn)]]
                          (ccl/unify a [v role rv] [(tg/this e) rn (tg/that e)]))
                        (remove not)))))))))

;;# Metamodel specific

(defn ^:private class->rel-symbols
  "Returns a relation symbol for the class `c`."
  [^AttributedElementClass c prefix]
  (let [n (.getUniqueName c)
        fqn (.getQualifiedName c)]
    (mapv (fn [s]
            (with-meta (symbol s)
              {:relation-name
               (symbol (str prefix (clojure.string/replace
                                    s #"([!])?.*[.]" #(or (nth % 1) ""))))}))
          [fqn (str fqn "!") (str "!" fqn) (str "!" fqn "!")])))

(defn ^:private create-vc-relations
  "Creates relations for the given vertex class."
  [vc prefix]
  `(do
     ~@(for [na (class->rel-symbols vc prefix)]
         `(defn ~(:relation-name (meta na))
            ~(format "A relation where `v` is a %s vertex of graph `g`." na)
            [~'g ~'v]
            (ccl/all
             (typeo ~'g ~'v '~na)
             (vertexo ~'g ~'v))))))

(defn ^:private create-ec-relations
  "Creates relations for the given edge class."
  [^EdgeClass ec prefix]
  `(do
     ~@(for [na (class->rel-symbols ec prefix)]
         `(defn ~(:relation-name (meta na))
            ~(format "A relation where `e` is a %s edge from `al` to `om` in graph `g`." na)
            [~'g ~'e ~'al ~'om]
            (ccl/all
             (typeo ~'g ~'e '~na)
             (edgeo ~'g ~'e ~'al ~'om))))))

(defn ^:private create-attr-relations
  "Creates relations for the given attribute."
  [attr aecs prefix]
  ;; attr is an attr name keyword, aecs the set of classes having
  ;; such an attr
  (let [ts (mapv #(g/qname %) aecs)]
    `(do
       (defn ~(symbol (str prefix (clojure.string/replace (name attr) "_" "-")))
         ~(format "A relation where `ae` has value `val` for its %s attribute in graph `g`." attr)
         [~'g ~'ae ~'val]
         (ccl/all
          (typeo ~'g ~'ae '~ts)
          (valueo ~'g ~'ae ~attr ~'val false)))
       (defn ~(symbol (str prefix (clojure.string/replace (name attr) "_" "-") "*"))
         ~(format "A relation where `ae` has value `val` for its %s attribute in graph `g`.
  When used in a bidirectional transformation executed in the direction of `g`
  the attribute value may be overridden." attr)
         [~'g ~'ae ~'val]
         (ccl/all
          (typeo ~'g ~'ae '~ts)
          (valueo ~'g ~'ae ~attr ~'val true))))))

(defn ^:private create-reference-relations
  "Creates a relation for the given role name."
  [role vcs prefix]
  (let [ts (mapv #(g/qname %) vcs)]
    `(do
       (defn ~(symbol (str prefix "->" (clojure.string/replace (name role) "_" "-")))
         ~(format "A relation where `sv` references `tv` in its `%s` role." (name role))
         [~'g ~'sv ~'tv]
         (typeo ~'g ~'sv '~ts)
         (adjo ~'g ~'sv ~role ~'tv false))
       ~(when (some #(not (g/mm-multi-valued-property? % role)) vcs)
          `(defn ~(symbol (str prefix "->" (clojure.string/replace (name role) "_" "-") "*"))
             ~(format "A relation where `sv` references `tv` in its `%s` role.
  When used in a bidirectional transformation executed in the direction of `g`
  the vertex in that single-valued role may be overridden." (name role))
             [~'g ~'sv ~'tv]
             (typeo ~'g ~'sv '~ts)
             (adjo ~'g ~'sv ~role ~'tv true))))))

(defmacro generate-schema-relations
  "Generates schema-specific relations in the namespace denoted by `nssym`.
  `schema-file` is the TG file with the schema.

  If `nssym` is nil (or not given), generate them in the current namespace.
  If `nssym` was given, require that namespace as `alias`."
  ([schema-file]
     `(generate-schema-relations ~schema-file nil nil nil))
  ([schema-file nssym]
     `(generate-schema-relations ~schema-file ~nssym nil nil))
  ([schema-file nssym alias]
     `(generate-schema-relations ~schema-file ~nssym ~alias nil))
  ([schema-file nssym alias prefix]
     `(g/metamodel-api-generator ~schema-file
                                 ~nssym
                                 ~alias
                                 ~prefix
                                 create-vc-relations
                                 create-ec-relations
                                 create-attr-relations
                                 create-reference-relations)))
