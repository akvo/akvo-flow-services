(defproject flow-services "0.9.31"
  :description "HTTP layer to applets functionality"
  :url "https://github.com/akvo/akvo-flow-services"
  :license {:name "GNU Affero General Public License"
            :url  "https://www.gnu.org/licenses/agpl"}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/data.csv "0.1.3"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [org.akvo/commons "0.4.6" :exclusions [me.raynes/fs]]
                 [com.taoensso/timbre "4.10.0"]
                 [timbre-ns-pattern-level "0.1.2"]
                 [cheshire "5.3.1"]
                 [compojure "1.1.8"]
                 [clojurewerkz/quartzite "1.3.0"]
                 [ring/ring-core "1.3.1"]
                 [ring/ring-jetty-adapter "1.3.1"]
                 [akvo/fs "20180618-134534.a44cdd5b"]
                 [clj-aws-s3 "0.3.9" :exclusions [joda-time]]
                 [clj-http "3.8.0"]
                 [org.clojure/java.jdbc "0.3.5"]
                 ; Java libraries
                 [jfree/jfreechart "1.0.13"]
                 [commons-lang/commons-lang "2.4"]
                 [org.apache.poi/poi "3.8"]
                 [org.apache.poi/poi-ooxml "3.8"]
                 [org.apache.tika/tika-core "1.6"]
                 [org.apache.tika/tika-parsers "1.6"]
                 [com.fzakaria/slf4j-timbre "0.3.8"]
                 [org.slf4j/log4j-over-slf4j "1.7.25"]
                 [org.slf4j/jul-to-slf4j "1.7.25"]
                 [org.slf4j/jcl-over-slf4j "1.7.25"]
                 [com.google.gdata/core "1.47.1"]
                 [org.xerial/sqlite-jdbc "3.7.2"]
                 ; Akvo FLOW dependencies
                 [org.akvo.flow/akvo-flow "v1.9.46-16-g8349f157" :classifier "classes"]
                 [org.json/json "20090211"]
                 [org.codehaus.jackson/jackson-mapper-asl "1.9.13"]
                 ; Configuration and Stats
                 [com.google.appengine/appengine-tools-sdk "1.9.50"]
                 [com.google.appengine/appengine-remote-api "1.9.50"]
                 [com.google.appengine/appengine-api-1.0-sdk "1.9.50"]

                 [aero "1.1.3"]
                 [raven-clj "1.5.1"]]
  :exclusions [log4j]
  :main akvo.flow-services.core
  :uberjar-name "akvo-flow-services.jar"
  :test-selectors {:default (fn [m] (not (or (:integration m) (:kubernetes-test m) (:wip m))))
                   :integration (fn [m] (and (:integration m) (not (:wip m))))
                   :kubernetes-test :kubernetes-test}
  :monkeypatch-clojure-test false
  :plugins [[lein-ring "0.8.5"]]
  :profiles {:dev  [:project/dev  :profiles/dev]
             :test [:project/test :profiles/test]
             :uberjar {:aot :all}
             :profiles/dev  {}
             :profiles/test {}
             :project/dev   {:dependencies [[metosin/testit "0.2.0"]
                                            [org.clojure/test.check "0.9.0"]]}
             :project/test  {}})
