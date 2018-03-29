;  Copyright (C) 2015 Stichting Akvo (Akvo Foundation)
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
            [akvo.flow-services.util :as util])
  (:import [java.util UUID]))

(defn feature [question-id questions question-answers]
  (let [geoshape (-> question-answers
                     (get question-id)
                     json/parse-string
                     (get "features")
                     first)]
    (when (and (= "Polygon" (get-in geoshape ["geometry" "type"]))
               (every? #(> (count %) 2) (get-in geoshape ["geometry" "coordinates"])))
      (reduce (fn [feature [id value]]
                (if (not= id question-id)
                  (assoc-in feature ["properties" (get questions id)] value)
                  feature))
              geoshape
              question-answers))))

;; question-id: the geoshape question id
;; quesions:    map from question id -> question text
;; responses:   map from instance-id -> question-id -> response text
(defn build-features [question-id questions responses]
  (for [[instance-id question-answers] responses]
    (feature question-id questions question-answers)))

(defn build-feature-collection [form-id geoshape-question-id questions responses]
  {:type "FeatureCollection"
   :properties {:formId form-id
                :questionId geoshape-question-id
                :questionText (get questions geoshape-question-id)}
   :features (remove nil? (build-features geoshape-question-id questions responses))})


(defn fetch-responses [ds form-id]
  (let [responses (query/result ds
                                {:kind "QuestionAnswerStore"
                                 :filter (query/= "surveyId" form-id)}
                                {:chunk-size 300})]
    (for [response responses]
      {:value (or (.getProperty response "value")
                  (.getValue (.getProperty response "valueText")))
       :iteration (or (.getProperty response "iteration") 0)
       :instance-id (.getProperty response "surveyInstanceId")
       :question-id (Long/parseLong (.getProperty response "questionID"))})))

(defn fetch-questions [ds form-id]
  (let [questions (query/result ds {:kind "Question"
                                    :filter (query/= "surveyId" form-id)})]
    (for [question questions]
      {:id (-> question .getKey .getId)
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

(defn export [app-id form-id geoshape-question-id]
  (timbre/infof "Exporting geoshape app-id: %s - form-id: %s - geoshape-question-id: %s" app-id form-id geoshape-question-id)
  (gae/with-datastore [ds (util/datastore-spec app-id)]
    (let [form-id (Long/parseLong form-id)
          questions (reduce (fn [result {:keys [id text]}]
                              (assoc result id text))
                            {}
                            (fetch-questions ds form-id))
          responses (reduce (fn [result {:keys [instance-id question-id value]}]
                              (assoc-in result [instance-id question-id] value))
                            {}
                            (filter #(zero? (:iteration %))
                                    (fetch-responses ds form-id)))
          feature-collection (build-feature-collection form-id
                                                       geoshape-question-id
                                                       questions
                                                       responses)
          file (export-file app-id form-id geoshape-question-id)]
      (with-open [writer (io/writer file)]
        (json/generate-stream feature-collection writer)
        (timbre/infof "Successfully exported geoshape to %s" file))
      file)))
