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
            [clj-http.client :as http])
  (:use [akvo.flow-services.exporter :only (export-report)]
        [akvo.flow-services.uploader :only (bulk-upload)]))

(defn gdpr-flow? [job-data]
  (= "true" (get-in job-data ["opts" "gdpr"])))

(defn create-report-in-flow [{:strs [baseURL exportType opts]}]
  {:method      :post
   :url         (str baseURL "/reports")
   :form-params {:report {:state      "IN_PROGRESS"
                          :user       (get opts "email")
                          :reportType exportType}}})

(defn expect-200 [http-response]
  (cond
    (:error http-response) {:error {:message "Error connecting to Flow" :cause (:error http-response)}}
    (not= 200 (:status http-response)) {:error {:message (str "Flow returns error on report creation. HTTP code: " (:status http-response))}}
    :default http-response))

(defn unwrap-throwing [{:keys [error] :as v}]
  (if error
    (throw (if-let [cause (:cause error)]
             (RuntimeException. (:message error) cause)
             (RuntimeException. (:message error))))
    v))

(defn if-ok [{:keys [error] :as v} f]
  (if error v (f v)))

(defn handle-create-report-in-flow [http-response]
  (-> http-response
      expect-200
      (if-ok (fn [ok]
               (if-let [flow-id (-> ok :body :report :id)]
                 flow-id
                 {:error "Flow did not return an id for the report"})))))

(defmacro wrap-exceptions [body]
  `(try
     ~body
     (catch Exception e# {:error {:cause e#}})))

(defn invalid-report? [report-path]
  (= "INVALID_PATH" report-path))

(defn user-friendly-message [error]
  (or (:message error)
      (some-> error :cause .getMessage)))

(defn finish-report-in-flow [{:strs [baseURL opts]} flow-id report-result]
  (if-let [error (:error report-result)]
    {:method      :put
     :url         (str baseURL "/reports/" flow-id)
     :form-params {:report {:state      "FINISHED_ERROR"
                            :user       (get opts "email")
                            :reportType "GEOSHAPE"
                            :message    (user-friendly-message error)}}}
    (if (invalid-report? report-result)
      {:method      :put
       :url         (str baseURL "/reports/" flow-id)
       :form-params {:report {:state      "FINISHED_ERROR"
                              :user       (get opts "email")
                              :reportType "GEOSHAPE"
                              :message    "Error generating report"}}}
      {:method      :put
       :url         (str baseURL "/reports/" flow-id)
       :form-params {:report {:state      "FINISHED_SUCCESS"
                              :user       (get opts "email")
                              :reportType "GEOSHAPE"
                              :filename   report-result}}})))

(defn send-http-json! [request]
  (wrap-exceptions
    (http/request (merge {:as               :json
                          :content-type     :json
                          :throw-exceptions false}
                         request))))

(defn open-report-in-flow [job-data]
  (-> job-data create-report-in-flow send-http-json! handle-create-report-in-flow unwrap-throwing))

(defn close-report-in-flow [flow-id job-data report]
  (-> (finish-report-in-flow job-data flow-id report) send-http-json! expect-200 unwrap-throwing))

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
    path))

(defn gdpr-email [{:strs [opts]}]
  (email/send-gdpr-report-ready (get opts "email")
                                (get opts "locale" "en")))

(defn old-email [{:strs [opts]} report]
  (email/send-report-ready (get opts "email")
                           (get opts "locale" "en")
                           (format "%s/report/%s"
                                   (get opts "flowServices")
                                   report)))

(defn do-export [job-data]
  (if (gdpr-flow? job-data)
    (let [flow-data (open-report-in-flow job-data)
          report (wrap-exceptions (run-report job-data))]
      (close-report-in-flow flow-data job-data report)
      (when-not (invalid-report? report)
        (gdpr-email job-data)))
    (let [report (run-report job-data)]
      (when-not (invalid-report? report)
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
