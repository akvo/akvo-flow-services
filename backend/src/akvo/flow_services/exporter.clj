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
            [clojure.walk :refer (stringify-keys keywordize-keys)]
            [akvo.commons.config :as config]
            [taoensso.timbre :as timbre :refer (infof)]

            [cheshire.core :as json]
            [akvo.commons.gae :as gae]
            [akvo.commons.gae.query :as query]
            [akvo.flow-services.util :as util])
  (:import java.io.File
    java.util.UUID
    org.waterforpeople.mapping.dataexport.SurveyDataImportExportFactory))

(defn- get-file-extension [t]
  (cond
    (= t "SURVEY_FORM") "xls"
    (= t "RAW_DATA_TEXT") "txt"
    :else "xlsx"))

(defn get-report-directory []
  (format "%s/%s" (:base-path @config/settings) "reports"))

(defn- get-path [base-url]
  (let [bn (config/get-bucket-name base-url)]
    (format "%s/%s/%s" (get-report-directory) bn (UUID/randomUUID))))

(defn- get-caddisfly-tests-file-url [options]
  "Retrieve the URL for caddisfly tests file"
  (let [bucket (get options "appId")]
    (:caddisfly-tests-file-url (config/find-config bucket))))

(defn- get-file [type base-url id]
  (let [path (get-path base-url)]
    (.mkdirs (io/file path))
    (io/file (format "%s/%s-%s.%s" path type id (get-file-extension type)))))

(defn safe-to-log-criteria [criteria]
  (select-keys criteria ["service-account-id" "s3bucket" "app-id" "domain" "surveyId" "alias" "flowServicesVersion"]))

(defn safe-to-log-options [options]
  (select-keys options ["exportMode" "lastCollection" "imgPrefix" "uploadUrl" "appId" "flowServices" "maxDataReportsRows" "caddisflyTestsFileUrl" "from" "to"]))

(defn ^File export-report
  "Exports a report using SurveyDataImportExportFactory based on the report type.
   Returns the reference to the saved file."
  [type base-url id options]
  (let [exporter (.getExporter (SurveyDataImportExportFactory.) type)
        file (get-file type base-url id)
        options (assoc options
                       "maxDataReportRows"
                       (:max-data-report-rows @config/settings)
                       "caddisflyTestsFileUrl"
                       (get-caddisfly-tests-file-url options))
        criteria (-> (or (get options "appId")
                         (get options "uploadUrl"))
                     (config/get-criteria id)
                     stringify-keys
                     (assoc "flowServicesVersion" (System/getenv "APP_VERSION")))]
    (infof "Exporting report baseURL: %s - criteria: %s - options: %s" base-url (safe-to-log-criteria criteria) (safe-to-log-options options))
    (.export exporter criteria file base-url options)
    file))

(defn map-by-keyid
  "Map elements from a source collection, grouped together as lists to an element
   in a destination collection.  The set of sub elements grouped as a single destination
   collection element is determined by matching a property in the source collection to
   the keyId element in the destination list
   e.g list 1 [{:keyId 1 :name \"first\"}, {:keyId 2 \"second\"}]
       list 2 [{:item 9 :parent 1}, {:item 87 :parent 2}, {:item 98 :parent 1}]
       conjoined list [{:keyId 1
                        :name \"first\"
                        :children [{:item 1 :parent 1},
                                   {:item 98 :parent 1}]
                       },
                       {:keyId 2
                        :name \"second\"
                        :children [{:item 87 :parent 2}]"
  [to-key to-coll grouping-key from-coll]
  (let [sorted-to-coll (sort-by :keyId to-coll)
        sorted-key-ids (map :keyId sorted-to-coll)
        sub-lists (map (group-by grouping-key from-coll) sorted-key-ids)
        ]
    (map #(assoc %1 to-key %2) sorted-to-coll sub-lists)))

(defn map-survey-forms
  [survey forms]
  (when (not-empty survey)
    (first (map-by-keyid :forms (conj [] survey) :surveyGroupId forms))))

(defn map-question-options
  [questions options]
  (map
   (fn [question]
     (if (= (:type question) "OPTION")
       question
       (dissoc question :options)))
   (map-by-keyid :options questions :questionId options)))

(def ignore-properties #{"ancestorIds" "createUserId" "lastUpdateUserId"})

(defn get-properties
  "Convert an entity to a map and strip unneeded properties"
  [entity]
  (if (nil? entity)
    {}
    (keywordize-keys (conj { "keyId" (.getId (.getKey entity))} (.getProperties entity)))))

(defn retrieve-question-options
  [ds question-ids]
  (when (not-empty question-ids)
    (map get-properties (query/result ds {:kind "QuestionOption"
                                          :filter (query/in "questionId" question-ids)}))))

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
  (flatten
    (let [first (take 30 in-filter-list)
          rest (nthnext in-filter-list 30)]
      (if (empty? rest)
        (f ds first)
        (conj [] (f ds first) (batch-retrieve-entities ds rest f))))))

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

(defn assemble-survey-definition
  "Assemble survey definition from components"
  [ds survey-id]
  (let [survey (retrieve-survey ds survey-id)
        forms (retrieve-forms ds survey-id)
        form-ids (map :keyId forms)
        question-groups (retrieve-question-groups ds form-ids)
        qgroup-ids (map :keyId question-groups)
        questions (batch-retrieve-entities ds qgroup-ids retrieve-questions)
        question-ids (map :keyId (filter #(= "OPTION" (:type %)) questions))
        question-options (batch-retrieve-entities ds question-ids retrieve-question-options)]
  (->> (map-question-options questions question-options)
       (map-by-keyid :questions forms :surveyId)
       (map-survey-forms survey))))

(defn export-survey-definition
  "Export survey definition as a JSON string"
  [gae-app-id survey-id]
  {:pre [(> survey-id 0)]}
  (gae/with-datastore [ds (util/datastore-spec gae-app-id)]
    (json/generate-string (assemble-survey-definition ds survey-id))))
