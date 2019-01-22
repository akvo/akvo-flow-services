(ns akvo.flow-services.geoshape-export-test
  (:require [clojure.test :refer :all]
            [akvo.flow-services.geoshape-export :as geoshape-export]
            [testit.core :as it]
            [cheshire.core :as json]))

(def valid-polygon {:features [{:type "Feature",
                                :properties {:length "598909.00", :pointCount "3", :area "13872516944.93"},
                                :geometry {:type "Polygon",
                                           :coordinates [[[43.657240234315395 43.805380480517236]
                                                          [44.38478909432889 42.843155681966564]
                                                          [46.41390200704336 44.473117973161685]
                                                          [43.657240234315395 43.805380480517236]]]}}],
                    :type "FeatureCollection"})

(def line-coordinates [[43.657240234315395 43.805380480517236]])
(def geo-question-id 2)
(def questions [{:id 1 :text "question 1"}
                {:id geo-question-id :text "geo question"}])

(deftest export
         (let [instance-id 3
               responses [{:value "one"
                           :iteration 0
                           :instance-id instance-id
                           :question-id 1}
                          {:value (json/generate-string valid-polygon)
                           :iteration 0
                           :instance-id instance-id
                           :question-id geo-question-id}]
               instance-data [{:surveyalTime 16
                               :collectionDate #inst"2018-05-24T15:07:33.094-00:00"
                               :surveyedLocaleIdentifier "381t-1vk2-2ph7"
                               :deviceIdentifier "device"
                               :surveyedLocaleDisplayName "vudhxhx"
                               :submitterName "a submitter"
                               :surveyId 58863002
                               :keyId instance-id
                               :surveyedLocaleId 56023002}]
               export-result (geoshape-export/build-feature-collection "any"
                               geo-question-id
                               questions
                               responses
                               (constantly instance-data))]

           (it/fact "generic properties"
             export-result
             =in=>
             {:properties {:formId "any"
                           :questionId 2
                           :questionText "geo question"}
              :type "FeatureCollection"})

           (it/fact "contains the whole first feature of the response"
             export-result
             =in=>
             {:features [{"geometry" {"coordinates" [[[43.657240234315395 43.805380480517236]
                                                      [44.38478909432889 42.843155681966564]
                                                      [46.41390200704336 44.473117973161685]
                                                      [43.657240234315395 43.805380480517236]]]
                                      "type" "Polygon"}
                          "properties" {"area" "13872516944.93"
                                        "length" "598909.00"
                                        "pointCount" "3"}
                          "type" "Feature"}]})

           (it/fact "it also contains other responses for the same instance-id"
             export-result
             =in=>
             {:features [{"properties" {"question 1" "one"}}]})

           (it/fact "and the instance data"
             export-result
             =in=>
             {:features [{"properties" {"Duration" "00:00:16"
                                        "Submitter" "a submitter"
                                        "Submission Date" "24-05-2018 15:07:33 UTC"
                                        "Instance" "3"
                                        "Device identifier" "device"
                                        "Display Name" "vudhxhx"
                                        "Identifier" "381t-1vk2-2ph7"}}]})))

(deftest repeated-group
         (let [instance-id 3
               responses [{:value "one"
                           :iteration 0
                           :instance-id instance-id
                           :question-id 1}
                          {:value "two"
                           :iteration 1
                           :instance-id instance-id
                           :question-id 1}
                          {:value (json/generate-string valid-polygon)
                           :iteration 0
                           :instance-id instance-id
                           :question-id geo-question-id}
                          {:value (json/generate-string {:type "FeatureCollection",
                                                         :features [{:type "Feature",
                                                                     :geometry {:type "MultiPoint", :coordinates [[-1.8617066 2.9536206]]},
                                                                     :properties {:pointCount "1"}}]})
                           :iteration 1
                           :instance-id instance-id
                           :question-id geo-question-id}]
               instance-data [{:keyId instance-id}]
               export-result (geoshape-export/build-feature-collection "any"
                               geo-question-id
                               questions
                               responses
                               (constantly instance-data))]

           (it/fact "both iterations exist"
             export-result
             =in=>
             {:features [{"geometry" {"type" "Polygon"}
                          "properties" {"Repeat No" 1}}
                         {"geometry" {"type" "MultiPoint"}
                          "properties" {"Repeat No" 2}}]})

           (it/fact "each one with its own responses"
             export-result
             =in=>
             {:features [{"properties" {"question 1" "one"}}
                         {"properties" {"question 1" "two"}}]})

           (it/fact "and the instance data is the same"
             export-result
             =in=>
             {:features [{"properties" {"Instance" (str instance-id)}}
                         {"properties" {"Instance" (str instance-id)}}]})))

(deftest export-other-geoshapes
         (let [export (fn [response-value]
                        (let [instance-id 3
                              responses [{:value (json/generate-string response-value)
                                          :iteration 0
                                          :instance-id instance-id
                                          :question-id geo-question-id}]]
                          (geoshape-export/build-feature-collection "any"
                            geo-question-id
                            questions
                            responses
                            (constantly nil))))]

           (it/fact "LineString geoshapes"
             (export {:type "FeatureCollection",
                      :features [{:type "Feature",
                                  :geometry {:type "LineString",
                                             :coordinates [[-1.2385138 85.4837292]
                                                           [-16.2389336 83.4832938]
                                                           [-12.2388719 15.4832508]]},
                                  :properties {:pointCount "3", :length "182.15"}}]})
             =in=>
             {:features [{"geometry" {"coordinates" [[-1.2385138 85.4837292]
                                                     [-16.2389336 83.4832938]
                                                     [-12.2388719 15.4832508]]
                                      "type" "LineString"}
                          "properties" {"pointCount" "3"
                                        "length" "182.15"}
                          "type" "Feature"}]})

           (it/fact "MultiPoint geoshapes"
             (export {:type "FeatureCollection",
                      :features [{:type "Feature",
                                  :geometry {:type "MultiPoint", :coordinates [[-1.8617066 2.9536206]]},
                                  :properties {:pointCount "1"}}]})
             =in=>
             {:features [{"geometry" {"coordinates" [[-1.8617066 2.9536206]]
                                      "type" "MultiPoint"}
                          "properties" {"pointCount" "1"}
                          "type" "Feature"}]})))

(defn invalid [response-delta]
  (let [responses [(-> (merge {:value valid-polygon
                               :iteration 0
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
  (invalid {:question-id (inc geo-question-id)}))

(deftest parsing-value
         (it/fact "Does not touch question types other than cascade or option"
           (geoshape-export/parse-value {:type :any-type} "[foobar]") => "[foobar]")

  (it/facts "Cascade extract code and name"
    (geoshape-export/parse-value {:type :CASCADE} "32") => "32"
    (geoshape-export/parse-value {:type :CASCADE} "[{invalid-json") => "[{invalid-json"

    (geoshape-export/parse-value {:type :CASCADE} (json/generate-string [{:code "spring" :name "spring"}])) => "spring"
    (geoshape-export/parse-value {:type :CASCADE} (json/generate-string [{:code "spring" :name "spring"}
                                                                         {:code "winter" :name "winter"}])) => "spring|winter"
    (geoshape-export/parse-value {:type :CASCADE} (json/generate-string [{:code "one" :name "spring"}])) => "one:spring"
    (geoshape-export/parse-value {:type :CASCADE} (json/generate-string [{:code nil :name "spring"}])) => "spring")


  (it/facts "Option extract code and name"
    (geoshape-export/parse-value {:type :OPTION} "32") => "32"
    (geoshape-export/parse-value {:type :OPTION} "[{invalid-json") => "[{invalid-json"

    (geoshape-export/parse-value {:type :OPTION} (json/generate-string [{:code "spring" :text "spring"}])) => "spring"
    (geoshape-export/parse-value {:type :OPTION} (json/generate-string [{:code "spring" :text "spring"}
                                                                        {:code "winter" :text "winter"}])) => "spring|winter"
    (geoshape-export/parse-value {:type :OPTION} (json/generate-string [{:code "one" :text "spring"}])) => "one:spring"
    (geoshape-export/parse-value {:type :OPTION} (json/generate-string [{:code nil :text "spring"}])) => "spring"))