(ns akvo.flow-services.end-to-end-geoshape-test
  {:integration true}
  (:require [clojure.test :refer :all]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [akvo.flow-services.test-util :as test-util]
            [akvo.commons.gae :as gae]
            [akvo.commons.gae.query :as query]))

(defn generate-report [survey-id question-id]
  (some->> (http/get (str test-util/flow-services-url "/generate")
                     {:query-params {:callback "somejson"
                                     :criteria (json/generate-string
                                                 {"baseURL"    test-util/wiremock-url
                                                  "exportType" "GEOSHAPE"
                                                  "surveyId"   (str survey-id)
                                                  "id"         "asdfxxxxx"
                                                  "opts"       {"email"          "dan@akvo.org"
                                                                "lastCollection" "false"
                                                                "imgPrefix"      "https://akvoflowsandbox.s3.amazonaws.com/images/",
                                                                "uploadUrl"      "https://akvoflowsandbox.s3.amazonaws.com/",
                                                                "exportMode"     "GEOSHAPE"
                                                                "questionId"     question-id
                                                                "flowServices"   test-util/flow-services-url
                                                                "appId"          "flowservices-dev-config"}})}})
           :body
           (re-seq #"somejson\((.*)\);")
           first
           second
           json/parse-string))

(defn mock-gae [survey-id surveyed-instance-id]
  (test-util/setup-wiremock [(test-util/instance-data survey-id surveyed-instance-id)]))

(use-fixtures :once test-util/fixture)

(defn question-answer [survey-id survey-instance-id question-id value]
  {"surveyId"         survey-id
   "value"            value
   "iteration"        0
   "surveyInstanceId" survey-instance-id
   "questionID"       (str question-id)})

(deftest report-generation
  (let [survey-id (System/currentTimeMillis)
        survey-instance-id (rand-int 100000000)
        question-ids (gae/with-datastore [ds test-util/gae-local]
                       {:geo-question     (.getId (gae/put! ds "Question" {"surveyId" survey-id "text" "GeoQuestion"}))
                        :another-question (.getId (gae/put! ds "Question" {"surveyId" survey-id "text" "AnotherQuestion"}))})
        some-additional-properties {:length "598909.00", :pointCount "3", :area "13872516944.93"}
        coords [[43.657240234315395 43.805380480517236]
                [44.38478909432889 42.843155681966564]
                [46.41390200704336 44.473117973161685]
                [43.657240234315395 43.805380480517236]]]
    (gae/with-datastore [ds test-util/gae-local]
      (doseq [answer [(question-answer survey-id survey-instance-id (:geo-question question-ids)
                                       (json/generate-string {:features [{:type       "Feature",
                                                                          :properties some-additional-properties,
                                                                          :geometry   {:type        "Polygon",
                                                                                       :coordinates [coords]}}],
                                                              :type     "FeatureCollection"}))
                      (question-answer survey-id survey-instance-id (:another-question question-ids) "the other question response")]]
        (gae/put! ds "QuestionAnswerStore" answer)))
    (test-util/try-for "GAE took too long to return results" 10
                       (= 2
                          (gae/with-datastore [ds test-util/gae-local]
                            (count (iterator-seq (.iterator (query/result ds
                                                                          {:kind   "QuestionAnswerStore"
                                                                           :filter (query/= "surveyId" survey-id)})))))))
    (mock-gae survey-id survey-instance-id)
    (test-util/mock-mailjet)
    (test-util/try-for "Processing for too long" 20
                       (not= {"status" "OK", "message" "PROCESSING"}
                             (generate-report survey-id (:geo-question question-ids))))
    (let [report-result (generate-report survey-id (:geo-question question-ids))]
      (is (= "OK" (get report-result "status")))

      (test-util/try-for "email not sent" 5
                         (= 1 (test-util/email-sent-count (get report-result "file"))))

      (is (= 200 (:status (test-util/get-report report-result))))
      (let [report (json/parse-string (:body (test-util/get-report report-result)) true)]
        (is (= coords (get-in report [:features 0 :geometry :coordinates 0])))
        (is (= "the other question response" (get-in report [:features 0 :properties :AnotherQuestion])))
        (is (= "BASH & JAMES" (get-in report [:features 0 :properties :Submitter])))))))