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

(ns akvo.flow-services.scheduler
  (:refer-clojure :exclude [key])
  (:import [org.quartz JobExecutionContext Scheduler ObjectAlreadyExistsException]
           java.io.File
           java.util.UUID)
  (:require [clojure.string :as string :only (split join)]
            [clojurewerkz.quartzite [conversion :as conversion]
                                    [jobs :as jobs]
                                    [scheduler :as scheduler]
                                    [triggers :as triggers]]
            [akvo.flow-services.config :as config])
  (:use [akvo.flow-services.exporter :only (export-report)]
        [akvo.flow-services.uploader :only (bulk-upload)]))

(def cache (ref {}))

(defn- valid-report? [report-path]
  (if (and (.exists ^File report-path)
           (> (.length ^File report-path) 0))
    true false))

(defn- get-path [report-file]
  (if (valid-report? report-file)
    (string/join "/" (take-last 3 (string/split (.getAbsolutePath ^File report-file) #"/")))
    "INVALID_PATH"))

(jobs/defjob ExportJob [job-data]
  (let [{:strs [baseURL exportType surveyId opts id]} (conversion/from-job-data job-data)
        report (export-report exportType baseURL surveyId opts)
        path (get-path report)]
    (dosync
      (alter cache conj {{:id id
                          :surveyId surveyId
                          :baseURL baseURL} path}))
    (scheduler/delete-job (jobs/key id))))


(jobs/defjob BulkUploadJob [job-data]
  (let [{:strs [baseURL uniqueIdentifier filename uploadDomain surveyId id]} (conversion/from-job-data job-data)]
    (bulk-upload baseURL uniqueIdentifier filename uploadDomain surveyId)
    (scheduler/delete-job (jobs/key id))))

(defn- get-executing-jobs-by-key [key]
  (filter #(= (.. ^JobExecutionContext % (getJobDetail) (getKey)) (jobs/key key))
          (.getCurrentlyExecutingJobs ^Scheduler @scheduler/*scheduler*)))

(defn- job-executing? [key]
  (if (seq (get-executing-jobs-by-key key)) true false))

(defn- report-id [m]
  (format "id%s" (hash (str m))))

(defn- get-job [job-type id params]
  (jobs/build
    (jobs/of-type job-type)
    (jobs/using-job-data (conj params {"id" id} ))
    (jobs/with-identity (jobs/key id))))

(defn- get-trigger [id]
  (triggers/build
    (triggers/with-identity (triggers/key id))
    (triggers/start-now)))

(defn- schedule-job [job-type id params]
  (let [job (get-job job-type id params)
        trigger (get-trigger id)]
    (try
      (scheduler/maybe-schedule job trigger)
      (catch ObjectAlreadyExistsException _))
    {"status" "OK"
     "message" "PROCESSING"}))

(defn- get-report-by-id [id]
  (let [found (filter #(= id (:id %)) (keys @cache))]
    (if (seq found)
      (@cache (nth found 0)))))

(defn invalidate-cache
  "Invalidates (removes) a given file from the in memory cache"
  [params]
  (let [baseURL (params "baseURL")
        alias (@config/instance-alias baseURL)]
    (doseq [sid (params "surveyIds")]
      (dosync
        (doseq [k (keys @cache) :when (and (= (:surveyId k) (str sid))
                                           (or (= (:baseURL k) baseURL)
                                               (= (:baseURL k) alias)))]
          (prn "Invalidating: " k)
          (alter cache dissoc k))))
    "OK"))

(defn generate-report
  "Returns the cached report for the given parameters, or schedules the report for generation"
  [params]
  (if-let [file (get-report-by-id (report-id params))]
    (if (= file "INVALID_PATH")
      (do
        (invalidate-cache {"baseURL" (params "baseURL")
                           "surveyIds" [(params "surveyId")]})
        {"status" "ERROR"
         "message" "_error_generating_report"})
      {"status" "OK"
       "file" file})
    (schedule-job ExportJob (report-id params) params)))

(defn process-and-upload
  "Schedules a bulk upload process"
  [params]
  (schedule-job BulkUploadJob (params "uniqueIdentifier") params))