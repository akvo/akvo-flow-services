;  Copyright (C) 2013-2015 Stichting Akvo (Akvo Foundation)
;
;  This file is part of Akvo FLOW.
;
;  Akvo FLOW is free software: you can redistribute it and modify it under the terms of
;  the GNU Affero General Public License (AGPL) as published by the Free Software Foundation,
;  either version 3 of the License or any later version.
;
;  Akvo FLOW is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
;  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
;  See the GNU Affero General Public License included below for more details.
;
;  The full license text can also be seen at <http://www.gnu.org/licenses/agpl.html>.

(ns akvo.flow-services.stats
  (:import [com.google.appengine.api.datastore Entity Query]
           java.util.Date
           java.text.SimpleDateFormat)
  (:require [clojurewerkz.quartzite [conversion :as conversion]
             [jobs :as jobs]
             [triggers :as triggers]
             [scheduler :as scheduler]]
            [clojurewerkz.quartzite.schedule.daily-interval :as interval]
            [akvo.commons.config :as config]
            [akvo.flow-services.util :as util]
            [akvo.commons.gae :as gae]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :refer (difference)]
            [taoensso.timbre :refer (errorf) :as timbre]
            [akvo.flow-services.exporter :as exporter]
            [akvo.flow-services.error :as e]))

(defn datastore-spec [server]
  (let [host (first (str/split server #"\."))]
    (util/datastore-spec host)))

(defn get-stats
  "Returns a list of stats for the given instance"
  [server kinds]
  (try
    (gae/with-datastore [ds (datastore-spec server)]
      (let [qt (Query. "__Stat_Total__")
            total (.asSingleEntity (.prepare ds qt))
            stats (if total                                 ;; total can be nil on a new unused instance
                    (let [ts (.getProperty total "timestamp")
                          qk (.setFilter (Query. "__Stat_Kind__")
                                         (gae/get-filter "timestamp" ts))]
                      (.asList (.prepare ds qk) (gae/get-fetch-options))))]
        (filter #(kinds (.getProperty % "kind_name")) stats)))
    (catch Exception e
      (e/error {:cause  e
                :server server}))))

(defn calc-stats [kinds stats]
  (let [entities (reduce #(assoc %1 (.getProperty %2 "kind_name") (.getProperty %2 "count")) {} stats)]
    (for [k kinds]
      (if (entities k)
        (entities k)
        0))))

(defn write-stats [kinds data stats-path]
  (let [filename (.format (SimpleDateFormat. "yyyy-MM-dd") (Date.))
        file (str stats-path "/" filename ".csv")
        fstats-path (io/file stats-path)]
    (if-not (.exists fstats-path)
      (.mkdirs fstats-path))
    (with-open [out-file (io/writer file)]
      (csv/write-csv out-file
                     (conj data kinds)))))

(defn get-all-data [server-list kinds]
  (for [server server-list]
    (e/if-ok (get-stats server kinds)
             #(conj (calc-stats kinds %) server))))

(defn too-many-errors? [all-data]
  (let [errors (vec (filter e/error? all-data))]
    (cond
      (empty? errors) {:level :info :message "No errors collecting stats" :data ""}

      (< 0.2
         (/ (count errors)
            (count all-data))) {:level :error :message "Too many errors collecting stats" :data errors}

      :else {:level :info :message "Some errors collecting stats" :data errors})))

(defn report-errors [all-data]
  (let [errors (too-many-errors? all-data)]
    (timbre/log (:level errors) (:message errors) (:data errors))))

(defn- stats-job [{:strs [server-list kinds stats-path]}]
  (let [all-data (get-all-data server-list kinds)
        valid-stats (filter e/ok? all-data)]
    (write-stats (conj (seq kinds) "Instance") valid-stats stats-path)
    (report-errors all-data)))

(jobs/defjob StatsJob [job-data]
  (stats-job (conversion/from-job-data job-data)))

(defn job-data [settings]
  (let [stats-path (str (exporter/get-report-directory) "/stats")
        all-instances (set (map #(last (str/split % #"https?://")) (keys @config/instance-alias)))
        dev-instances (set (:dev-instances settings))
        server-list (difference all-instances dev-instances)
        kinds (apply sorted-set (:stats-kinds settings))]
    {"server-list" server-list
     "stats-path"  stats-path
     "kinds"       kinds}))

(defn schedule-stats-job
  "Schedule a daily job execution based on the sch-time in the form [HH mm ss]"
  [sch-time]
  (let [job (jobs/build
              (jobs/of-type StatsJob)
              (jobs/with-identity (jobs/key "stats-job"))
              (jobs/using-job-data (job-data @config/settings)))
        trigger (triggers/build
                  (triggers/with-identity (triggers/key "stats-trigger"))
                  (triggers/start-now)
                  (triggers/with-schedule
                    (interval/schedule
                      (interval/with-interval-in-days 1)
                      (interval/starting-daily-at
                        (apply interval/time-of-day sch-time)))))]
    (scheduler/schedule job trigger)))


(comment
  (stats-job (job-data @config/settings))

  )