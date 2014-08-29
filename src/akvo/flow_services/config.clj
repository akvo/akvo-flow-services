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

(ns akvo.flow-services.config
  (:import java.io.File
    [com.google.apphosting.utils.config AppEngineWebXml AppEngineWebXmlReader AppEngineConfigException])
  (:require [clojure.java.io :as io]
    [clojure.string :as str :only (split)]
    [clojure.java.shell :as shell]
    [clojure.edn :as edn :only (read-string)]
    [me.raynes.fs :as fs :only (find-files)]))


(def configs (atom {}))

(def instance-alias (atom {}))

(def settings (atom {}))

(defn get-bucket-name
  "Extracts the bucket name from an upload domain url: https://akvoflow-1.s3.amazonaws.com => akvoflow-1"
  [url]
  (last (str/split (first (str/split url #"\.s3\.amazonaws\.com")) #"//")))

(defn- get-config [file]
  (let [appengine-web (-> file .getAbsolutePath (AppEngineWebXmlReader. "") .readAppEngineWebXml)
        app-id (.getAppId appengine-web)
        props (.getSystemProperties appengine-web)
        app-alias (get props "alias")
        access-key (get props "aws_identifier")
        secret-key (get props "aws_secret_key")
        s3bucket (get props "s3bucket")
        apiKey (get props "restPrivateKey")
        domain (format "%s.appspot.com" app-id)]
    {:alias app-alias
     :domain domain
     :access-key access-key
     :secret-key secret-key
     :s3bucket s3bucket
     :apiKey apiKey}))

(defn get-criteria
  "Returns a map of upload configuration criteria"
  [upload-domain surveyId]
  (let [domain (get-bucket-name upload-domain)
        config (@configs domain)]
    (assoc config :surveyId surveyId)))

(defn get-domain
  "Returns the instance domain for a given base-url"
  [base-url]
  (last (str/split base-url #"//")))

(defn get-alias
  "Returns the instance alias for a given domain or
  the same domain if no alias if found"
  [base-url]
  (let [domain (get-domain base-url)]
    (get @instance-alias domain domain)))

(defn set-config!
  "Resets the value of configs and alias maps based on the appengine-web.xml files"
  [path]
  (let [cfgs (map get-config (fs/find-files path #"appengine-web.xml"))
        bucket-fn (fn [res k v]
                    (assoc res k (first v)))
        alias-fn (fn [res k v]
                   (assoc res k (:alias (first v))))]
    (reset! configs (reduce-kv bucket-fn {} (group-by :s3bucket cfgs)))
    (reset! instance-alias (reduce-kv alias-fn {} (group-by :domain cfgs)))))

(defn set-settings!
  "Resets the value of settings reading the new values from the file path"
  [path]
  (reset! settings (-> path (io/file) (slurp) (edn/read-string))))

(defn reload [path]
  (let [pull (shell/with-sh-dir path (shell/sh "git" "pull"))]
    (when (zero? (pull :exit))
        (set-config! path))))
