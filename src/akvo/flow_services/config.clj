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

(ns akvo.flow-services.config
  (:import org.apache.commons.io.FileUtils
           java.io.File)
  (:require [clojure.java.io :as io]
            [clojure.string :as string :only (split)]
            [clojure.data.xml :as xml :only (parse)]))



(defn- get-domain [url]
  (last (string/split url #"/")))

(defn- load-properties [file]
  (with-open [is ^java.io.InputStream (io/input-stream file)]
    (let [props (java.util.Properties.)]
      (.load props is)
      (assoc {} (get-domain (.getProperty ^java.util.Properties props "uploadUrl")) (into {} props)))))

(defn- list-config-files [path]
  (let [exts (into-array String ["properties" "xml"])]
    (FileUtils/listFiles (io/file path) ^"[Ljava.lang.String;" exts true)))

(defn- get-system-properties [content]
  (:content
    (first (filter #(= (:tag %) :system-properties) (:content content)))))

(defn- get-application-id [content]
  (first
    (:content
      (first
        (filter #(= (:tag %) :application) (:content content))))))

(defn- filter-xml [content]
  (first
    (filter #(= "alias" (let [{{:keys [name value]} :attrs} %] name)) (get-system-properties content))))

(defn- get-alias [file]
  (let [content (xml/parse (io/reader file))
        app-id (get-application-id content)
        {{:keys [name value]} :attrs} (filter-xml content)]
    {value app-id}))

(defn- get-map [coll pred]
  (loop [c coll
         m {}]
    (if-not (seq c)
      m
      (recur (next c) (into m (pred (first c)))))))

(defn load-alias-map
  "Returns a map of all instance alias based on the defintion in appengine-web.xml
   {alias1 instance-id1, alias2 instance-id2, ...}"
  [path]
  (let [files (filter #(= "appengine-web.xml" (.getName ^File %)) (list-config-files path))]
    (get-map files get-alias)))

(defn load-upload-conf
  "Returns a map of all UploadConstants.properties files in a directory
   The map follows the follwing structure: {domain1 {config1}, domain2 {config2} ... }
   The `domain` is the uploadUrl property in the properties file"
  [path]
  (let [files (filter #(= "UploadConstants.properties" (.getName ^File %)) (list-config-files path))]
    (get-map files load-properties)))