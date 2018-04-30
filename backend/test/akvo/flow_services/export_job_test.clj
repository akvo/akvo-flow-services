(ns akvo.flow-services.export-job-test
  (:require [clojure.test :refer [deftest is are testing]]
            [akvo.flow-services.scheduler :as scheduler]))


(deftest gdpr?
  (is (scheduler/gdpr-flow? {"opts" {"gdpr" "true"}}))
  (is (not (scheduler/gdpr-flow? {"opts" {}})))
  (is (not (scheduler/gdpr-flow? {"opts" {"gdpr" "false"}})))

  (testing "flow notifications"
    (testing "create request"
      (is (= (scheduler/create-report-in-flow {"opts"       {"email" "user@akvo.org"}
                                               "baseURL"    "http://foobar"
                                               "exportType" "GEOSHAPE"})
             {:method :post
              :url    "http://foobar/reports"
              :body   {:report {:state      "IN_PROGRESS"
                                :user       "user@akvo.org"
                                :reportType "GEOSHAPE"}}})))

    (testing "finish request"
      (are [report-result expected-body]
        (= (scheduler/finish-report-in-flow {"opts"       {"email" "user@akvo.org"}
                                             "baseURL"    "http://foobar"
                                             "exportType" "GEOSHAPE"
                                             "flowId"     "id-returned-by-flow"}
                                            report-result)
           {:method :put
            :url    "http://foobar/reports/id-returned-by-flow"
            :body   {:report (merge {:user       "user@akvo.org"
                                     :reportType "GEOSHAPE"}
                                    expected-body)}})

        {:report-path "some awesome path"} {:state    "FINISHED_SUCCESS"
                                            :filename "some awesome path"}

        {:report-path "INVALID_PATH"} {:state   "FINISHED_ERROR"
                                       :message "Error generating report"}

        {:exception (RuntimeException. "Something very bad")} {:state   "FINISHED_ERROR"
                                                               :message "Something very bad"})))
  )