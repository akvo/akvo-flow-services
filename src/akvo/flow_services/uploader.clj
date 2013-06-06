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

(ns akvo.flow-services.uploader
  (:import java.io.File
           org.apache.commons.io.FileUtils
           org.apache.ant.compress.taskdefs.Unzip
           org.waterforpeople.mapping.dataexport.SurveyDataImportExportFactory)
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pprint]))


(def configs (atom {}))

(defn set-config!
  "Resets the value of configs map"
  [configs-map]
  (swap! configs into configs-map))

(defn- get-path []
  (format "%s/%s" (System/getProperty "java.io.tmpdir") "akvo/flow/uploads"))

(defn save-chunk
  "Saves the current produced ring temp file in a different folder.
   The expected `params` is a ring map containing a `file` part o the multipart request."
  [params]
  (let [identifier (format "%s/%s" (get-path) (params "resumableIdentifier"))
        path (io/file identifier)
        tempfile (params "file")]
    (if-not (.exists ^File path)
      (.mkdirs path))
    (io/copy (tempfile :tempfile)
             (io/file (format "%s/%s.%s" identifier (params "resumableFilename") (params "resumableChunkNumber"))))
    "OK"))

(defn- combine [directory filename no-parts]
  (let [f (io/file (format "%s/%s" directory filename))]
    (doseq [idx (range 1 (+ 1 no-parts))]
      (FileUtils/writeByteArrayToFile f (FileUtils/readFileToByteArray (io/file (format "%s/%s.%s" directory filename idx))) true))))

(defn- cleanup [path]
  (doseq [file (filter #(re-find #".\d+$" (.getName ^File %)) (FileUtils/listFiles (io/file path) nil false))]
    (FileUtils/deleteQuietly ^File file)))

(defn delete-directory
  "Deletes a directory recursively"
  [path]
  (FileUtils/deleteDirectory (io/file path)))

(defn- unzip-file [directory filename]
  (let [dest (io/file (format "%s/%s" directory "zip-content"))]
    (if-not (.exists ^File dest)
      (.mkdirs dest))
    (doto (Unzip.)
      (.setSrc (io/file (format "%s/%s" directory filename)))
      (.setDest dest)
      (.execute))
    dest))

(defn- get-criteria [upload-domain surveyId]
  (let [config (@configs upload-domain)]
    {"uploadBase" (config "uploadUrl")
     "awsId" (config "s3Id")
     "dataPolicy" (config "surveyDataS3Policy")
     "dataSig" (config "surveyDataS3Sig")
     "imagePolicy" (config "imageS3Policy")
     "imageSig" (config "imageS3Sig")
     "surveyId" surveyId}))

(defn- get-upload-type [^File path]
  (if (and (.isFile path)
           (.endsWith (str/upper-case (.getName path)) "XLSX"))
    "RAW_DATA"
    "BULK_SURVEY"))

(defn- upload [path base-url upload-domain surveyId]
  (let [importer (.getImporter (SurveyDataImportExportFactory.) (get-upload-type path))]
    (.executeImport importer path base-url (get-criteria upload-domain surveyId))))

(defn bulk-upload
  "Combines the parts, extracts and uploads the content of a zip file"
  [base-url unique-identifier filename upload-domain surveyId]
  (let [path (format "%s/%s" (get-path) unique-identifier)
        no-parts (count (seq (FileUtils/listFiles (io/file path) nil false)))
        uname (str/upper-case filename)]
    (combine path filename no-parts)
    (cleanup path)
    (cond
      (.endsWith uname "ZIP") (upload (unzip-file path filename) base-url upload-domain surveyId) ; Extract and upload
      (.endsWith uname "XLSX") (upload (io/file path filename) base-url upload-domain surveyId) ; Upload raw data
      :else (upload (io/file path) base-url upload-domain surveyId)))) ; JPG? upload file in the folder
