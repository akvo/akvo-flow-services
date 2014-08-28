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

(ns akvo.flow-services.uploader
  (:import java.io.File
           org.waterforpeople.mapping.dataexport.SurveyDataImportExportFactory
           java.util.zip.ZipFile)
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [akvo.flow-services.config :as config]
            [me.raynes.fs :as fs :only (find-files file? delete delete-dir)]
            [me.raynes.fs.compression :as fsc :only (zip unzip)]
            [aws.sdk.s3 :as s3 :only (put-object)]))


(defn- get-path []
  (format "%s/%s" (:base-path @config/settings) "uploads"))

(defn save-chunk
  "Saves the current produced ring temp file in a different folder.
   The expected `params` is a ring map containing a `file` part o the multipart request."
  [params]
  (let [identifier (format "%s/%s" (get-path) (:resumableIdentifier params))
        path (io/file identifier)
        tempfile (:file params)]
    (if-not (.exists ^File path)
      (.mkdirs path))
    (io/copy (:tempfile tempfile)
             (io/file (format "%s/%s.%s" identifier (:resumableFilename params) (:resumableChunkNumber params))))
    "OK"))

(defn- part-no-comp
  "Comparator function based on file part number
   expects 2 files using filename.ext.number pattern"
  [f1 f2]
  (let [part-no #"\d+$"
        p1 (read-string (re-find part-no (.getName f1)))
        p2 (read-string (re-find part-no (.getName f2)))]
    (< p1 p2)))

(defn- get-parts [path]
  (sort part-no-comp (filter fs/file? (fs/find-files path #".*\.\d+$"))))

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

(defn- get-key
  [f]
  (let [fname (.getName f)
        pos (.lastIndexOf fname ".")
        ext (.substring fname (inc pos))
        prefix (condp = (.toLowerCase ext)
                 "zip" "devicezip/"
                 "jpg" "images/"
                 "jpeg" "images/")]
    (str prefix fname)))

(defn- upload [f bucket-name]
  (let [creds (select-keys (@config/configs bucket-name) [:access-key :secret-key])
        obj-key (get-key f)]
    (s3/put-object creds bucket-name obj-key f)))

(defn- raw-data
  [path base-url bucket-name surveyId]
  (let [importer (.getImporter (SurveyDataImportExportFactory.) "RAW_DATA")]))

(defn- get-data [f]
  (try
    (with-open [zf (ZipFile. f)]
      (let [entry (.getEntry zf "data.txt")]
        (if entry
          (doall
            (line-seq (io/reader (.getInputStream zf entry)))))))))

(defn- bulk-survey
  [path bucket-name]
  (let [data (group-by #(nth (str/split % #"\t") 11) ;; 12th column contains the UUID
               (remove nil?
                 (distinct (mapcat get-data (fs/find-files path #".*\.zip$")))))]
    (doseq [k (keys data)
            :let [fname (format "/tmp/%s.zip" k)]]
      (fsc/zip fname ["data.txt" (str/join "\n" (data k))])
      (upload (io/file fname) bucket-name))))


(defn bulk-upload
  "Combines the parts, extracts and uploads the content of a zip file"
  [base-url unique-identifier filename upload-domain surveyId]
  (let [path (format "%s/%s" (get-path) unique-identifier)
        uname (str/upper-case filename)
        bucket-name (config/get-bucket-name upload-domain)]
    (combine path filename)
    (cleanup path)
    (cond
      (.endsWith uname "ZIP") (bulk-survey (unzip-file path filename) bucket-name) ; Extract and upload
      (.endsWith uname "XLSX") (raw-data (io/file path filename) base-url bucket-name surveyId) ; Upload raw data
      :else (upload (io/file path) bucket-name))))
