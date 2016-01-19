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

(defn retrieve-survey
  [gae-app-id survey-id]
  (let [settings @config/settings
        config (config/find-config gae-app-id)]
    (gae/with-datastore [ds {:hostname (:domain config)
                         :service-account-id (:service-account-id config)
                         :private-key-file (:private-key-file config)
                         :port 443}]
      (query/entity ds "SurveyGroup" survey-id))))

(defn export-survey-definition
  "Assemble the definition of a survey from its different components and export
   the entire collection as JSON"
  [gae-app-id survey-id]
  (let [survey (retrieve-survey gae-app-id survey-id)]
    survey))
