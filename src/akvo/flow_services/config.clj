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
           java.io.File
           [com.google.apphosting.utils.config AppEngineWebXml AppEngineWebXmlReader AppEngineConfigException])
  (:require [clojure.java.io :as io]
            [clojure.string :as string :only (split)]))


(def configs (atom {}))

(def instance-alias (atom {}))

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

(defn- get-alias [file]
  (let [appengine-web (.readAppEngineWebXml (AppEngineWebXmlReader. (.getAbsolutePath file) ""))
        app-id (.getAppId appengine-web)
        app-alias (-> appengine-web .getSystemProperties (get "alias"))
        domain (format "http://%s.appspot.com" app-id)]
    {app-alias domain}))

(defn- get-map [coll pred]
  (loop [c coll
         m {}]
    (if-not (seq c)
      m
      (recur (next c) (into m (pred (first c)))))))

(defn- load-alias-map
  [path]
  (let [files (filter #(= "appengine-web.xml" (.getName ^File %)) (list-config-files path))]
    (get-map files get-alias)))

(defn- load-upload-conf
  [path]
  (let [files (filter #(= "UploadConstants.properties" (.getName ^File %)) (list-config-files path))]
    (get-map files load-properties)))

(defn set-config!
  "Resets the value of configs map based on the Upload.properties files"
  [path]
  (swap! configs into (load-upload-conf path)))

(defn set-instance-alias!
  "Resets the value of the instance-alias map based on the appengine-web.xml files"
  [path]
  (swap! instance-alias into (load-alias-map path)))

(defn get-criteria
  "Returns a map of upload configuration criteria"
  [upload-domain surveyId]
  (let [domain (get-domain upload-domain)
        config (@configs domain)]
    {"uploadBase" (config "uploadUrl")
     "awsId" (config "s3Id")
     "dataPolicy" (config "surveyDataS3Policy")
     "dataSig" (config "surveyDataS3Sig")
     "imagePolicy" (config "imageS3Policy")
     "imageSig" (config "imageS3Sig")
     "apiKey" (config "apiKey")
     "surveyId" surveyId}))
