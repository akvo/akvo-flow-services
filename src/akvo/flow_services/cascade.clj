;  Copyright (C) 2014 Stichting Akvo (Akvo Foundation)
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

(ns akvo.flow-services.cascade
  (:import [com.google.appengine.api.datastore Entity Query] 
           [java.nio.file Paths Files])
  (:require [clojurewerkz.quartzite [conversion :as conversion]
                                    [jobs :as jobs]
                                    [scheduler :as q-scheduler]]
            [akvo.flow-services.config :as config]
            [akvo.flow-services.scheduler :as scheduler]
            [akvo.flow-services.gae :refer :all]
            [clojure.java.io :as io]
            [me.raynes.fs.compression :as fsc]
            [me.raynes.fs :as fs]
            [clojure.java.jdbc :refer :all]
            [aws.sdk.s3 :as s3]))

(def page-size 1000)

(defn- create-db 
  [settings]
  (try (db-do-commands settings
    (create-table-ddl :nodes
                      [:nodeId :integer]
                      [:name :text]
                      [:parentNodeId :integer]))
       (catch Exception e (println e))))

(defn- store-item
  "write the item to the sqlite database"
  [nodeId name parentNodeId db-settings]
  (insert! db-settings :nodes {:nodeId nodeId, :name name, :parentNodeId parentNodeId}))

(defn- normalise-item
 "Stores items with normalised ids. For example, this list:
 nodeId name	   parentNodeId
 2323 	 item1   23212  =>  1  item1  2
 1212 	 item2   0      =>  3  item2  0
 6545 	 item3   2323   =>  4  item3  1
 23212   item4	 23455  =>  2  item4	5
 The 0 nodeId is mapped to 0"
  [nodeId parentNodeId name ids db-settings]
  (let [index (ids :index)
        newIndex (atom index)
        ids-node (if (contains? ids nodeId)
                   ids
                   (do (swap! newIndex inc)
                     (assoc ids nodeId @newIndex)))
        ids-parentNode (if (contains? ids-node parentNodeId)
                        ids-node
                        (do (swap! newIndex inc)
                          (assoc ids-node parentNodeId @newIndex)))
        ids-index (assoc ids-parentNode :index @newIndex)] 
    ; write item to datastore
    (store-item (ids-index nodeId) name (ids-index parentNodeId) db-settings)    
    ; return updated lookup table
    ids-index))

(defn- process-data
  "runs trough a list of data retrieved from GAE, and 
   sends them off to be normalised and stored"
  [data idLookup db-settings]
   (reduce (fn [ids item] 
             (let [parentNodeId (.getProperty item "parentNodeId")
                   nodeId (-> item .getKey .getId)
                   name (.getProperty item "name")] 
               (normalise-item nodeId parentNodeId name ids db-settings)
               )) idLookup data))

(defn- create-zip-file 
  "zips the temporary file and returns the zipped file name"
  [db-settings]
  (let [fname (:subname db-settings)
        fname-zip (str fname ".zip")]
    (fsc/zip fname-zip [[(:db-name db-settings) (Files/readAllBytes (Paths/get (java.net.URI. (str "file://" fname))))]])
    fname-zip))

(defn- upload-to-s3 
  "Upload the zipped sqlite file to s3"
  [fname bucket db-settings]
  (let [creds (select-keys (@config/configs bucket) [:access-key :secret-key])
        f (io/file fname)
        obj-key (str "surveys/" (:db-name db-settings) ".zip")]
    (s3/put-object creds bucket obj-key f {} (s3/grant :all-users :read))
    ))

(defn- publish-cascade [uploadUrl cascadeResourceId version]
   (let [
        settings @config/settings
        {:keys [username password tmp-path]} settings
        ;{:keys [uploadUrl cascadeResourceId version]} params
        bucket (config/get-bucket-name uploadUrl)
        config (@config/configs bucket)
        
        ; sqlite database
        tmp-dir-path  (str tmp-path "/" (java.util.UUID/randomUUID))
        
        ; create temp dir
        tmp-dir (fs/mkdir tmp-dir-path)
        db-name (str "cascade-" cascadeResourceId "-v" version ".sqlite")
        db-settings {:classname "org.sqlite.JDBC"
                     :subprotocol "sqlite"
                     :subname  (str tmp-dir-path "/" db-name)
                     :db-name  db-name}

        ; GAE remote API
        opts (get-options (:domain config) username password)
        installer (get-installer opts)
        ds (get-ds)
        query (Query. "CascadeNode")
        qf (.setFilter query (get-filter "cascadeResourceId" (Long/valueOf cascadeResourceId)))
        pq (.prepare ds qf)]
     
     ; create sqlite database file
     (create-db db-settings)
     
     ; get batches from GAE using a cursor and process them
     (loop [data (.asList pq (get-fetch-options-cursor page-size nil))
            ;; nodeId 0 is mapped to 0
            idLookup {:index 0, 0 0}]
       ; TODO if the first list is empty, we should fail and not build an empty database
       (let [cursor (.getCursor data)]
         (if (empty? data) 
           nil
           (recur (.asList pq (get-fetch-options-cursor page-size cursor)) (process-data data idLookup db-settings)))))
     (.uninstall installer)
     
     ; TODO, check if the database construction was successful
     (println (upload-to-s3 (create-zip-file db-settings) bucket db-settings))
     
     ; TODO delete temp files after successful upload
     ))

(jobs/defjob CascadeJob [job-data]
  (let [{:strs [uploadUrl cascadeResourceId version]} (conversion/from-job-data job-data)]
   (publish-cascade uploadUrl cascadeResourceId version)))

(defn schedule-publish-cascade [params]
  (scheduler/schedule-job CascadeJob (str (:cascadeResourceId params)) params))
