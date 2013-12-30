;  Copyright (C) 2013 Stichting Akvo (Akvo Foundation)
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
  (:import [com.google.appengine.tools.remoteapi RemoteApiInstaller RemoteApiOptions]
           [com.google.appengine.api.datastore DatastoreServiceFactory Entity Query
            Query$FilterOperator Query$FilterPredicate PreparedQuery FetchOptions FetchOptions$Builder])
  (:require [clojurewerkz.quartzite [conversion :as conversion]
                                    [jobs :as jobs]]
            [akvo.flow-services.core :as core :only (settings)]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]))


(defn get-options 
  "Returns a RemoteApiOptions object"
  [server usr pwd]
  (doto
    (RemoteApiOptions.)
    (.server server 443)
    (.credentials usr pwd)))

(defn get-installer
  "Returns a RemoteApiInstaller object"
  [opts]
  (doto
    (RemoteApiInstaller.)
    (.install opts)))

(defn get-defaults
  "Returns the defaults options for a PreparedQuery"
  []
  (FetchOptions$Builder/withDefaults))

(defn get-ds
  "Returns an instance of a DatastoreService"
  []
  (DatastoreServiceFactory/getDatastoreService))

(defn get-filter
  "Helper function that returns a FilterPredicate based on a property"
  [property value]
  (Query$FilterPredicate. property Query$FilterOperator/EQUAL value))

(defn get-stats
  "Returns a list of stats for the given instance"
  [server usr pwd kinds]
  (let [opts (get-options server usr pwd)
        installer (get-installer opts)
        ds (get-ds)
        qt (Query. "__Stat_Total__")
        total (.asSingleEntity (.prepare ds qt))
        ts (.getProperty total "timestamp")
        qk (.setFilter (Query. "__Stat_Kind__") (get-filter "timestamp" ts))
        stats (.asList (.prepare ds qk) (get-defaults))]
    (.uninstall installer)
    stats (filter #(kinds (.getProperty % "kind_name")) stats)))

(defn calc-stats [kinds stats]
   (for [e (filter #(kinds (.getProperty % "kind_name")) stats)
         k kinds
         :when (= (.getProperty e "kind_name") k)]
     (.getProperty e "count")))

(defn write-stats [kinds data]
  (with-open [out-file (io/writer "out-file.csv")]
    (csv/write-csv out-file
                   (conj data kinds))))

(defn get-all-data [server-list user password kinds]
  (for [server server-list 
        :let [stats (get-stats server user password kinds)]] 
    (calc-stats kinds stats)))

(jobs/defjob StatsJob [job-data]
  (let [{:strs [user password server-list kinds]} (conversion/from-job-data job-data)
        all-data (get-all-data server-list user password kinds)]
    (write-stats kinds all-data)))
