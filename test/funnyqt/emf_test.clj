(ns funnyqt.emf-test
  (:refer-clojure :exclude [parents])
  (:require [clojure.test :refer :all]
            [funnyqt
             [emf :refer :all]
             [generic :as g]
             [query :as q]])
  (:import org.eclipse.emf.common.util.EList))

(deftest test-load-ecore-resource
  (let [mm (load-ecore-resource "test/input/Families.ecore")]
    (is (instance? org.eclipse.emf.ecore.resource.Resource mm))
    ;; Restricting to our custom one by its nsURI...
    (with-ns-uris ["http://families/1.0"]
      (is (== 1 (count (epackages)))))))

(load-ecore-resource "test/input/Families.ecore")
(def family-model (load-resource "test/input/example.families"))

(deftest test-eresource
  (doseq [eo (eallcontents family-model)]
    (is (= family-model (eresource eo)))))

(deftest test-eclassifiers
  (with-ns-uris ["http://families/1.0"]
    (is (== 3 (count (eclassifiers))))))

(deftest test-eclassifier
  (let [fmodel (eclassifier 'FamilyModel)
        family (eclassifier 'Family)
        person (eclassifier 'Member)]
    (is fmodel)
    (is family)
    (is person)))

(defn ^:private make-uniqueelist
  []
  (let [ul (org.eclipse.emf.common.util.UniqueEList.)]
    (doseq [i [0 1 2 3 4 1 5 6 7 7 3 2 8 1 0 0 9 0]]
      (.add ul i))
    ul))

(defn ^:private make-elist
  []
  (let [el (org.eclipse.emf.common.util.BasicEList.)]
      (doseq [item [0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9]]
        (.add el item))
      el))

(defn ^:private make-emap
  []
  (let [em (org.eclipse.emf.common.util.BasicEMap.)]
    (doseq [[k v] [[:a "a"] [:b "b"] [:c "c"] [:d "d"]]]
      (.put em k v))
    em))

(deftest test-econtents-eallcontents
  (let [all   (eallcontents family-model)
        mems  (eallcontents family-model 'Member)
        fams  (eallcontents family-model 'Family)
        fmods (eallcontents family-model 'FamilyModel)]
    (is (== 17 (count all)))
    (is (== 1  (count fmods)))
    (is (== 3  (count fams)))
    (is (== 13 (count mems)))
    ;; The FamilyModel is the container of all Members and Families.
    (doseq [x (concat mems fams)]
      (is (= (q/the fmods) (econtainer x))))
    ;; In this concrete case, this is true
    (is (= (eallcontents family-model '!FamilyModel)
           (econtents (econtents family-model))
           (mapcat g/contents (econtents family-model))))
    (is (= (eallcontents family-model 'FamilyModel)
           (econtents family-model    'FamilyModel)))
    (is (= (eallcontents family-model 'Member)
           (econtents (econtents family-model)   'Member)
           (mapcat #(g/contents % 'Member)
                   (econtents family-model))))
    (is (= (eallcontents family-model 'Family)
           (econtents (econtents family-model) 'Family)
           (mapcat #(g/contents % 'Family)
                   (econtents family-model))))))

(deftest test-ecrossrefs
  (let [fsmith (first (eallcontents family-model 'Family))]
    (is (= (ecrossrefs fsmith)
           (ecrossrefs fsmith [:father :mother :sons :daughters])))
    (is (== 1
            (count (ecrossrefs fsmith :father))
            (count (ecrossrefs fsmith :mother))
            (count (ecrossrefs fsmith :daughters))))
    (is (== 3 (count (ecrossrefs fsmith :sons))))))

(deftest test-econtentrefs
  (let [fm (first (eallcontents family-model 'FamilyModel))]
    (is (= (eallcontents fm)
           (econtentrefs fm)))
    (is (= (eallcontents fm 'Member)
           (econtentrefs fm :members)))
    (is (= (eallcontents fm 'Family)
           (econtentrefs fm :families)))))

(deftest test-inv-erefs
  (let [[f1 f2 f3] (eallcontents family-model 'Family)]
    (are [x y z cnt] (and (== cnt (count x) (count y) (count z))
                          (= (apply hash-set x)
                             (apply hash-set y)
                             (apply hash-set z)))
         ;; 7, cause the FamilyModel is also included
         (erefs f1)
         (inv-erefs f1)
         (inv-erefs f1 nil family-model)
         7
         ;; Here, it's not included (cross-refs only)
         (ecrossrefs f1)
         (inv-ecrossrefs f1)
         (inv-ecrossrefs f1 nil family-model)
         6
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (erefs f1 :father)
         (inv-erefs f1 :familyFather)
         (inv-erefs f1 :familyFather family-model)
         1
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (ecrossrefs f1 :father)
         (inv-ecrossrefs f1 :familyFather)
         (inv-ecrossrefs f1 :familyFather family-model)
         1
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (erefs f1 [:mother :father])
         (inv-erefs f1 [:familyMother :familyFather])
         (inv-erefs f1 [:familyMother :familyFather] family-model)
         2
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (ecrossrefs f1 [:mother :father])
         (inv-ecrossrefs f1 [:familyMother :familyFather])
         (inv-ecrossrefs f1 [:familyMother :familyFather] family-model)
         2
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (erefs f2 :father)
         (inv-erefs f2 :familyFather)
         (inv-erefs f2 :familyFather family-model)
         1
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (ecrossrefs f2 :father)
         (inv-ecrossrefs f2 :familyFather)
         (inv-ecrossrefs f2 :familyFather family-model)
         1
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (erefs f3 :sons)
         (inv-erefs f3 :familySon)
         (inv-erefs f3 :familySon family-model)
         0
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (ecrossrefs f3 :sons)
         (inv-ecrossrefs f3 :familySon)
         (inv-ecrossrefs f3 :familySon family-model)
         0
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (erefs f3 [:daughters])
         (inv-erefs f3 :familyDaughter)
         (inv-erefs f3 :familyDaughter family-model)
         3
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (ecrossrefs f3 [:daughters])
         (inv-ecrossrefs f3 :familyDaughter)
         (inv-ecrossrefs f3 :familyDaughter family-model)
         3)))

(deftest test-epairs
  (is (== 31 (count (epairs family-model))))
  (is (== 15 (count (ecrosspairs family-model))))
  (is (== 16 (count (econtentpairs family-model))))

  (is (== 16 (count (epairs family-model :model nil))))
  (is (==  3 (count (epairs family-model nil :families))))
  (is (==  3 (count (epairs family-model :model :families))))

  (is (==  3 (count (epairs family-model nil nil 'FamilyModel 'Family))))
  (is (== 18 (count (epairs family-model nil nil nil 'Family))))
  (is (==  3 (count (epairs family-model :model nil nil 'Family)))))

(deftest test-eget
  (let [fm (q/the (econtents family-model))
        fsmith (first (econtents fm 'Family))]
    (is (= (econtents fm)
           (concat (eget fm :families)
                   (eget fm :members))))
    (is (= (econtents fm 'Family)
           (eget fm :families)))
    (is (= (econtents fm 'Member)
           (eget fm :members)))))

(deftest test-erefs-and-ecrossrefs
  (let [fm (q/the (econtents family-model))
        fsmith (first (econtents fm 'Family))]
    (are [x] (= (eget fm x) (erefs fm x))
         :families
         :members)
    (are [x] (= (let [r (eget fsmith x)]
                  (if (coll? r) r #{r}))
                (erefs fsmith x)
                (ecrossrefs fsmith x))
         :father
         :mother
         :sons
         :daughters)
    ;; Those are all crossrefs, so erefs and ecrossrefs should equal
    (are [x] (= (erefs fsmith x) (ecrossrefs fsmith x))
         :father
         :mother
         :sons
         :daughters
         [:father :mother]
         [:sons :daughters]
         [:father :sons]
         [:mother :daughters])))

(defn make-test-familymodel
  "Creates a more or less random FamilyModel with `fnum` families and `mnum`
  members.  The references (father, mother, sons, daughters) are set randomly."
  [fnum mnum]
  (let [m (new-resource) ;; Also test the generic creation function...
        fm (g/create-element! m 'FamilyModel)
        make-family (fn [i]
                      (ecreate! nil 'Family
                                {:lastName (str "Family" i)
                                 :street   (str "Some Street " i)
                                 :town     (str i " Sometown")}))
        make-member (fn [i]
                      (ecreate! nil 'Member
                                {:firstName (str "Member" i)
                                 :age       (Integer/valueOf ^Long (mod i 80))}))
        random-free-member (fn [mems ref]
                             (loop [m (rand-nth mems)]
                               (if (eget m ref)
                                 (recur (rand-nth mems))
                                 m)))
        random-members (fn [mems]
                         (loop [r #{}, i (rand-int 7)]
                           (if (pos? i)
                             (recur (conj r (rand-nth mems)) (dec i))
                             r)))]
    (eset! fm :families
           (loop [fams [], i 1]
             (if (<= i fnum)
               (recur (conj fams (make-family i)) (inc i))
               fams)))
    (eset! fm :members
           (loop [mems [], i 1]
             (if (<= i mnum)
               (recur (conj mems (make-member i)) (inc i))
               mems)))
    (let [mems (vec (eget fm :members))]
      (loop [fams (eget fm :families), r []]
        (when (seq fams)
          (let [fam (first fams)]
            (eset! fam :father    (random-free-member mems :familyFather))
            (eset! fam :mother    (random-free-member mems :familyMother))
            (eset! fam :sons      (random-members mems))
            ;; Also try the generic version...
            (g/set-adjs! fam :daughters (random-members mems))
            (recur (rest fams) (conj r fam))))))
    m))

(deftest test-ecreate
  (let [m (make-test-familymodel 100 1000)]
    (are [c s] (== c (count (eallcontents m s)))
         1101 nil
         1    'FamilyModel
         100  'Family
         1000 'Member
         1100 '[Family Member])
    ;; Every family has its father/mother refs set
    (is (q/forall? (fn [f]
                     (and (eget f :father)
                          (eget f :mother)))
                   (eallcontents m 'Family)))))

(deftest test-eget-raw
  (let [i 1000
        fm (ecreate! nil 'FamilyModel)
        ^EList ms (eget-raw fm :members)]
    (print "Adding" i "Members (raw): \t")
    (time (dotimes [_ i]
            (.add ms (ecreate! nil 'Member))))
    (is (== i (count (econtents fm 'Member))))
    (print "Adding" i "Members (eset!): \t")
    (time (eset! fm :members (loop [ims (eget fm :members), x i]
                               (if (pos? x)
                                 (recur (conj ims (ecreate! nil 'Member)) (dec x))
                                 ims))))
    (is (== (* 2 i) (count (econtents fm 'Member))))))

(deftest test-stressy-add-remove
  (let [fm (new-resource)
        root (ecreate! fm 'FamilyModel)
        f   (ecreate! nil 'Member)
        fam (ecreate! nil 'Family)
        s   (ecreate! nil 'Member)]
    (eadd! root :members f s)
    (eadd! root :families fam)
    (is (== 3 (count (epairs fm))))
    (is (== 3 (count (econtentpairs fm))))
    (is (zero? (count (ecrosspairs fm))))
    ;; the refs are unique, so in fact only one new link is established.
    (dotimes [i 1000]
      (eadd! fam :sons s))
    (is (== 4 (count (epairs fm))))
    (is (== 3 (count (econtentpairs fm))))
    (is (== 1 (count (ecrosspairs fm))))))

(deftest test-create-element!
  (let [fm (new-resource)
        root (ecreate! fm 'FamilyModel)
        f    (g/create-element! fm 'Family {:lastName "Doe"
                                            :model root})
        m    (g/create-element! fm 'Member {:firstName "John"
                                            :model root
                                            :familyFather f})]
    (is (= 3 (count (eallcontents fm))))
    (is (= 1
           (count (eallcontents fm 'FamilyModel))
           (count (eallcontents fm 'Family))
           (count (eallcontents fm 'Member))))
    (is (= root (eget f :model) (eget m :model)))
    (is (= m (eget f :father)))
    (is (= f (eget m :familyFather)))))

;;# Test the generated api

(generate-ecore-model-functions "test/input/Families.ecore"
                                test.functional.families.emf
                                fams)

(g/generate-metamodel-functions "test/input/Families.ecore"
                                test.functional.families.generic-emf
                                gfams)

(deftest test-generated-api
  (let [m (new-resource)
        root (gfams/create-FamilyModel! m)
        fam  (fams/create-Family! nil)
        m1   (fams/create-Member! nil {:firstName "Ben"})
        m2   (fams/create-Member! nil)]
    (is (= "Ben"
           (eget m1 :firstName)
           (fams/firstName m1)
           (gfams/firstName m1)))

    (is (and
         (fams/isa-Member? m1)
         (fams/isa-Member? m2)
         (fams/isa-Family? fam)
         (fams/isa-FamilyModel? root)
         (gfams/isa-Member? m1)
         (gfams/isa-Member? m2)
         (gfams/isa-Family? fam)
         (gfams/isa-FamilyModel? root)))

    (is (= false
           (fams/isa-Member? fam)
           (fams/isa-Family? root)
           (fams/isa-FamilyModel? m1)
           (gfams/isa-Member? fam)
           (gfams/isa-Family? root)
           (gfams/isa-FamilyModel? m1)))

    (fams/set-firstName! m1 "Bob")
    (is (= "Bob"
           (eget m1 :firstName)
           (fams/firstName m1)
           (gfams/firstName m1)))

    (gfams/set-firstName! m1 "Bobby")
    (is (= "Bobby"
           (eget m1 :firstName)
           (fams/firstName m1)
           (gfams/firstName m1)))

    (fams/->add-sons! fam m1 m2)
    (is (= [m1 m2]
           (eget fam :sons)
           (fams/->sons fam)
           (gfams/->sons fam)))

    (fams/->set-sons! fam [m2 m1])
    (is (= [m2 m1]
           (eget fam :sons)
           (fams/->sons fam)
           (gfams/->sons fam)))

    (fams/->set-sons! fam [m1 m2])
    (is (= [m1 m2]
           (eget fam :sons)
           (fams/->sons fam)
           (gfams/->sons fam)))

    (fams/->add-families! root fam)
    (gfams/->addall-members! root [m1 m2])
    (is (= [m1 m2]
           (eget root :members)
           (fams/->members root)
           (gfams/->members root)))

    (is (= (eallcontents m 'FamilyModel)
           (fams/all-FamilyModels m)
           (gfams/all-FamilyModels m)))
    (is (= (eallcontents m 'Member)
           (fams/all-Members m)
           (gfams/all-Members m)))
    (is (= (eallcontents m 'Family)
           (fams/all-Families m)
           (gfams/all-Families m)))))

(load-ecore-resource "test/input/AddressBook.ecore")

(deftest test-super-subclass-fns
  (with-ns-uris #{"http://addressbook/1.0"}
    (let [named   (eclassifier 'NamedElement)
          entry   (eclassifier 'Entry)
          ab      (eclassifier 'AddressBook)
          cat     (eclassifier 'Category)
          contact (eclassifier 'Contact)
          org     (eclassifier 'Organization)]
      (is (= #{cat ab org}
             (esubclasses named)
             (eallsubclasses named)))
      (is (= #{org contact}
             (esubclasses entry)
             (eallsubclasses entry)))
      (is (= #{}
             (esuperclasses entry)
             (eallsuperclasses entry)))
      (is (= #{named entry}
             (esuperclasses org)
             (eallsuperclasses org)))
      (is (= #{entry}
             (esuperclasses contact)
             (eallsuperclasses contact))))))

(deftest test-ns-uri-restrictions
  (is (thrown-with-msg? Exception #"No such EPackage nsURI"
                        (eclassifier {"invalid uri" 'Foo})))
  (is (thrown-with-msg? Exception #"No such EPackage nsURI"
                        (with-ns-uris ["invalid uri"]
                          (eclassifier 'Foo))))
  (is (thrown-with-msg? Exception #"No such EClassifier"
                        (eclassifier {"http://addressbook/1.0" 'Member})))
  (is (thrown-with-msg? Exception #"No such EClassifier"
                        (with-ns-uris ["http://addressbook/1.0"]
                          (eclassifier 'Member))))
  (is (= (eclassifier 'Member)
         (with-ns-uris ["http://families/1.0"]
           (eclassifier 'Member))
         (eclassifier {"http://families/1.0" 'Member})))
  (is (thrown-with-msg? Exception #"No such EClassifier"
                        (eclassifier {"http://addressbook/1.0" 'Member})))
  (is (thrown-with-msg? Exception #"No such EClassifier"
                        (with-ns-uris ["http://addressbook/1.0"]
                          (eclassifier 'Member))))
  (is (= (eallcontents family-model '[Member Family])
         (eallcontents family-model
                       '{"http://addressbook/1.0"
                         [:or {"http://families/1.0" Member}
                          {"http://families/1.0" Family}]})))
  (is (thrown-with-msg? Exception #"No such EClassifier"
                        (eallcontents family-model
                                      '{"http://addressbook/1.0"
                                        [:or {"http://families/1.0" Member}
                                         Family]})))
  (is (= (eclassifier 'AddressBook)
         (eclassifier {"http://addressbook/1.0" 'AddressBook})
         (with-ns-uris ["http://addressbook/1.0"]
           (eclassifier 'AddressBook))))
  (is (= (eallcontents family-model '[Family Member])
         (eallcontents family-model '[Family Member])
         (eallcontents family-model '{"http://families/1.0" [Family Member]})))
  (is (= (eallcontents family-model 'Member)
         (eallcontents family-model 'Member)
         (eallcontents family-model '{"http://families/1.0" Member}))))

(load-ecore-resource "test/input/Genealogy.ecore")

(deftest test-eenum-constant
  (is (instance? org.eclipse.emf.ecore.EEnumLiteral (eenum-literal 'AgeGroup.ADULT)))
  (is (instance? org.eclipse.emf.ecore.EEnumLiteral (eenum-literal 'AgeGroup.CHILD)))
  (is (= (eenum-literal 'AgeGroup.CHILD)
         (funnyqt.generic/enum-constant nil 'AgeGroup.CHILD)))
  (is (= (eenum-literal 'AgeGroup.ADULT)
         (funnyqt.generic/enum-constant nil 'AgeGroup.ADULT))))

(deftest test-adj
  (is (every? #(= %
                  (g/adj % :father :familyFather)
                  (g/adj % :mother :familyMother)
                  (q/the (g/adjs % :father :familyFather))
                  (q/the (g/adjs % :mother :familyMother))
                  (eget (eget % :father) :familyFather)
                  (eget (eget % :mother) :familyMother))
              (eallcontents family-model 'Family)))
  ;; Multivalued refs should throw
  (is (thrown? Exception (g/adj (first (econtents family-model)) :members))))

(deftest test-copy-model-and-equal-models?
  (let [c (g/copy-model family-model)]
    (is (= (count (eallcontents family-model))
           (count (eallcontents c))))
    (is (= (count (epairs family-model))
           (count (epairs c))))
    (is (g/equal-models? family-model c))
    (let [fs (q/the #(= (eget % :street) "Smith Avenue 4")
                    (eallcontents c 'Family))
          ^EList l (eget-raw fs :sons)]
      (.move l 0 (int (dec (.size l))))
      ;; Now the models should be equal when comparing without respect to link
      ;; order, but with respect to link order they must not be equal.
      (is (g/equal-models? family-model c false))
      (is (not (g/equal-models? family-model c true))))
    ;; Now change something
    (eset! (first (eallcontents c 'Member)) :firstName "Foobar")
    (is (not (g/equal-models? family-model c)))))

(deftest test-equal-models?
  (let [m1 (doto (new-resource)
             (ecreate! 'Member {:firstName "X"})
             (ecreate! 'Member {:firstName "Y"})
             (ecreate! 'Member {:firstName "Z"}))
        m2 (doto (new-resource)
             (ecreate! 'Member {:firstName "X"})
             (ecreate! 'Member {:firstName "X"})
             (ecreate! 'Member {:firstName "X"}))
        m3 (doto (new-resource)
             (ecreate! 'Member {:firstName "X"})
             (ecreate! 'Member {:firstName "X"})
             (ecreate! 'Member {:firstName "X"}))]
    (is (not (g/equal-models? m1 m2)))
    (is (not (g/equal-models? m2 m1)))
    (is (g/equal-models? m2 m3))
    (is (g/equal-models? m3 m2))))
