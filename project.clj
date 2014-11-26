(defproject funnyqt "0.32.6"
  :description "A model querying and transformation library for TGraphs and EMF
  models developed as part of Tassilo Horn's dissertation studies.

  Everything's totally pre-pre-pre-alpha and subject to frequent, incompatible
  changes.  You've been warned, but have fun anyway.

  You might also want to check the <a href=\"uberdoc.html\">code formatted
  nicely along the docs in a literate programming style</a> generated by Fogus'
  excellent Marginalia tool."
  :dependencies [[org.clojure/clojure "1.7.0-alpha4"]
                 [org.clojure/core.cache "0.6.4"]
                 [de.uni-koblenz.ist/jgralab "7.7.21"]
                 [org.clojure/core.logic "0.8.9"]
                 [org.flatland/ordered "1.5.2"]
                 [org.clojure/tools.macro "0.1.5"]
                 [emf-xsd-sdk "2.10.1"]
                 [inflections "0.9.13" :exclusions [org.clojure/clojure]]]
  :profiles {:dev
             {:dependencies
              [[criterium "0.4.3"]
               [org.clojure/tools.namespace "0.2.7"]]}}
  ;; Don't put version control dirs into the jar
  :jar-exclusions [#"(?:^|/).(svn|hg|git)/"]
  :resource-paths ["resources"]
  :global-vars {*warn-on-reflection* true}
  :jvm-opts ^:replace ["-Xmx1G"]
  :license {:name "GNU General Public License, Version 3 (or later)"
            :url "http://www.gnu.org/licenses/gpl.html"
            :distribution :repo}
  :url "https://github.com/jgralab/funnyqt"
  :repl-options {:init (println "Welcome to FunnyQT!")}
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; Stuff specific to generating API docs
  :html5-docs-name "FunnyQT"
  ;; :html5-docs-page-title nil ;; => "FunnyQT API Documentation"
  ;;:html5-docs-source-path "src/"
  :html5-docs-ns-includes #"^funnyqt\..*"
  :html5-docs-ns-excludes #".*\.test\..*"
  ;; :html5-docs-docs-dir nil ;; => "docs"
  :html5-docs-repository-url #(str "https://github.com/jgralab/funnyqt/blob/v"
                                   (:version %)))
