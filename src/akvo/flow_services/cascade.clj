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
           [java.nio.file Paths Files]
           [java.util UUID Date])
  (:require [clojurewerkz.quartzite [conversion :as conversion]
                                    [jobs :as jobs]]
            [akvo.flow-services.config :as config]
            [akvo.flow-services.scheduler :as scheduler]
            [akvo.flow-services.gae :refer :all]
            [clojure.java.io :as io]
            [me.raynes.fs.compression :as fsc]
            [me.raynes.fs :as fs]
            [clojure.java.jdbc :refer [db-do-commands create-table-ddl insert! query]]
            [clojure.data.csv :as csv]
            [aws.sdk.s3 :as s3]
            [taoensso.timbre :as timbre :refer [errorf debugf infof]]))

(def page-size 100)

(defn get-db-spec
  [path db-name]
  {:classname "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname  (str path "/" db-name)
   :db-name  db-name})

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
         (errorf e "Error creating database %s" db-spec))))

(defn- create-tmp-data-db
  [levels]
  (let [spec (get-db-spec "/tmp" (str (UUID/randomUUID) ".db"))
        schema (conj
                 (mapcat identity
                  (for [n (range levels)]
                    [[(keyword (format "code_%s text NOT NULL" n))] [(keyword (format "name_%s text NOT NULL" n))]]))
                 [:id :integer "PRIMARY KEY"])
        table-ddl (apply create-table-ddl :data schema)
        mapping-ddl (create-table-ddl :mapping
                      [:path :text "NOT NULL"]
                      [:keyid :number "NOT NULL"])
        mapping-idx "CREATE UNIQUE INDEX mapping_idx on mapping (path, keyid)"]
    (debugf "Table DDL: %s" table-ddl)
    (try
      (db-do-commands spec table-ddl)
      (db-do-commands spec mapping-ddl mapping-idx)
      spec
      (catch Exception e
        (errorf e "Error creating temp db - spec: %s - schema: %s" spec schema)))))

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
  (let [f (fn [{:keys [idxs next-id result]} {:keys [id name parent]}]
            (let [new-id (get idxs id next-id)
                  next-id (if (= new-id next-id) (inc next-id) next-id)
                  new-parent-id (get idxs parent next-id)
                  next-id (if (= new-parent-id next-id) (inc next-id) next-id)]
              {:idxs (assoc idxs id new-id parent new-parent-id)
               :next-id next-id
               :result (conj result {:id new-id :name name :parent new-parent-id})}))]
    (:result
      (reduce f {:idxs {0 0}
                 :next-id 1
                 :result []}
              nodes))))

(defn- create-zip-file 
  "zips the temporary file and returns the zipped file name"
  [db-spec]
  (let [fname (:subname db-spec)
        fname-zip (str fname ".zip")]
    (debugf "Creating zip file: " fname-zip)
    (fsc/zip fname-zip [[(:db-name db-spec) (Files/readAllBytes (Paths/get (java.net.URI. (str "file://" fname))))]])
    fname-zip))

(defn- upload-to-s3 
  "Upload the zipped sqlite file to s3"
  [fname bucket db-spec]
  (let [creds (select-keys (@config/configs bucket) [:access-key :secret-key])
        f (io/file fname)
        obj-key (str "surveys/" (:db-name db-spec) ".zip")]
    (debugf "Uploading object: %s to bucket: %s" obj-key bucket)
    (try
      (s3/put-object creds bucket obj-key f {} (s3/grant :all-users :read))
      (catch Exception e
        (errorf e "Error uploading object: %s to bucket: %s" obj-key bucket)))))

(defn get-nodes
  "Returns the nodes for a given cascadeResourceId.
  A node just a map e.g. {:id 1 :name \"some name\" :parent 0}"
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
                                (catch Exception e
                                  (errorf e "Error getting data from GAE: %s" (.getMessage e))))
                     nodes []]
                (if (not (seq entities))
                  nodes
                  (recur (try
                           (.asList pq (get-fetch-options page-size (.getCursor entities)))
                           (catch Exception e
                             (errorf e "Error getting data from GAE: %s" (.getMessage e))))
                    (into nodes (for [node entities]
                                  {:id (.. node (getKey) (getId))
                                   :name (.getProperty node "name")
                                   :parent (.getProperty node "parentNodeId")})))))]
    (.uninstall installer)
    (sort-by :id data)))


(defn validate-csv
  "Validates a cascade CSV file based on number of levels and the presence of `codes` in the file
   Returns the first invalid row or nil if everything is corrent"
  [fpath levels codes?]
  (let [l (if codes? (* levels 2) levels)
        f (io/file fpath)]
    (if (and (.exists f) (.canRead f))
      (with-open [r (io/reader f)]
        (some #(if (not= (count (remove empty? %)) l) %) (csv/read-csv r)))
      [(format "File Not Found at %s" (.getAbsolutePath f))])))

(defn csv-to-db
  "Creates a SQLite db and inserts the data from a CSV file.
  An exception when inserting can be considered a validation error"
  [fpath levels codes?]
  (let [db (create-tmp-data-db levels)
        columns (vec (flatten
                       (for [n (range levels)]
                         [(format "code_%s" n) (format "name_%s" n)])))]
    (with-open [r (io/reader (io/file fpath))]
      (debugf "Inserting CSV data into db")
      (doseq [line (csv/read-csv r)]
        (insert! db :data columns line)))
    db))

(defn create-node
  [code name parent-id resource-id]
  (let [node (Entity. "CascadeNode")
        ts (Date.)]
    (doto node
      (.setProperty "code" code)
      (.setProperty "name" name)
      (.setProperty "lastUpdateDateTime" ts)
      (.setProperty "createdDateTime" ts)
      (.setProperty "parentNodeId" parent-id)
      (.setProperty "cascadeResourceId" resource-id))
    node))

(defn- get-path
  [level as]
  (format "%s as %s"
    (clojure.string/join "||'|'||" (for [n (range (inc level))]
                                     (format "code_%s" n))) as))

(defn- get-limit-offset
  [limit offset]
  (format "LIMIT %s OFFSET %s" limit offset))

(defn- get-data-sql
  [level & [limit offset]]
  (let [sql (if (> level 0)
            (format "SELECT DISTINCT %s, %s, code_%s as code, name_%s as name FROM data ORDER BY id %s"
              (get-path (dec level) "parent")
              (get-path level "path")
              level
              level
              (if (and limit offset)
                (get-limit-offset limit offset)
                ""))
            (format "SELECT DISTINCT '0' as parent, code_0 as path, code_0 as code, name_0 as name FROM data ORDER BY id %s"
              (if (and limit offset)
                (get-limit-offset limit offset)
                "")))]
    (debugf "data sql: %s" sql)
    sql))

(defn- get-count-sql
  [level]
  (let [sql (format "SELECT count(*) as count FROM (%s)" (get-data-sql level))]
    (debugf "count sql: %s" sql)
    sql))

(defn create-nodes
  [db srv usr pwd levels limit]
  (let [installer (get-installer (get-options srv usr pwd))
        ds (get-ds)
        sql-limit 100]
    (loop [level 0
           offset 0
           level-count (:count (first (query db (get-count-sql level))))]
      (if (and (> level-count 0) (< level levels))
        (let [data-sql (get-data-sql level sql-limit offset)
              data (query db data-sql)
              inc-offset? (= (count data) sql-limit)
              inc-level? (not inc-offset?)
              new-level (if inc-level?
                          (inc level)
                          level)
              new-offset (if inc-level?
                           0
                           (if inc-offset?
                             (+ offset sql-limit)
                             offset))
              new-count (if inc-level?
                          (if (< new-level levels)
                            (:count (first (query db (get-count-sql (inc level))))) 0)
                          level-count)
              entities (reduce (fn [result item]
                                 (let [code (:code item)
                                       name (:name item)
                                       parent-id (if (> level 0)
                                                   (:keyid (first (query db (format "SELECT keyid FROM mapping WHERE path = '%s'" (:parent item)))))
                                                   0)]
                                   (conj result (create-node code name parent-id 0))))
                         []
                         data)
              key-ids (.put ds entities)
              mappings (apply merge (map (fn [node ds-id]
                                           {(:path node) (.getId ds-id)})
                                      data
                                      key-ids))]
          (try
            (doseq [m (keys mappings)]
              (debugf "inserting mapping: path %s -> %s" m (get mappings m))
              (insert! db :mapping {:path m :keyid (get mappings m)}))
            (catch Exception e
              (errorf "%s" data-sql)
              (errorf "%s" data)
              (errorf "%s" entities)
              (errorf "%s" key-ids)
              (throw e)))
          (recur new-level
            new-offset
            new-count))))))

(defn- publish-cascade [uploadUrl cascadeResourceId version]
   (let [{:keys [username password]} @config/settings
        bucket (config/get-bucket-name uploadUrl)
        config (@config/configs bucket)
        tmp-dir (fs/temp-dir (UUID/randomUUID))
        db-name (format  "cascade-%s-v%s.sqlite" cascadeResourceId version)
        db-spec (get-db-spec (.getAbsolutePath tmp-dir) db-name)
        db (create-db db-spec)]
     (if db
       (when-let [nodes (seq (normalize-ids (get-nodes uploadUrl cascadeResourceId)))]
         (doseq [n nodes]
           (store-node n db-spec))
         ;; recover this when we have more data on the size of db after vacuum
         ;; (db-do-commands db-spec false "vacuum")
         (upload-to-s3 (create-zip-file db-spec) bucket db-spec)
         (infof "Cascade resource published - uploadUrl: %s - resourceId: %s - version: %s" uploadUrl cascadeResourceId version)))))

(jobs/defjob CascadeJob [job-data]
  (let [{:strs [uploadUrl cascadeResourceId version]} (conversion/from-job-data job-data)]
    (infof "Publishing cascade resource - uploadUrl: %s - resourceId: %s - version: %s" uploadUrl cascadeResourceId version)
    (publish-cascade uploadUrl cascadeResourceId version)))

(defn schedule-publish-cascade [params]
  (scheduler/schedule-job CascadeJob (str "cascade" (hash params)) params))
