(ns akvo.flow-services.stats-test
  (:require [clojure.test :refer :all]
            [akvo.flow-services.stats :as stats]
            [akvo.flow-services.error :as e]
            [akvo.commons.config :as config]
            [akvo.flow-services.test-util :as test-util]
            [akvo.commons.gae :as gae]
            [clojure.string :as str]
            [akvo.commons.gae.query :as query])
  (:import (com.google.appengine.api.datastore Query DatastoreService Entity)))

(def error (e/error "some error"))
(def ok "")

(deftest too-many-errors
  (is (= (stats/too-many-errors? [error error])
         {:level   :error
          :message "Too many errors collecting stats"
          :data    [error error]}))
  (is (= (stats/too-many-errors? [error ok ok ok])
         {:level   :error
          :message "Too many errors collecting stats"
          :data    [error]}))
  (is (= (stats/too-many-errors? [error ok ok ok ok])
         {:level   :info
          :message "Some errors collecting stats"
          :data    [error]}))
  (is (= (stats/too-many-errors? [ok])
         {:level   :info
          :message "No errors collecting stats"
          :data    ""}))
  (is (= (stats/too-many-errors? [])
         {:level   :info
          :message "No errors collecting stats"
          :data    ""})))

(defn upsert!
  "Upsert Entity"
  [^DatastoreService ds query props]
  (let [entity (or (.asSingleEntity (.prepare ds
                                              query))
                   (Entity. (.getKind query)))]
    (doseq [k (keys props)]
      (.setProperty entity (name k) (props k)))
    (.put ds entity)))

(defn query
  ([kind] (Query. kind))
  ([kind filter] (.setFilter (Query. kind) filter)))

(defn kind-stat [kind]
  (query stats/*stats-kind* (gae/get-filter "kind_name" kind)))

(defn setup-db [user-count device-count]
  (gae/with-datastore [ds test-util/gae-local]
    (let [ts (System/currentTimeMillis)]
      (upsert! ds (query stats/*total-stats-kind*)
               {"timestamp" ts})
      (upsert! ds (kind-stat "User")
               {"count"     user-count
                "kind_name" "User"
                "timestamp" ts})
      (upsert! ds (kind-stat "Device")
               {"count"     device-count
                "kind_name" "Device"
                "timestamp" ts})))
  (test-util/try-for "GAE took too long to sync" 10
                     (= 2
                        (gae/with-datastore [ds test-util/gae-local]
                          (count (iterator-seq (.iterator (query/result ds
                                                                        {:kind stats/*stats-kind*}))))))))

(deftest ^:integration happy-path
  (binding [stats/*total-stats-kind* "Testing_Stats_Total"
            stats/*stats-kind* "Testing_Stat_Kind"]
    (let [user-count (rand-int 100000)
          device-count (rand-int 100000)
          _ (setup-db user-count device-count)
          result-file (stats/stats-job (stats/job-data (dissoc @config/settings :dev-instances)))]
      (is (= (str "akvoflowsandbox.appspot.com," device-count ",0,0,0," user-count)
             (second (str/split-lines (slurp result-file))))))))