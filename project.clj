(defproject flow-services "0.5.1"
  :description "HTTP layer to applets functionality"
  :url "https://github.com/akvo/akvo-flow-services"
  :license {:name "GNU Affero General Public License"
            :url "https://www.gnu.org/licenses/agpl"}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.clojure/data.xml "0.0.7"]
                 [cheshire "5.1.1"]
                 [compojure "1.1.5"]
                 [clojurewerkz/quartzite "1.0.1"]
                 [ring/ring-core "1.1.8"]
                 [ring/ring-servlet "1.1.8"]
                 [ring/ring-jetty-adapter "1.1.8"]
                 ; Java libraries
                 [jfree/jfreechart "1.0.13"]
                 [org.apache.poi/poi "3.8"]
                 [org.apache.poi/poi-ooxml "3.8"]
                 [org.apache.ant/ant-compress "1.2"]
                 [org.slf4j/slf4j-api "1.7.3"]
                 [org.slf4j/slf4j-simple "1.7.3"]
                 ; Akvo FLOW dependencies
                 [exporterapplet "1.0.0"]
                 [org.json/json "20090211"]]

  :main akvo.flow-services.core
  :aot [akvo.flow-services.core]
  :plugins [[lein-ring "0.8.5"]]
  :ring {:handler akvo.flow-services.core/app
         :init akvo.flow-services.core/init})
