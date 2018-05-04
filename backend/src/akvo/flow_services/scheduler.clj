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
            [akvo.flow-services.geoshape-export :as geoshape]
            [akvo.flow-services.error :as e]
            [akvo.flow-services.util :as util])
  (:use [akvo.flow-services.exporter :only (export-report)]
        [akvo.flow-services.uploader :only (bulk-upload)]))

(defn gdpr-flow? [job-data]
  (= "true" (get-in job-data ["opts" "gdpr"])))

(defn create-report-in-flow [{:strs [baseURL exportType opts surveyId]}]
  {:method      :post
   :url         (str baseURL "/rest/reports")
   :form-params {:report {:state      "IN_PROGRESS"
                          :user       (get opts "email")
                          :formId surveyId
                          :reportType exportType}}})

(defn expect-200 [http-response]
  (if (e/ok? http-response)
    (if (not= 200 (:status http-response))
      (e/error {:message (str "Flow returns error on report creation. HTTP code: " (:status http-response))})
      http-response)
    http-response))

(defn handle-create-report-in-flow [http-response]
  (-> http-response
      expect-200
      (e/if-ok (fn [ok]
                 (if-let [flow-id (-> ok :body :report :keyId)]
                   flow-id
                   (e/error {:message "Flow did not return an id for the report"}))))))

(defn report-full-url [opts report]
  (format "%s/report/%s"
          (get opts "flowServices")
          report))

(defn finish-report-in-flow [{:strs [baseURL opts exportType surveyId]} flow-id report-result]
  (let [report (if (e/ok? report-result)
                 {:state    "FINISHED_SUCCESS"
                  :filename (report-full-url opts report-result)}
                 {:state   "FINISHED_ERROR"
                  :message (e/user-friendly-message report-result)})]
    {:method      :put
     :url         (str baseURL "/rest/reports/" flow-id)
     :form-params {:report
                   (assoc report
                     :keyId flow-id
                     :user (get opts "email")
                     :formId surveyId
                     :reportType exportType)}}))
(defn open-report-in-flow [job-data]
  (-> job-data
      create-report-in-flow
      (#(util/send-http-json! job-data %))
      handle-create-report-in-flow
      e/unwrap-throwing))

(defn close-report-in-flow [flow-id job-data report]
  (-> (finish-report-in-flow job-data flow-id report)
      (#(util/send-http-json! job-data %))
      expect-200
      e/unwrap-throwing))

(def cache (atom {}))

(defn- valid-report? [report-path]
  (boolean
    (and (.exists report-path)
         (pos? (.length report-path)))))

(defn- get-path [report-file]
  (if (valid-report? report-file)
    (str/join "/" (take-last 3 (str/split (.getAbsolutePath ^File report-file) #"/")))
    "INVALID_PATH"))

(defn run-report [{:strs [baseURL exportType surveyId opts id]}]
  (let [questionId (get opts "questionId")
        report (if (= exportType "GEOSHAPE")
                 (geoshape/export (get opts "appId") surveyId questionId)
                 (export-report exportType baseURL surveyId opts))
        path (get-path report)]
    (swap! cache conj {{:id         id
                        :surveyId   surveyId
                        :questionId questionId
                        :baseURL    (config/get-domain baseURL)} path})
    (scheduler/delete-job (jobs/key id))
    (if (= "INVALID_PATH" path)
      (e/error {:message "Error generating report"})
      path)))

(defn gdpr-email [{:strs [opts]}]
  (email/send-gdpr-report-ready (get opts "email")
                                (get opts "locale" "en")))

(defn old-email [{:strs [opts]} report]
  (email/send-report-ready (get opts "email")
                           (get opts "locale" "en")
                           (report-full-url opts report)))

(defn do-export [job-data]
  (if (gdpr-flow? job-data)
    (let [flow-data (open-report-in-flow job-data)
          report (e/wrap-exceptions (run-report job-data))]
      (close-report-in-flow flow-data job-data report)
      (when (e/ok? report)
        (gdpr-email job-data)))
    (let [report (run-report job-data)]
      (when (e/ok? report)
        (old-email job-data report)))))

(jobs/defjob ExportJob [job-data]
  (do-export (conversion/from-job-data job-data)))

(jobs/defjob BulkUploadJob [job-data]
  (let [{:strs [baseURL uniqueIdentifier filename uploadDomain surveyId id]} (conversion/from-job-data job-data)]
    (bulk-upload baseURL uniqueIdentifier filename uploadDomain surveyId)
    (scheduler/delete-job (jobs/key id))))

(defn- report-id [m]
  (format "id%s" (hash m)))

(defn- get-job [job-type id params]
  (jobs/build
    (jobs/of-type job-type)
    (jobs/using-job-data (conj params {:id id}))
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
    {:status  "OK"
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
        (invalidate-cache {"baseURL"   (criteria "baseURL")
                           "surveyIds" [(criteria "surveyId")]})
        {:status  "ERROR"
         :message "_error_generating_report"})
      {:status "OK"
       :file   file})
    (schedule-job ExportJob (report-id criteria) criteria)))

(defn process-and-upload
  "Schedules a bulk upload process"
  [params]
  (schedule-job BulkUploadJob (:uniqueIdentifier params) params))
