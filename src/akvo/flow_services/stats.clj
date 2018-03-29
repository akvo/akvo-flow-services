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
           java.util.Date java.text.SimpleDateFormat)
  (:require [clojurewerkz.quartzite [conversion :as conversion]
                                    [jobs :as jobs]
                                    [triggers :as triggers]
                                    [scheduler :as scheduler]]
            [clojurewerkz.quartzite.schedule.daily-interval :as interval]
            [akvo.commons.config :as config]
            [akvo.commons.gae :as gae]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :refer (difference)]
            [taoensso.timbre :as timbre :refer (errorf)]))

(defn datastore-spec [server]
  (let [host (first (str/split server #"\."))
        cfg (config/find-config host)]
    {:hostname server
     :service-account-id (:service-account-id cfg)
     :private-key-file (:private-key-file cfg)
     :port 443}))

(defn get-stats
  "Returns a list of stats for the given instance"
  [server kinds]
  (try
    (gae/with-datastore [ds (datastore-spec server)]
     (let [qt (Query. "__Stat_Total__")
           total (.asSingleEntity (.prepare ds qt))
           stats (if total ;; total can be nil on a new unused instance
                   (let [ts (.getProperty total "timestamp")
                         qk (.setFilter (Query. "__Stat_Kind__")
                                        (gae/get-filter "timestamp" ts))]
                     (.asList (.prepare ds qk) (gae/get-fetch-options))))]
       (filter #(kinds (.getProperty % "kind_name")) stats)))
    (catch Exception e
      (errorf e "Error trying to get data for %s" server))))

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
  (for [server server-list
        :let [stats (get-stats server kinds)]
        :when stats]
    (conj (calc-stats kinds stats) server)))

(defn- stats-job [{:strs [server-list kinds stats-path]}]
  (let [all-data (get-all-data server-list kinds)]
    (write-stats (conj (seq kinds) "Instance") all-data stats-path)))

(jobs/defjob StatsJob [job-data]
  (stats-job (conversion/from-job-data job-data)))

(defn job-data [settings]
  (let [{:keys [username password stats-path]} settings
        all-instances (set (map #(last (str/split % #"https?://")) (keys @config/instance-alias)))
        dev-instances (set (:dev-instances @config/settings))
        server-list (difference all-instances dev-instances)
        kinds (apply sorted-set (:stats-kinds settings))]
    {"username"    username
     "password"    password
     "server-list" server-list
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