(ns akvo.flow-services.export-job-test
  (:require [clojure.test :refer [deftest is are testing]]
            [akvo.flow-services.scheduler :as scheduler]))


(deftest gdpr?
  (is (scheduler/gdpr-flow? {"opts" {"gdpr" "true"}}))
  (is (not (scheduler/gdpr-flow? {"opts" {}})))
  (is (not (scheduler/gdpr-flow? {"opts" {"gdpr" "false"}})))

  (is (= (scheduler/create-report-in-flow {"opts"       {"email" "user@akvo.org"}
                                           "baseURL"    "http://foobar"
                                           "exportType" "GEOSHAPE"})
         {:method :post
          :url    "http://foobar/reports"
          :body   {:report {:state      "IN_PROGRESS"
                            :user       "user@akvo.org"
                            :reportType "GEOSHAPE"}}}))

  (testing "flow notifications"
    (testing "create request"
      (is (= (scheduler/finish-report-in-flow {"opts"       {"email" "user@akvo.org"}
                                               "baseURL"    "http://foobar"
                                               "exportType" "GEOSHAPE"
                                               "flowId"     "id-returned-by-flow"}
                                              {:report-path "some awesome path"})
             {:method :put
              :url    "http://foobar/reports/id-returned-by-flow"
              :body   {:report {:state      "FINISHED_SUCCESS"
                                :user       "user@akvo.org"
                                :reportType "GEOSHAPE"
                                :filename   "some awesome path"}}})))

    (testing "finish request"

      (testing "success"
        (is (= (scheduler/finish-report-in-flow {"opts"       {"email" "user@akvo.org"}
                                                 "baseURL"    "http://foobar"
                                                 "exportType" "GEOSHAPE"
                                                 "flowId"     "id-returned-by-flow"}
                                                {:report-path "some awesome path"})
               {:method :put
                :url    "http://foobar/reports/id-returned-by-flow"
                :body   {:report {:state      "FINISHED_SUCCESS"
                                  :user       "user@akvo.org"
                                  :reportType "GEOSHAPE"
                                  :filename   "some awesome path"}}})))

      (testing "error"
        (is (= (scheduler/finish-report-in-flow {"opts"       {"email" "user@akvo.org"}
                                                 "baseURL"    "http://foobar"
                                                 "exportType" "GEOSHAPE"
                                                 "flowId"     "id-returned-by-flow"}
                                                {:report-path "INVALID_PATH"})
               {:method :put
                :url    "http://foobar/reports/id-returned-by-flow"
                :body   {:report {:state      "FINISHED_ERROR"
                                  :user       "user@akvo.org"
                                  :reportType "GEOSHAPE"
                                  :message    "Error generating report"}}})))

      (testing "exception"
        (is (= (scheduler/finish-report-in-flow {"opts"       {"email" "user@akvo.org"}
                                                 "baseURL"    "http://foobar"
                                                 "exportType" "GEOSHAPE"
                                                 "flowId"     "id-returned-by-flow"}
                                                {:exception (RuntimeException. "Something very bad")})
               {:method :put
                :url    "http://foobar/reports/id-returned-by-flow"
                :body   {:report {:state      "FINISHED_ERROR"
                                  :user       "user@akvo.org"
                                  :reportType "GEOSHAPE"
                                  :message    "Something very bad"}}})))
      ))
  )