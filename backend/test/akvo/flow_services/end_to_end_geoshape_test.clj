(ns akvo.flow-services.end-to-end-geoshape-test
  {:integration true}
  (:require [clojure.test :refer :all]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [akvo.flow-services.test-util :as test-util]
            [akvo.commons.gae :as gae]))

(def wiremock-url "http://wiremock-proxy:8080")
(def wiremock-mappings-url (str wiremock-url "/__admin/mappings"))
(def flow-services-url "http://mainnetwork:3000")
(def gae-local {:hostname "localhost"
                :port     8888})

(defn generate-report [survey-id question-id]
  (some->> (http/get (str flow-services-url "/generate")
                     {:query-params {:callback "somejson"
                                     :criteria (json/generate-string
                                                 {"baseURL"    wiremock-url
                                                  "exportType" "GEOSHAPE"
                                                  "surveyId"   (str survey-id)
                                                  "id"         "asdfxxxxx"
                                                  "opts"       {"email"          "dan@akvo.org"
                                                                "lastCollection" "false"
                                                                "imgPrefix"      "https://akvoflowsandbox.s3.amazonaws.com/images/",
                                                                "uploadUrl"      "https://akvoflowsandbox.s3.amazonaws.com/",
                                                                "exportMode"     "GEOSHAPE"
                                                                "questionId"     question-id
                                                                "flowServices"   flow-services-url
                                                                "appId"          "flowservices-dev-config"}})}})
           :body
           (re-seq #"somejson\((.*)\);")
           first
           second
           json/parse-string))

(defn instance-data [survey-id instance-id]
  {"request"  {"method"          "GET"
               "urlPath"         "/instancedata"
               "queryParameters" {"action"           {"equalTo" "getInstanceData"}
                                  "surveyInstanceId" {"equalTo" (str instance-id)}}}
   "response" {"status"   200
               "jsonBody" {"surveyInstanceData"   {"surveyedLocaleDisplayName" "a survey display name"
                                                   "surveyId"                  survey-id
                                                   "surveyedLocaleId"          147442028
                                                   "userID"                    1
                                                   "collectionDate"            1418598306000
                                                   "submitterName"             "BASH & JAMES"
                                                   "deviceIdentifier"          "IMPORTER"
                                                   "surveyalTime"              0
                                                   "surveyedLocaleIdentifier"  "a6ey-5r1h-6a0y"
                                                   "keyId"                     instance-id}
                           "latestApprovalStatus" ""
                           "resultCount"          0
                           "offset"               0}}})

(defn mock-gae [survey-id surveyed-instance-id]
  (let [messages [(instance-data survey-id surveyed-instance-id)]]
    (doseq [message messages]
      (http/post wiremock-mappings-url {:body (json/generate-string message)}))))

(defn mock-mailjet []
  (http/post wiremock-mappings-url {:body (json/generate-string {"request"  {"method"  "POST"
                                                                             "urlPath" "/mailjet/send"}
                                                                 "response" {"status" 200
                                                                             "body"   "ok"}})}))

(defn email-sent-count [body]
  (-> (http/post (str wiremock-url "/__admin/requests/count")
                 {:as   :json
                  :body (json/generate-string
                          {"method"       "POST"
                           "bodyPatterns" [{"matches" (str ".*" body ".*")}]
                           "urlPath"      "/mailjet/send"})})
      :body
      :count))

(defn reset-wiremock []
  (http/post (str wiremock-mappings-url "/reset")))

(defn check-servers-up []
  (test-util/wait-for-server "mainnetwork" 3000)
  (test-util/wait-for-server "wiremock-proxy" 8080))

(use-fixtures :once (fn [f]
                      (check-servers-up)
                      (reset-wiremock)
                      (f)))

(defn question-answer [survey-id survey-instance-id question-id value]
  {"surveyId"         survey-id
   "value"            value
   "iteration"        0
   "surveyInstanceId" survey-instance-id
   "questionID"       (str question-id)})

(deftest report-generation
  (let [survey-id (System/currentTimeMillis)
        survey-instance-id (rand-int 100000000)
        question-ids (gae/with-datastore [ds gae-local]
                       {:geo-question     (.getId (gae/put! ds "Question" {"surveyId" survey-id "text" "GeoQuestion"}))
                        :another-question (.getId (gae/put! ds "Question" {"surveyId" survey-id "text" "AnotherQuestion"}))})
        some-additional-properties {:length "598909.00", :pointCount "3", :area "13872516944.93"}
        coords [[43.657240234315395 43.805380480517236]
                [44.38478909432889 42.843155681966564]
                [46.41390200704336 44.473117973161685]
                [43.657240234315395 43.805380480517236]]]
    (gae/with-datastore [ds gae-local]
      (doseq [answer [(question-answer survey-id survey-instance-id (:geo-question question-ids)
                                       (json/generate-string {:features [{:type       "Feature",
                                                                          :properties some-additional-properties,
                                                                          :geometry   {:type        "Polygon",
                                                                                       :coordinates [coords]}}],
                                                              :type     "FeatureCollection"}))
                      (question-answer survey-id survey-instance-id (:another-question question-ids) "the other question response")]]
        (gae/put! ds "QuestionAnswerStore" answer)))
    (mock-gae survey-id survey-instance-id)
    (mock-mailjet)
    (test-util/try-for "Processing for too long" 20
                       (not= {"status" "OK", "message" "PROCESSING"}
                             (generate-report survey-id (:geo-question question-ids))))
    (let [report-result (generate-report survey-id (:geo-question question-ids))]
      (is (= "OK" (get report-result "status")))

      (test-util/try-for "email not sent" 5
                         (= 1 (email-sent-count (get report-result "file"))))

      (is (= 200 (:status (http/get (str flow-services-url "/report/" (get report-result "file"))))))
      (let [report (json/parse-string (:body (http/get (str flow-services-url "/report/" (get report-result "file")))) true)]
        (is (= coords (get-in report [:features 0 :geometry :coordinates 0])))
        (is (= "the other question response" (get-in report [:features 0 :properties :AnotherQuestion])))
        (is (= "BASH & JAMES" (get-in report [:features 0 :properties :Submitter])))))))