;  Copyright (C) 2013-2014 Stichting Akvo (Akvo Foundation)
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
           java.util.Date java.text.SimpleDateFormat)
  (:require [clojurewerkz.quartzite [conversion :as conversion]
                                    [jobs :as jobs]
                                    [triggers :as triggers]
                                    [scheduler :as scheduler]]
            [clojurewerkz.quartzite.schedule.daily-interval :as interval]
            [akvo.flow-services.config :as config :only (settings instance-alias)]
            [akvo.flow-services.gae :refer :all]
            [clojure.data.csv :as csv :only (write-csv)]
            [clojure.java.io :as io]
            [clojure.string :as str :only (split)]
            [clojure.set :refer (difference)]))


(defn get-stats
  "Returns a list of stats for the given instance"
  [server usr pwd kinds]
  (let [opts (get-options server usr pwd)
        installer (get-installer opts)
        ds (get-ds)
        qt (Query. "__Stat_Total__")
        total (.asSingleEntity (.prepare ds qt))
        stats (if total ;; total can be nil on a new unused instance
                (let [ts (.getProperty total "timestamp")
                      qk (.setFilter (Query. "__Stat_Kind__") (get-filter "timestamp" ts))]
                  (.asList (.prepare ds qk) (get-fetch-options))))]
    (.uninstall installer)
    (filter #(kinds (.getProperty % "kind_name")) stats)))

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

(defn get-all-data [server-list username password kinds]
  (for [server server-list
        :let [stats (get-stats server username password kinds)]]
    (conj (calc-stats kinds stats) server)))

(jobs/defjob StatsJob [job-data]
  (let [{:strs [username password server-list kinds stats-path]} (conversion/from-job-data job-data)
        all-data (get-all-data server-list username password kinds)]
    (write-stats (conj (seq kinds) "Instance") all-data stats-path)))

(defn schedule-stats-job
  "Schedule a daily job execution based on the sch-time in the form [HH mm ss]"
  [sch-time]
  (let [settings @config/settings
        {:keys [username password stats-path]} settings
        all-instances (set (map #(last (str/split % #"https?://")) (keys @config/instance-alias)))
        dev-instances (set (:dev-instances @config/settings))
        server-list (difference all-instances dev-instances)
        kinds (apply sorted-set (:stats-kinds settings))
        job (jobs/build
              (jobs/of-type StatsJob)
              (jobs/with-identity (jobs/key "stats-job"))
              (jobs/using-job-data {"username" username
                                    "password" password
                                    "server-list" server-list
                                    "stats-path" stats-path
                                    "kinds" kinds}))
        trigger (triggers/build
                  (triggers/with-identity (triggers/key "stats-trigger"))
                  (triggers/start-now)
                  (triggers/with-schedule
                    (interval/schedule
                      (interval/with-interval-in-days 1)
                      (interval/starting-daily-at
                        (apply interval/time-of-day sch-time)))))]
    (scheduler/schedule job trigger)))
