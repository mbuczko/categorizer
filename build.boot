(set-env!
 :source-paths   #{"src"}
 :dependencies '[[org.clojure/clojure "1.8.0" :scope "provided"]
                 [org.clojure/tools.logging "0.3.1" :scope "test"]
                 [boot/core "2.5.5" :scope "test"]
                 [adzerk/bootlaces "0.1.13" :scope "test"]
                 [zilti/boot-midje "0.2.1-SNAPSHOT" :scope "test"]
                 [midje "1.8.3" :scope "test"]
                 [cheshire "5.5.0"]
                 [michaelblume/marginalia "0.9.0" :scope "test" :excludes [org.clojure/tools.namespace]]])

(require '[adzerk.bootlaces :refer :all]
         '[zilti.boot-midje :refer [midje]]
         '[marginalia.html  :refer [*resources*]]
         '[marginalia.core  :as marg])

(def +version+ "0.4.3")

(bootlaces! +version+)

(deftask marginalia
  []
  (binding [*resources* ""]
    (marg/uberdoc!
     "./docs/literate.html"
     ["examples/src/categorizer/examples/tree.clj"]
     {:name "mbuczko/categorizer"
      :version +version+})))

(task-options!
 midje {:test-paths #{"test"}}
 pom   {:project 'mbuczko/categorizer
        :version +version+
        :description "Category tree management"
        :url "https://github.com/mbuczko/categorizer"
        :scm {:url "https://github.com/mbuczko/categorizer"}
        :license {"Eclipse Public License" "http://www.eclipse.org/legal/epl-v10.html"}})
