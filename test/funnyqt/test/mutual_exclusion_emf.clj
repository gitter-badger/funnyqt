(ns funnyqt.test.mutual-exclusion-emf
  (:use funnyqt.emf.core)
  (:use funnyqt.generic-protocols)
  (:use funnyqt.generic)
  (:use funnyqt.match-replace)
  (:use funnyqt.emf.query)
  (:use clojure.test))

;;* Rules

;;** Short Transformation Sequence

(defrule new-rule
  "Matches 2 connected processes and injects a new process in between."
  [sys] [p1 (econtents sys 'Process)
         :let [p2 (eget p1 :next)]]
  (let [p (ecreate! 'Process)]
    (eadd! sys :processes p)
    (eset! p1 :next p)
    (eset! p :next p2)))

(defrule kill-rule
  "Matches a sequence of 3 connected processes and deletes the middle one."
  [sys] [p1 (econtents sys 'Process)
         :let [p2 (eget p1 :next)
               p3 (eget p2 :next)]]
  (delete! p2)
  (eset! p1 :next p3))

(defrule mount-rule
  "Matches a process and creates and assigns a resource to it."
  [sys] [p (econtents sys 'Process)]
  (let [r (ecreate! 'Resource)]
    (eadd! sys :resources r)
    (eset! r :taker p)))

(defrule unmount-rule
  "Matches a resource assigned to a process and deletes it."
  [sys] [r (econtents sys 'Resource)
         :let [t (eget r :taker)]]
  (delete! r))

(defrule pass-rule
  "Passes the token to the next process if the current doesn't request it."
  [sys] [r  (econtents sys 'Resource)
         p1 (eget r :taker)
         :when (empty? (filter #(= % r)
                               (eget p1 :requested)))
         p2 (eget p1 :next)]
  (eset! r :taker p2))

(defrule request-rule
  "Matches a process that doesn't request any resource and a resource not held
  by that process and makes the process request that resource."
  [sys] [p (econtents sys 'Process)
         :when (empty? (eget p :requested))
         r (econtents sys 'Resource)
         :when (not= p (eget r :holder))]
  (eadd! p :requested r))

(defrule take-rule
  "Matches a process that requests a resource that in turn tokens the process
  and makes the process hold that resource."
  ([sys] [r  (econtents sys 'Resource)
          :let [p (eget r :taker)]
          :when (member? p (eget r :requester))]
     (take-rule sys r p))
  ([sys r p]
     (eset! r :taker nil)
     (eremove! r :requester p)
     (eset! r :holder p)))

(defrule release-rule
  "Matches a resource held by a process and not requesting more resources, and
  releases that resource."
  ([sys] [r  (econtents sys 'Resource)
          :let [p (eget r :holder)]]
     (release-rule sys r p))
  ([sys r p]
     (when (empty? (eget p :requested))
       (eset! r :holder nil)
       (eset! r :releaser p)
       [r p])))

(defrule give-rule
  "Matches a process releasing a resource, and gives the token to that resource
  to the next process."
  ([sys] [p1  (econtents sys 'Process)
          r   (eget p1 :released)
          :let [p2 (eget p1 :next)]]
     (give-rule sys r p2))
  ([sys r p2]
     (eset! r :releaser nil)
     (eset! r :taker p2)
     [r p2]))

(defrule blocked-rule
  "Matches a process requesting a resource held by some other process, and
  creates a blocked edge."
  [sys] [r   (econtents sys 'Resource)
         p1  (eget r :requester)
         :let [p2 (eget r :holder)]]
  (eset! r :blocked p1))

(defrule waiting-rule
  "Moves the blocked state."
  [sys] [r1  (econtents sys 'Resource)
         p2  (eget r1 :requester)
         :let [p1 (eget r1 :holder)]
         :when (not= p1 p2)
         r2  (eget p1 :blocked_by)]
  (eadd! p2 :blocked_by r2)
  (eremove! p1 :blocked_by r2)
  [r1])

(defrule ignore-rule
  "Removes the blocked state if nothing is held anymore."
  [sys] [p (econtents sys 'Process)
         :when (empty? (eget p :held))]
  (eset! p :blocked_by nil))

(defrule unlock-rule
  "Matches a process holding and blocking a resource and releases it."
  [sys] [r  (econtents sys 'Resource)
         :let [p (eget r :holder)]
         :when (member? p (eget r :blocked))]
  (eset! r :holder nil)
  (eremove! r :blocked p)
  (eset! r :releaser p))

(defn apply-mutual-exclusion-sts
  [m n param-pass]
  (let [sys (the (econtents m 'System))]
    ;; n-2 times new-rule ==> n processes in a ring
    (dotimes [_ (- n 2)]
      (new-rule sys))
    ;; mount a resource and give token to one process
    (mount-rule sys)
    ;; Let all processe issue a request to the single resource
    (dotimes [_ n]
      (request-rule sys))
    ;; Handle the requests...
    (if param-pass
      (iteratively #(apply give-rule sys (apply release-rule sys (take-rule sys))))
      (iteratively #(do
                      (take-rule sys)
                      (release-rule sys)
                      (give-rule sys))))))

(defn g-sts
  "Returns an initial graph for the STS.
  Two Processes connected in a ring by two Next edges."
  []
  (let [m (new-model)
        s  (ecreate! m 'System)
        p1 (ecreate! 'Process)
        p2 (ecreate! 'Process)]
    (eset! s :processes [p1 p2])
    (eset! p1 :next p2)
    (eset! p2 :next p1)
    m))


;;** Long Transformation Sequence

(comment
(defrule request-star-rule
  "Matches a process and its successor that hold two different resources, and
  makes the successor request its predecessor's resource."
  [sys] [r1 (econtents sys 'Resource)
       h1 (iseq r1 'HeldBy :out)
       :let [p1 (omega h1)]
       n  (iseq p1 'Next :in)
       :let [p2 (alpha n)]
       h2 (iseq p2 'HeldBy :in)
       :let [r2 (alpha h2)]
       :when (not= r1 r2)
       :when (empty? (filter #(= p1 (alpha %))
                             (iseq r2 'Request :in)))]
  (create-edge! 'Request p1 r2))

(defrule release-star-rule
  "Matches a process holding 2 resources where one is requested by another
  process, and releases the requested one."
  ([sys] [p2 (econtents sys 'Process)
        h2 (iseq p2 'HeldBy :in)
        :let [r2 (alpha h2)]
        h1 (iseq p2 'HeldBy :in)
        :let [r1 (alpha h1)]
        rq (iseq r1 'Request :in)
        :let [p1 (alpha rq)]
        :when (and (not= r1 r2) (not= p1 p2))]
     (release-star-rule sys r2 h2 p2 h1 r1 rq p1))
  ([sys r2 h2 p2] [h1 (iseq p2 'HeldBy :in)
                 :let [r1 (alpha h1)]
                 rq (iseq r1 'Request :in)
                 :let [p1 (alpha rq)]
                 :when (and (not= r1 r2) (not= p1 p2))]
     (release-star-rule sys r2 h2 p2 h1 r1 rq p1))
  ([sys r2 h2 p2 h1 r1 rq p1]
     (delete! h1)
     (create-edge! 'Release r1 p2)))

(defn apply-mutual-exclusion-lts
  [sys n param-pass]
  (dotimes [_ n]
    (request-star-rule sys))
  (blocked-rule sys)
  (dotimes [_ (dec n)]
    (waiting-rule sys))
  (unlock-rule sys)
  (blocked-rule sys)
  (if param-pass
    (iteratively* waiting-rule sys)
    (iteratively #(waiting-rule sys)))
  (ignore-rule sys)
  (if param-pass
    (iteratively #(apply release-star-rule % (apply take-rule % (give-rule %))) sys)
    (iteratively #(do (give-rule sys) (take-rule sys) (release-star-rule sys))))
  (give-rule sys)
  (take-rule sys))

(defn g-lts
  "Returns an initial graph for the LTS.
  n processes and n resources.
  n Next edges organize the processes in a token ring.
  n HeldBy edges assign to each process a resource."
  [n]
  (let [sys (create-graph (load-schema "test/input/mutual-exclusion-schema.tg")
                        (str "Long transformation sequence, N =" n))]
    (loop [i n, lp nil]
      (if (pos? i)
        (let [r (ecreate! sys 'Resource)
              p (ecreate! sys 'Process)]
          (when lp
            (create-edge! 'Next lp p))
          (create-edge! 'HeldBy r p)
          (recur (dec i) p))
        (create-edge! 'Next lp (first (econtents sys 'Process)))))
    sys))
)

;;* Tests

(load-metamodel "test/input/MutualExclusion.ecore")

(deftest mutual-exclusion-sts
  (println)
  (println "Mutual Exclusion STS")
  (println "====================")
  (doseq [n [5, 100, 1000]]
    (let [g1 (g-sts)
          g2 (g-sts)]
      (println "N =" n)
      (print "  without parameter passing:\t")
      (time (apply-mutual-exclusion-sts g1 n false))
      (is (= (+ 2 n) (count (eallobjects g1))))

      (print "  with parameter passing:\t")
      (time (apply-mutual-exclusion-sts g2 n true))
      (is (= (+ 2 n) (count (eallobjects g2)))))))

(comment
(deftest mutual-exclusion-lts
  (println)
  (println "Mutual Exclusion LTS")
  (println "====================")
  (doseq [[n r] [[4, 100] [30, 27] [500, 1]]]
    (let [g1 (g-lts n)
          vc (vcount g1)
          ec (ecount g1)
          g2 (g-lts n)]
      (println "N =" n ", R =" r)
      (print "  without parameter passing:\t")
      (time (dotimes [_ r] (apply-mutual-exclusion-lts g1 n false)))
      (is (= vc (vcount g1)))
      (is (= ec (ecount g1)))

      (print "  with parameter passing:\t")
      (time (dotimes [_ r] (apply-mutual-exclusion-lts g2 n true)))
      (is (= vc (vcount g2)))
      (is (= ec (ecount g2))))))
)

