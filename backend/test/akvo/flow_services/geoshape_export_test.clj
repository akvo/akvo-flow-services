(ns akvo.flow-services.geoshape-export-test
  (:require [clojure.test :refer :all]
            [akvo.flow-services.geoshape-export :as geoshape-export]
            [testit.core :as it]
            [cheshire.core :as json]))

(def valid-polygon {:features [{:type       "Feature",
                                :properties {:length "598909.00", :pointCount "3", :area "13872516944.93"},
                                :geometry   {:type        "Polygon",
                                             :coordinates [[[43.657240234315395 43.805380480517236]
                                                            [44.38478909432889 42.843155681966564]
                                                            [46.41390200704336 44.473117973161685]
                                                            [43.657240234315395 43.805380480517236]]]}}],
                    :type     "FeatureCollection"})

(def line-coordinates [[43.657240234315395 43.805380480517236]])
(def geo-question-id 2)
(def questions [{:id 1 :text "question 1"}
                {:id geo-question-id :text "geo question"}])

(deftest export
  (let [instance-id 3
        responses [{:value       "one"
                    :iteration   0
                    :instance-id instance-id
                    :question-id 1}
                   {:value       (json/generate-string valid-polygon)
                    :iteration   0
                    :instance-id instance-id
                    :question-id geo-question-id}]
        instance-data [{:surveyalTime              16
                        :collectionDate            #inst"2018-05-24T15:07:33.094-00:00"
                        :surveyedLocaleIdentifier  "381t-1vk2-2ph7"
                        :deviceIdentifier          "device"
                        :surveyedLocaleDisplayName "vudhxhx"
                        :submitterName             "a submitter"
                        :surveyId                  58863002
                        :keyId                     instance-id
                        :surveyedLocaleId          56023002}]
        export-result (geoshape-export/build-feature-collection "any"
                                                                geo-question-id
                                                                questions
                                                                responses
                                                                (constantly instance-data))]

    (it/fact "generic properties"
      export-result
      =in=>
      {:properties {:formId       "any"
                    :questionId   2
                    :questionText "geo question"}
       :type       "FeatureCollection"})

    (it/fact "contains the whole first feature of the response"
      export-result
      =in=>
      {:features [{"geometry"   {"coordinates" [[[43.657240234315395 43.805380480517236]
                                                 [44.38478909432889 42.843155681966564]
                                                 [46.41390200704336 44.473117973161685]
                                                 [43.657240234315395 43.805380480517236]]]
                                 "type"        "Polygon"}
                   "properties" {"area"       "13872516944.93"
                                 "length"     "598909.00"
                                 "pointCount" "3"}
                   "type"       "Feature"}]})

    (it/fact "it also contains other responses for the same instance-id"
      export-result
      =in=>
      {:features [{"properties" {"question 1" "one"}}]})

    (it/fact "and the instance data"
      export-result
      =in=>
      {:features [{"properties" {"Duration"          "00:00:16"
                                 "Submitter"         "a submitter"
                                 "Submission Date"   "24-05-2018 15:07:33 UTC"
                                 "Instance"          "3"
                                 "Device identifier" "device"
                                 "Display Name"      "vudhxhx"
                                 "Identifier"        "381t-1vk2-2ph7"}}]})))


(defn invalid [response-delta]
  (let [responses [(-> (merge {:value       valid-polygon
                               :iteration   0
                               :instance-id 3
                               :question-id geo-question-id}
                              response-delta)
                       (update :value json/generate-string))]
        instance-data []]
    (it/fact
      (geoshape-export/build-feature-collection "any" geo-question-id questions responses (constantly instance-data))
      =in=>
      {:features empty?})))

(deftest export-invalid-data

  (invalid {:value (assoc-in valid-polygon [:features 0 :geometry :type] "Other than Polygon")})
  (invalid {:value (assoc-in valid-polygon [:features 0 :geometry :coordinates 1] line-coordinates)})
  (invalid {:question-id (inc geo-question-id)})
  (invalid {:iteration 1}))