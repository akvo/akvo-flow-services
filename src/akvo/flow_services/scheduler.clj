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

(ns akvo.flow-services.scheduler
  (:refer-clojure :exclude [key])
  (:import [org.quartz JobExecutionContext Scheduler ObjectAlreadyExistsException]
           java.io.File
           java.util.UUID)
  (:require [clojure.string :as str]
            [taoensso.timbre :as timbre :refer (infof warnf)]
            [clojurewerkz.quartzite [conversion :as conversion]
                                    [jobs :as jobs]
                                    [scheduler :as scheduler]
                                    [triggers :as triggers]]
            [akvo.flow-services.config :as config]
            [akvo.flow-services.email :as email])
  (:use [akvo.flow-services.exporter :only (export-report)]
        [akvo.flow-services.uploader :only (bulk-upload)]))

(def cache (ref {}))

(def in-flight-reports (atom {}))

(defn- valid-report? [report-path]
  (boolean
    (and (.exists report-path)
      (pos? (.length report-path)))))

(defn- get-path [report-file]
  (if (valid-report? report-file)
    (str/join "/" (take-last 3 (str/split (.getAbsolutePath ^File report-file) #"/")))
    "INVALID_PATH"))

(jobs/defjob ExportJob [job-data]
  (let [{:strs [baseURL exportType surveyId opts id]} (conversion/from-job-data job-data)
        report (export-report exportType baseURL surveyId opts)
        path (get-path report)]
    (dosync
      (alter cache conj {{:id id
                          :surveyId surveyId
                          :baseURL (config/get-domain baseURL)} path}))
    (when (get opts "email")
      (if (= path "INVALID_PATH")
        (warnf "Could not generate report %s for surveyId %s" id surveyId)
        (email/send-report-ready (get @in-flight-reports id)
                                 (get opts "locale" "en")
                                 (format "%s/report/%s"
                                         (get opts "flowServices")
                                         path)))
      (swap! in-flight-reports dissoc id))
    (scheduler/delete-job (jobs/key id))))


(jobs/defjob BulkUploadJob [job-data]
  (let [{:strs [baseURL uniqueIdentifier filename uploadDomain surveyId id]} (conversion/from-job-data job-data)]
    (bulk-upload baseURL uniqueIdentifier filename uploadDomain surveyId)
    (scheduler/delete-job (jobs/key id))))

(defn- report-id [m]
  (format "id%s" (hash m)))

(defn- get-job [job-type id params]
  (jobs/build
    (jobs/of-type job-type)
    (jobs/using-job-data (conj params {:id id} ))
    (jobs/with-identity (jobs/key id))))

(defn- get-trigger [id]
  (triggers/build
    (triggers/with-identity (triggers/key id))
    (triggers/start-now)))

(defn schedule-job [job-type id params]
  (let [job (get-job job-type id params)
        trigger (get-trigger id)]
    (try
      (scheduler/maybe-schedule job trigger)
      (catch ObjectAlreadyExistsException _))
    (when-let [email (get-in params ["opts" "email"])]
      (swap! in-flight-reports update-in [id] (fnil conj #{}) email))
    {:status "OK"
     :message "PROCESSING"}))

(defn- get-report-by-id [id]
  (let [found (filter #(= id (:id %)) (keys @cache))]
    (if (seq found)
      (@cache (nth found 0)))))

(defn invalidate-cache
  "Invalidates (removes) a given file from the in memory cache"
  [params]
  (let [baseURL (config/get-domain (params "baseURL"))
        alias (config/get-alias baseURL)]
    (doseq [sid (params "surveyIds")]
      (dosync
        (doseq [k (keys @cache) :when (and (= (:surveyId k) (str sid))
                                           (or (= (:baseURL k) baseURL)
                                               (= (:baseURL k) alias)))]
          (infof "Invalidating: %s" k)
          (alter cache dissoc k))))
    "OK"))

(defn generate-report
  "Returns the cached report for the given parameters, or schedules the report for generation"
  [criteria]
  (if-let [file (get-report-by-id (report-id criteria))]
    (if (= file "INVALID_PATH")
      (do
        (invalidate-cache {"baseURL" (criteria "baseURL")
                           "surveyIds" [(criteria "surveyId")]})
        {:status "ERROR"
         :message "_error_generating_report"})
      {:status "OK"
       :file file})
    (schedule-job ExportJob (report-id criteria) criteria)))

(defn process-and-upload
  "Schedules a bulk upload process"
  [params]
  (schedule-job BulkUploadJob (:uniqueIdentifier params) params))
