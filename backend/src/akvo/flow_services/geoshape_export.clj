;  Copyright (C) 2015,2019 Stichting Akvo (Akvo Foundation)
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

(ns akvo.flow-services.geoshape-export
  (:require [clojure.java.io :as io]
            [akvo.commons.gae :as gae]
            [akvo.commons.gae.query :as query]
            [akvo.commons.config :as config]
            [cheshire.core :as json]
            [taoensso.timbre :as timbre]
            [akvo.flow-services.util :as util]
            [clojure.string :as s])
  (:import [java.util UUID TimeZone]
           [org.waterforpeople.mapping.dataexport.service BulkDataServiceClient]
           [org.waterforpeople.mapping.dataexport ExportImportUtils]
           [java.text SimpleDateFormat]))

(defn parse-cascade-and-option
  "Mostly translated from org.waterforpeople.mapping.dataexport.GraphicalSurveySummaryExporter.cascadeCellValues
  and org.waterforpeople.mapping.dataexport.GraphicalSurveySummaryExporter.buildOptionString"
  [value field]
  (if (some-> value (s/starts-with? "["))
    (try
      (let [parsed-value (json/parse-string value true)]
        (s/join "|" (map (fn [m]
                           (let [field-value (get m field)
                                 code (get m :code)]
                             (cond
                               (nil? code) field-value
                               (= field-value code) field-value
                               :else (str code ":" field-value))))
                      parsed-value)))
      (catch Exception _ value))
    value))

(defn parse-value [question value]
  (case (:type question)
    :CASCADE (parse-cascade-and-option value :name)
    :OPTION (parse-cascade-and-option value :text)
    value))

(defn feature [question-id questions question-answers other-data]
  (let [geoshape (try
                   (-> question-answers
                         (get question-id)
                         json/parse-string
                         (get "features")
                         first)
                   (catch Exception e
                     (throw (Exception. (format "Error processing geoshape for Instance %s"
                                                (get other-data "Instance"))
                                        e))))
        geometry-type (get-in geoshape ["geometry" "type"])
        valid-polygon (and
                        (= "Polygon" geometry-type)
                        (every? #(> (count %) 2) (get-in geoshape ["geometry" "coordinates"])))]
    (when (or
            (#{"MultiPoint" "LineString"} geometry-type)
            valid-polygon)
      (->
        (reduce (fn [feature [id value]]
                  (if (not= id question-id)
                    (assoc-in feature ["properties" (get questions id)] (parse-value (get questions id) value))
                    feature))
                geoshape
                question-answers)
        (update "properties" merge other-data)))))

(defn sanitize [s]
  (or
    (some-> s
            (s/replace "\n" " ")
            (s/replace "\t" "")
            (s/trim))
    ""))

(defn duration-text [d]
  (if d
    (let [date-format (doto
                        (SimpleDateFormat. "HH:mm:ss")
                        (.setTimeZone (TimeZone/getTimeZone "GMT")))]
      (try
        (.format date-format (* 1000 d))
        (catch Exception _ "")))
    ""))

(defn format-instance-data [instance-data]
  (when instance-data
    {"Identifier"        (:surveyedLocaleIdentifier instance-data)
     "Display Name"      (:surveyedLocaleDisplayName instance-data)
     "Device identifier" (:deviceIdentifier instance-data)
     "Instance"          (str (:keyId instance-data))
     "Submission Date"   (ExportImportUtils/formatDateTime (:collectionDate instance-data))
     "Submitter"         (sanitize (:submitterName instance-data))
     "Duration"          (duration-text (:surveyalTime instance-data))}))

;; question-id: the geoshape question id
;; quesions:    map from question id -> question text
;; responses:   map from instance-id -> iteration -> question-id -> response text
;; instance-data: map from instance-id -> survey-instance-data
(defn build-features [question-id questions responses instance-data]
  (for [[instance-id iteration-answers] responses
        [iteration-number question-answers] (sort iteration-answers)]
    (feature question-id questions question-answers (assoc
                                                      (format-instance-data (get instance-data instance-id))
                                                      "Repeat No" (inc iteration-number)))))

(defn build-feature-collection* [form-id geoshape-question-id questions responses instance-data]
  {:type       "FeatureCollection"
   :properties {:formId       form-id
                :questionId   geoshape-question-id
                :questionText (get questions geoshape-question-id)}
   :features   (remove nil? (build-features geoshape-question-id questions responses instance-data))})


(defn fetch-responses [ds form-id]
  (let [responses (query/result ds
                                {:kind   "QuestionAnswerStore"
                                 :filter (query/= "surveyId" form-id)}
                                {:chunk-size 300})]
    (for [response responses]
      {:value       (or (.getProperty response "value")
                        (.getValue (.getProperty response "valueText")))
       :iteration   (or (.getProperty response "iteration") 0)
       :instance-id (.getProperty response "surveyInstanceId")
       :question-id (Long/parseLong (.getProperty response "questionID"))})))

(defn fetch-questions [ds form-id]
  (let [questions (query/result ds {:kind   "Question"
                                    :filter (query/= "surveyId" form-id)})]
    (for [question questions]
      {:id   (-> question .getKey .getId)
       :type (keyword (.getProperty question "type"))
       :text (.getProperty question "text")})))

(defn export-file [app-id form-id geoshape-question-id]
  (let [base-path (:base-path @config/settings)
        dir (io/file base-path
                     "reports"
                     app-id
                     (str (UUID/randomUUID)))
        filename (str "GEOSHAPE-" form-id "-" geoshape-question-id ".json")]
    (.mkdirs dir)
    (io/file dir filename)))

(defn fetch-instance-data [server-base api-key instance-id]
  (dissoc (bean (. (BulkDataServiceClient/fetchInstanceData instance-id server-base api-key) surveyInstanceData))
          :class :approvedFlag :questionAnswersStore :surveyCode))

(defn key-by [key-f val-f coll]
  (reduce (fn [result x]
            (assoc-in result (key-f x) (val-f x)))
          {}
          coll))

(def key-questions (partial key-by (juxt :id) :text))
(def key-responses (partial key-by (juxt :instance-id :iteration :question-id) :value))

(def key-instance-data (partial key-by (juxt :keyId) identity))

(defn build-feature-collection [form-id geoshape-question-id questions responses get-instance-data-fn]
  (let [questions (key-questions questions)
        responses (key-responses responses)
        instance-datas (key-instance-data (get-instance-data-fn (keys responses)))]
    (build-feature-collection* form-id
                               geoshape-question-id
                               questions
                               responses
                               instance-datas)))

(defn export [baseUrl api-key app-id form-id geoshape-question-id]
  (timbre/infof "Exporting geoshape app-id: %s - form-id: %s - geoshape-question-id: %s" app-id form-id geoshape-question-id)
  (gae/with-datastore [ds (util/datastore-spec app-id)]
    (let [form-id (Long/parseLong form-id)
          questions (fetch-questions ds form-id)
          responses (fetch-responses ds form-id)
          instance-data-fn (fn [instance-ids]
                             (map
                               (partial fetch-instance-data baseUrl api-key)
                               instance-ids))
          feature-collection (build-feature-collection form-id geoshape-question-id questions responses instance-data-fn)
          file (export-file app-id form-id geoshape-question-id)]
      (with-open [writer (io/writer file)]
        (json/generate-stream feature-collection writer)
        (timbre/infof "Successfully exported geoshape ")
        (timbre/debugf "Successfully exported geoshape to %s" file))
      file)))
