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
  (:import [org.quartz ObjectAlreadyExistsException]
           java.io.File)
  (:require [clojure.string :as str]
            [taoensso.timbre :refer (infof warnf)]
            [clojurewerkz.quartzite [conversion :as conversion]
                                    [jobs :as jobs]
                                    [scheduler :as scheduler]
                                    [triggers :as triggers]]
            [akvo.commons.config :as config]
            [akvo.flow-services.email :as email]
            [akvo.flow-services.geoshape-export :as geoshape])
  (:use [akvo.flow-services.exporter :only (export-report)]
        [akvo.flow-services.uploader :only (bulk-upload)]))

(defn gdpr-flow? [job-data]
  (= "true" (get-in job-data ["opts" "gdpr"])))

(defn create-report-in-flow [{:strs [baseURL exportType opts]}]
  {:method :post
   :url    (str baseURL "/reports")
   :body   {:report {:state      "IN_PROGRESS"
                     :user       (get opts "email")
                     :reportType exportType}}})

(defn finish-report-in-flow [{:strs [baseURL flow-create-result opts]} report-result]
  (if-let [exception (:exception report-result)]
    {:method :put
     :url    (str baseURL "/reports/" flow-create-result)
     :body   {:report {:state      "FINISHED_ERROR"
                       :user       (get opts "email")
                       :reportType "GEOSHAPE"
                       :message    (.getMessage exception)}}}
    (if (= "INVALID_PATH" (:report-path report-result))
      {:method :put
       :url    (str baseURL "/reports/" flow-create-result)
       :body   {:report {:state      "FINISHED_ERROR"
                         :user       (get opts "email")
                         :reportType "GEOSHAPE"
                         :message    "Error generating report"}}}
      {:method :put
       :url    (str baseURL "/reports/" flow-create-result)
       :body   {:report {:state      "FINISHED_SUCCESS"
                         :user       (get opts "email")
                         :reportType "GEOSHAPE"
                         :filename   (:report-path report-result)}}})))

(defn handle-create-report-in-flow [http-response]
  (cond
    (:exception http-response) [:abort (RuntimeException. "Error connecting to Flow" (:exception http-response))]
    (not= 200 (:status http-response)) [:abort (str "Flow returns error on report creation. HTTP code: " (:status http-response))]
    (-> http-response :body :report :id) [:continue {"flow-create-result" (-> http-response :body :report :id)}]
    :default [:abort "Flow did not return an id for the report"]))

(def cache (atom {}))

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
        questionId (get opts "questionId")
        appId (get opts "appId")
        report (if (= exportType "GEOSHAPE")
                 (geoshape/export appId surveyId questionId)
                 (export-report exportType baseURL surveyId opts))
        path (get-path report)]
    (swap! cache conj {{:id         id
                        :surveyId   surveyId
                        :questionId questionId
                        :baseURL    (config/get-domain baseURL)} path})
    (when (get opts "email")
      (if (= path "INVALID_PATH")
        (warnf "Could not generate report %s for surveyId %s" id surveyId)
        (email/send-report-ready (get opts "email")
                                 (get opts "locale" "en")
                                 (format "%s/report/%s"
                                         (get opts "flowServices")
                                         path))))
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
      (doseq [k (keys @cache) :when (and (= (:surveyId k) (str sid))
                                         (or (= (:baseURL k) baseURL)
                                             (= (:baseURL k) alias)))]
        (infof "Invalidating: %s" k)
        (swap! cache dissoc k)))
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
