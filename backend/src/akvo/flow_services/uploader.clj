;  Copyright (C) 2013-2015 Stichting Akvo (Akvo Foundation)
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
           org.waterforpeople.mapping.dataexport.RawDataSpreadsheetImporter
           java.util.zip.ZipFile
           java.net.URLEncoder
           [org.apache.poi.ss.usermodel Cell Row Sheet]
           [com.google.appengine.api.datastore Entity Query])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :as set]
            [me.raynes.fs :as fs]
            [me.raynes.fs.compression :as fsc]
            [aws.sdk.s3 :as s3]
            [clj-http.client :as http]
            [akvo.commons.config :as config]
            [akvo.commons.gae :as gae]
            [akvo.commons.gae.query :as query]
            [taoensso.timbre :as timbre :refer (debugf infof errorf)]
            [akvo.flow-services.util :as util]))


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

(defn combine [directory filename]
  (let [f (io/file (format "%s/%s" directory filename))
        parts (get-parts directory)]
    (if (seq parts)
      (with-open [os (io/output-stream f)]
        (doseq [p parts]
          (io/copy p os))))))

(defn- cleanup [path]
  (doseq [file (get-parts path)]
    (fs/delete file)))

(defn- unzip
  "Takes the path to a zipfile source and unzips it to target-dir."
  ([source]
   (unzip source (name source)))
  ([source target-dir]
   (with-open [zip (ZipFile. (fs/file source))]
     (let [entries (enumeration-seq (.entries zip))
           target-dir-as-file (fs/file target-dir)
           target-file #(fs/file target-dir-as-file (str %))]
       (doseq [entry entries :when (not (.isDirectory ^java.util.zip.ZipEntry entry))
               :let [f (target-file entry)]]
         (when-not (-> f .getCanonicalPath (.startsWith (.getCanonicalPath target-dir-as-file)))
           (throw (ex-info "Expanding entry would be created outside target dir"
                           {:entry            entry
                            :entry-final-path f
                            :target-dir       target-dir})))
         (fs/mkdirs (fs/parent f))
         (io/copy (.getInputStream zip entry) f))))
   target-dir))

(defn- unzip-file [directory filename]
  (let [dest (io/file (format "%s/%s" directory "zip-content"))
        source (io/file (format "%s/%s" directory filename))]
    (if-not (.exists ^File dest)
      (.mkdirs dest))
    (unzip source dest)))

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
  (let [creds (select-keys (config/find-config bucket-name) [:access-key :secret-key])
        obj-key (get-key f)]
    (debugf "Uploading file: %s - bucket: %s" f bucket-name)
    (if (.startsWith obj-key "images/")
      (s3/put-object creds bucket-name obj-key f {} (s3/grant :all-users :read))
      (s3/put-object creds bucket-name obj-key f))))

(defn add-message [bucket-name action obj-id content]
  (let [msg {"actionAbout" action
             "objectId" (if obj-id (Long/parseLong obj-id))
             ;; Return only first 500 xters of message due to GAE String limitation
             "shortMessage" (if (nil? content)
                              ""
                              (subs content 0 (min 499 (count content))))}]
    (gae/with-datastore [ds (util/datastore-spec bucket-name)]
      (gae/put! ds "Message" msg))))

(defn- retrieve-question-ids [bucket-name survey-id]
  (gae/with-datastore [ds (util/datastore-spec bucket-name)]
    (for [question (seq (query/result ds {:kind      "Question"
                                          :filter    (query/= "surveyId" (Long/valueOf survey-id))
                                          :keys-only true}))]
      (.getId (.getKey question)))))

(defn- get-file-ids [sheet]
  (->> (.getRow sheet 0)
       (map (fn [cell] (second (re-find #"(\d+)|" (.getStringCellValue cell)))))
       (filter some?)
       (map #(Long/parseLong %))))

(defn- validate-question-ids
  "validate whether file was uploaded against a wrong survey
   based on question ids it contains. Assume that if none of
   the ids in the file can be matched with any in the survey, then
   file has been uploaded against the wrong survey.  If only some
   are not matched, none matched ids possibly indicate deleted questions"
  [f importer bucket-name surveyId]
  (let [datastore-ids (into #{} (retrieve-question-ids bucket-name surveyId))
        file-ids (into #{} (get-file-ids (.getDataSheet importer f)))
        invalid-ids (set/difference file-ids datastore-ids)
        file-name (.getName f)]
    (when
      (and (not (empty? invalid-ids))(= invalid-ids file-ids))
      ;; the -2 below is due to convention use RawDataSpreadsheetImporter.validate()
      {-2 (format "The uploaded file '%s' does not match the selected survey" file-name)})))

(defn- validate-raw-data [f importer bucket-name surveyId]
  (let [invalid-question-errors (validate-question-ids f importer bucket-name surveyId)
        invalid-header-errors (when (empty? invalid-question-errors)(.validate importer f))]
    (if (not (empty? invalid-question-errors))
      invalid-question-errors
      (when (not (empty? invalid-header-errors)) invalid-header-errors))))

(defn- raw-data
  [f base-url bucket-name surveyId]
  (let [importer (RawDataSpreadsheetImporter.)
        errors (validate-raw-data f importer bucket-name surveyId)]
    (if (not (empty? errors))
      (errorf "Errors in raw data upload - baseURL: %s - file: %s - surveyId: %s - errors: %s" base-url f surveyId errors))
    (if (empty? errors)
      (.executeImport importer f base-url (config/get-criteria bucket-name surveyId))
      (add-message bucket-name "importData" surveyId
                   (format "Invalid RAW DATA file: %s - Errors: %s" (.getName f) (str/join ", " (vals errors)))))))

(defn- get-data [f]
  (try
    (with-open [zf (ZipFile. f)]
      (let [entry (.getEntry zf "data.txt")]
        (if entry
          (doall
            (line-seq (io/reader (.getInputStream zf entry)))))))))

(defn- filter-files
  [fcoll]
  (->> fcoll
    (remove #(.contains (.getAbsolutePath %) "__MACOSX"))
    (remove #(.contains (.getName %) "wfpGenerated"))
    (remove #(zero? (.length %)))))

(defn get-format
  "Determine whether the zip file contains JSON or TSV data"
  [f]
  (with-open [zf (ZipFile. f)]
    (cond
      (.getEntry zf "data.json") :json
      (.getEntry zf "data.txt") :tsv
      :else nil)))

(defn- get-zip-files
  [path]
  (filter-files (fs/find-files path #".*\.zip$")))

(defn- get-images
  [path]
  (filter-files (fs/find-files path #".*\.(jpg|JPG|jpeg|JPEG)$")))

(defn query-string
  [params]
  (->> params
       (map (fn [[k v]]
              (str (name k) "=" (java.net.URLEncoder/encode (str v) "UTF-8"))))
       (str/join "&")))

(defn notify-gae [server params]
  (let [max-retries 10
        sleep-time 60000
        success? (loop [attempts 1]
                   (when (<= attempts max-retries)
                     (or (try
                           (debugf "Notifying %s (Attempt #%s)" server  attempts)
                           (http/get (format "https://%s/processor?%s" server (query-string params)))
                           (catch Exception e
                             (infof "Failed to notify %s. Retry in %s msecs" server sleep-time)))
                         (do
                           (Thread/sleep sleep-time)
                           (recur (inc attempts))))))]
    (if success?
      (infof "Successfully notified %s" server)
      (errorf "Failed to notify %s after %s attempts" server max-retries))))

(defn- bulk-survey
  [path bucket-name filename]
  (infof "Bulk upload - path: %s - bucket: %s - file: %s" path bucket-name filename)
  (let [files (group-by get-format (get-zip-files path))
        tsv-data (group-by #(nth (str/split % #"\t") 11) ;; 12th column contains the UUID
                           (remove nil? (distinct (mapcat get-data (:tsv files)))))
        server (:domain (config/find-config bucket-name))]
    (doseq [file (:json files)]
      (upload file bucket-name)
      (future (notify-gae server {"action" "submit" "fileName" (.getName file)})))
    (doseq [k (keys tsv-data)
            :let [fname (format "/tmp/%s.zip" k)
                  fzip (io/file fname)]]
      (fsc/zip fzip ["data.txt" (str/join "\n" (tsv-data k))])
      (upload fzip bucket-name)
      (future (notify-gae server {"action" "submit" "fileName" (.getName fzip)})))
    (doseq [f (get-images path)]
      (upload f bucket-name))
    (add-message bucket-name "bulkUpload" nil (format "File: %s processed" filename))))


(defn bulk-upload
  "Combines the parts, extracts and uploads the content of a zip file"
  [base-url unique-identifier filename upload-domain surveyId]
  (let [path (format "%s/%s" (get-path) unique-identifier)
        uname (str/upper-case filename)
        bucket-name (config/get-bucket-name upload-domain)]
    (combine path filename)
    (cleanup path)
    (cond
      (.endsWith uname "ZIP") (bulk-survey (unzip-file path filename) bucket-name filename) ; Extract and upload
      (.endsWith uname "XLSX") (raw-data (io/file path filename) base-url bucket-name surveyId) ; Upload raw data
      :else (upload (io/file path) bucket-name))))
