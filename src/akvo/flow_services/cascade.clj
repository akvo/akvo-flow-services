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
                                    [jobs :as jobs]]
            [akvo.flow-services.config :as config]
            [akvo.flow-services.scheduler :as scheduler]
            [akvo.flow-services.gae :refer :all]
            [clojure.java.io :as io]
            [me.raynes.fs.compression :as fsc]
            [me.raynes.fs :as fs]
            [clojure.java.jdbc :refer [db-do-commands create-table-ddl insert!]]
            [aws.sdk.s3 :as s3]
            [taoensso.timbre :as timbre :refer [error debugf]]))

(def page-size 100)

(defn- create-db
  [db-spec]
  (debugf "Creating db %s" db-spec)
  (try
    (db-do-commands db-spec
      (create-table-ddl :nodes
        [:id :integer "PRIMARY KEY"]
        [:name :text]
        [:parent :integer])
      "CREATE UNIQUE INDEX node_idx ON nodes (name, parent)")
       (catch Exception e
         (error e "Error creating database"))))

(defn- store-node
  "write the item to the sqlite database"
  [node db-spec]
  (debugf "Storing node: %s" node)
  (insert! db-spec :nodes node))

(defn normalize-ids
  "Normalize the ids of the nodes to smaller values. e.g.
  nodeId name	   parentNodeId
  2323 	 item1   23212  =>  1  item1  2
  1212 	 item2   0      =>  3  item2  0
  6545 	 item3   2323   =>  4  item3  1
  23212   item4	 23455  =>  2  item4  5
  The 0 nodeId is mapped to 0"
  [nodes]
  (:result
    (reduce
     (fn [{:keys [idxs next-id result]} {:keys [id name parent]}]
       (let [new-id (get idxs id next-id)
             next-id (if (= new-id next-id) (inc next-id) next-id)
             new-parent-id (get idxs parent next-id)
             next-id (if (= new-parent-id next-id) (inc next-id) next-id)]
         {:idxs (assoc idxs id new-id parent new-parent-id)
          :next-id next-id
          :result (conj result {:id new-id :name name :parent new-parent-id})})) {:idxs {0 0}
                                                                                  :next-id 1
                                                                                  :result []} nodes)))

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
    (s3/put-object creds bucket obj-key f {} (s3/grant :all-users :read))))

(defn get-db-spec
  [path db-name]
  {:classname "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname  (str path "/" db-name)
   :db-name  db-name})

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
     (loop [data (.asList pq (get-fetch-options page-size))
            ;; nodeId 0 is mapped to 0
            idLookup {:index 0, 0 0}]
       ; TODO if the first list is empty, we should fail and not build an empty database
       (let [cursor (.getCursor data)]
         (if (empty? data) 
           nil
           (recur (.asList pq (get-fetch-options page-size cursor)) (process-data data idLookup db-settings)))))
     (.uninstall installer)
     
     ; TODO, check if the database construction was successful
     (println (upload-to-s3 (create-zip-file db-settings) bucket db-settings))
     
     ; TODO delete temp files after successful upload
     ))

(defn get-nodes
  "Returns the nodes for a given cascadeResourceId.
  A node just a map e.g. {:id 1 :name \"some name\" :parentId 0}"
  [upload-url cascade-id]
  (let [{:keys [username password]} @config/settings
        cfg (@config/configs (config/get-bucket-name upload-url))
        opts (get-options (:domain cfg) username password)
        installer (get-installer opts)
        ds (get-ds)
        query (Query. "CascadeNode")
        qf (.setFilter query (get-filter "cascadeResourceId" (Long/valueOf cascade-id)))
        pq (.prepare ds qf)
        data  (loop [entities (try
                                (.asList pq (get-fetch-options page-size))
                                (catch Exception _))
                     nodes []]
                (if (not (seq entities))
                  nodes
                  (recur (try (.asList pq (get-fetch-options page-size (.getCursor entities)))
                           (catch Exception _))
                    (apply conj nodes (for [node entities]
                                        {:id (.. node (getKey) (getId))
                                         :name (.getProperty node "name")
                                         :parent (.getProperty node "parentNodeId")})))))]
    (.uninstall installer)
    (if (seq data)
      (sort-by :id data))))

(jobs/defjob CascadeJob [job-data]
  (let [{:strs [uploadUrl cascadeResourceId version]} (conversion/from-job-data job-data)]
   (publish-cascade uploadUrl cascadeResourceId version)))

(defn schedule-publish-cascade [params]
  (scheduler/schedule-job CascadeJob (str (:cascadeResourceId params)) params))
