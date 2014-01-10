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
           org.waterforpeople.mapping.dataexport.SurveyDataImportExportFactory)
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pprint]
            [akvo.flow-services.config :as config]
            [me.raynes.fs :as fs :only (find-files file? delete delete-dir)]
            [me.raynes.fs.compression :as fsc :only (unzip)]))


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

(defn- get-parts [path]
  (sort (filter #(fs/file? %) (fs/find-files path #".*\d+$"))))

(defn- combine [directory filename]
  (let [f (io/file (format "%s/%s" directory filename))
        parts (get-parts directory)]
    (if (seq parts)
      (with-open [os (io/output-stream f)]
        (doseq [p parts]
          (io/copy p os))))))

(defn- cleanup [path]
  (doseq [file (get-parts path)]
    (fs/delete file)))

(defn- unzip-file [directory filename]
  (let [dest (io/file (format "%s/%s" directory "zip-content"))
        source (io/file (format "%s/%s" directory filename))]
    (if-not (.exists ^File dest)
      (.mkdirs dest))
    (fsc/unzip source dest)))

(defn- get-upload-type [^File path]
  (if (and (.isFile path)
           (.endsWith (str/upper-case (.getName path)) "XLSX"))
    "RAW_DATA"
    "BULK_SURVEY"))

(defn- upload [path base-url upload-domain surveyId]
  (let [importer (.getImporter (SurveyDataImportExportFactory.) (get-upload-type path))]
    (.executeImport importer path base-url (config/get-criteria upload-domain surveyId))))

(defn bulk-upload
  "Combines the parts, extracts and uploads the content of a zip file"
  [base-url unique-identifier filename upload-domain surveyId]
  (let [path (format "%s/%s" (get-path) unique-identifier)
        uname (str/upper-case filename)]
    (combine path filename)
    (cleanup path)
    (cond
      (.endsWith uname "ZIP") (upload (unzip-file path filename) base-url upload-domain surveyId) ; Extract and upload
      (.endsWith uname "XLSX") (upload (io/file path filename) base-url upload-domain surveyId) ; Upload raw data
      :else (upload (io/file path) base-url upload-domain surveyId)) ; JPG? upload file in the folder
    (fs/delete-dir path)))
