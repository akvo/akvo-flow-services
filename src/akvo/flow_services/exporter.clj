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
;  The full license text can also be seen at <http://www.gnu.org/licenses/agpl.html>..

(ns akvo.flow-services.exporter
  (:require [clojure.java.io :as io]
    [clojure.walk :refer (stringify-keys)]
    [akvo.commons.config :as config]
    [taoensso.timbre :as timbre :refer (infof)]
    [cheshire.core :as json]
    [akvo.commons.gae :as gae]
    [akvo.commons.gae.query :as query])
  (:import java.io.File
    java.util.UUID
    org.waterforpeople.mapping.dataexport.SurveyDataImportExportFactory))

(defn- get-file-extension [t]
  (cond
    (= t "SURVEY_FORM") "xls"
    (= t "RAW_DATA_TEXT") "txt"
    :else "xlsx"))

(defn- get-path [base-url]
  (let [base-path (:base-path @config/settings)
        bn (config/get-bucket-name base-url)]
    (format "%s/%s/%s/%s" base-path "reports" bn (UUID/randomUUID))))

(defn- get-file [type base-url id]
  (let [path (get-path base-url)]
    (.mkdirs (io/file path))
    (io/file (format "%s/%s-%s.%s" path type id (get-file-extension type)))))

(defn ^File export-report
  "Exports a report using SurveyDataImportExportFactory based on the report type.
   Returns the reference to the saved file."
  [type base-url id options]
  (let [exporter (.getExporter (SurveyDataImportExportFactory.) type)
        file (get-file type base-url id)
        options (assoc options
                       "maxDataReportRows"
                       (:max-data-report-rows @config/settings))
        criteria (-> "uploadUrl"
                     options
                     config/get-bucket-name
                     (config/get-criteria id)
                     stringify-keys)]
    (infof "Exporting report baseURL: %s - criteria: %s - options: %s" base-url criteria options)
    (.export exporter criteria file base-url options)
    file))

(def ignore-properties #{"ancestorIds" "createUserId" "lastUpdateUserId"})

(defn get-properties
  "Convert an entity to a map and strip unneeded properties"
  [entity]
  (if (nil? entity)
    {}
    (conj { "keyId" (.getId (.getKey entity))} (.getProperties entity))))

(defn retrieve-question-options
  [ds question-ids]
  (map get-properties (query/result ds {:kind "QuestionOption"
                                        :filter (query/in "questionId" question-ids)})))

(defn retrieve-questions
  [ds qgroup-ids]
  (when (not-empty qgroup-ids)
    (map get-properties (query/result ds {:kind "Question"
                                          :filter (query/in "questionGroupId" qgroup-ids)}))))

(defn batch-retrieve-entities
  "A function to batch retrieve entities from the datastore when
   when using the IN query.  Takes the datatore reference, a list
   of values that will be used for the IN queries and the function
   to execute the query.  The function provided should accept two
   parameters, the datastore reference and a collection of values
   for the parameter which will be filtered for in the datastore"
  [ds in-filter-list f]
  (let [first (take 30 in-filter-list)
        rest (nthnext in-filter-list 30)]
    (if (empty? rest)
      (f ds first)
      (conj [] (f ds first) (batch-retrieve-entities ds rest f)))))

(defn retrieve-question-groups
  [ds form-ids]
  (when (not-empty form-ids)
    (map get-properties (query/result ds {:kind "QuestionGroup"
                                          :filter (query/in "surveyId" form-ids)}))))

(defn retrieve-forms
  [ds survey-id]
  (map get-properties (query/result ds {:kind "Survey"
                                        :filter (query/= "surveyGroupId" survey-id)})))

(defn retrieve-survey
  [ds survey-id]
  (get-properties (query/entity ds "SurveyGroup" survey-id)))

(defn get-keyid
  [entity]
    (get entity "keyId"))

(defn assemble-survey-definition
  "Assemble survey definition from components"
  [ds survey-id]
  (let [survey (retrieve-survey ds survey-id)
        forms (retrieve-forms ds survey-id)
        form-ids (map get-keyid forms)
        question-groups (retrieve-question-groups ds form-ids)
        qgroup-ids (map get-keyid question-groups)
        questions (batch-retrieve-entities ds qgroup-ids retrieve-questions)
        question-ids (map get-keyid (filter #(= "OPTION" (get % "type")) questions))
        question-options (batch-retrieve-entities ds question-ids retrieve-question-options)]
    form-ids))

(defn export-survey-definition
  "Export survey definition as a JSON string"
  [gae-app-id survey-id]
  (let [settings @config/settings
        {:keys [domain service-account-id private-key-file]} (config/find-config gae-app-id)]
    (gae/with-datastore [ds {:hostname domain
                             :service-account-id service-account-id
                             :private-key-file private-key-file
                             :port 443}]
        (json/generate-string (assemble-survey-definition ds survey-id)))))
