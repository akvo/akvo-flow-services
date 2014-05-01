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
  (:import java.io.File
           [com.google.apphosting.utils.config AppEngineWebXml AppEngineWebXmlReader AppEngineConfigException])
  (:require [clojure.java.io :as io]
            [clojure.string :as string :only (split)]
            [clojure.java.shell :as shell]
            [clojure.edn :as edn :only (read-string)]
            [me.raynes.fs :as fs :only (find-files)]))


(def configs (atom {}))

(def instance-alias (atom {}))

(def settings (atom {}))

(def property-alias
       {"uploadUrl"          "uploadBase"  
        "s3Id"               "awsId"       
        "surveyDataS3Policy" "dataPolicy"  
        "surveyDataS3Sig"    "dataSig"     
        "imageS3Policy"      "imagePolicy" 
        "imageS3Sig"         "imageSig"    
        "apiKey"             "apiKey"})

(defn get-domain
  "Extracts the domain from a string (takes care of trailing slash)
  \"http://sub.akvoflow.org\" => \"sub.akvoflow.org\""
  [url]
  (last (string/split url #"/")))

(defn- transform-map 
  "Build a map with a given Properties file and
  the alias of the keys to be modified"
  [props alias-map]
  (loop [properties (keys props)
         m {}]
    (if (seq properties)
      (recur (next properties) (assoc m (if-let [k (alias-map (first properties))]
                                           k ; Only if it exists on the alias map
                                           (first properties)) ; The original key otherwise
                                       (.getProperty props (first properties))))
      m)))

(defn- load-properties [file]
  (with-open [is ^java.io.InputStream (io/input-stream file)]
    (let [props (java.util.Properties.)]
      (.load props is)
      (assoc {} (get-domain (.getProperty ^java.util.Properties props "uploadUrl")) (transform-map props property-alias)))))

(defn- get-alias [file]
  (let [appengine-web (-> file .getAbsolutePath (AppEngineWebXmlReader. "") .readAppEngineWebXml)
        app-id (.getAppId appengine-web)
        app-alias (-> appengine-web .getSystemProperties (get "alias"))
        domain (format "http://%s.appspot.com" app-id)]
    {app-alias domain}))

(defn- get-map [coll func]
  (loop [c coll
         m {}]
    (if-not (seq c)
      m
      (recur (next c) (into m (func (first c)))))))

(defn- load-alias-map
  [path]
  (get-map (fs/find-files path #"appengine-web.xml") get-alias))

(defn- load-upload-conf
  [path]
  (get-map (fs/find-files path #"UploadConstants.properties") load-properties))

(defn set-config!
  "Resets the value of configs map based on the Upload.properties files"
  [path]
  (reset! configs (load-upload-conf path)))

(defn set-instance-alias!
  "Resets the value of the instance-alias map based on the appengine-web.xml files"
  [path]
  (reset! instance-alias (load-alias-map path)))

(defn set-settings!
  "Resets the value of settings reading the new values from the file path"
  [path]
  (reset! settings (-> path (io/file) (slurp) (edn/read-string))))

(defn get-criteria
  "Returns a map of upload configuration criteria"
  [upload-domain surveyId]
  (let [domain (get-domain upload-domain)
        config (@configs domain)]
    (assoc config "surveyId" surveyId)))

(defn reload [path]
  (let [pull (shell/with-sh-dir path (shell/sh "git" "pull"))]
    (when (zero? (pull :exit))
        (set-config! path)
        (set-instance-alias! path))))
