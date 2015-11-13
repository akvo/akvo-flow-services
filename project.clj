(defproject flow-services "0.9.10"
  :description "HTTP layer to applets functionality"
  :url "https://github.com/akvo/akvo-flow-services"
  :license {:name "GNU Affero General Public License"
            :url "https://www.gnu.org/licenses/agpl"}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/data.csv "0.1.2"]
                 [org.clojure/tools.nrepl "0.2.5"]
                 [org.akvo/commons "0.3.0"]
                 [com.taoensso/timbre "3.3.1"]
                 [cheshire "5.3.1"]
                 [compojure "1.1.8"]
                 [clojurewerkz/quartzite "1.3.0"]
                 [ring/ring-core "1.3.1"]
                 [ring/ring-jetty-adapter "1.3.1"]
                 [me.raynes/fs "1.4.6"]
                 [clj-aws-s3 "0.3.9" :exclusions [joda-time]]
                 [clj-http "1.0.0"]
                 [org.clojure/java.jdbc "0.3.5"]
                 [com.draines/postal "1.11.3"]
                 ; Java libraries
                 [jfree/jfreechart "1.0.13"]
                 [org.apache.poi/poi "3.8"]
                 [org.apache.poi/poi-ooxml "3.8"]
                 [org.apache.tika/tika-core "1.6"]
                 [org.apache.tika/tika-parsers "1.6"]
                 [org.slf4j/slf4j-api "1.7.7"]
                 [org.slf4j/slf4j-simple "1.7.7"]
                 [com.google.gdata/core "1.47.1"]
                 [org.xerial/sqlite-jdbc "3.7.2"]
                 ; Akvo FLOW dependencies
                 [exporterapplet "1.9.3"]
                 [org.json/json "20090211"]
                 [log4j/log4j "1.2.16"]
                 ; Configuration and Stats
                 [com.google.appengine/appengine-tools-sdk "1.9.18"]
                 [com.google.appengine/appengine-remote-api "1.9.18"]
                 [com.google.appengine/appengine-api-1.0-sdk "1.9.18"]]

  :main akvo.flow-services.core
  :aot [akvo.flow-services.core]
  :plugins [[lein-ring "0.8.5"]]
  :ring {:handler akvo.flow-services.core/app
         :init akvo.flow-services.core/init})
