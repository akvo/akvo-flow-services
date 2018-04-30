(ns akvo.flow-services.export-job-test
  (:require [clojure.test :refer [deftest is are testing]]
            [akvo.flow-services.scheduler :as scheduler])
  (:import (java.io IOException)))


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

    (testing "handle create response"
      (is (= (scheduler/handle-create-report-in-flow {:status 200
                                                      :body   {:report {:id "some-id"}}})
             [:continue {"flow-create-result" "some-id"}]))

      (is (= (scheduler/handle-create-report-in-flow {:status 200
                                                      :body   nil})
             [:abort "Flow did not return an id for the report"]))

      (is (= (scheduler/handle-create-report-in-flow {:status 500})
             [:abort "Flow returns error on report creation. HTTP code: 500"]))

      (let [an-exception (IOException. "some error")
            [action value] (scheduler/handle-create-report-in-flow {:exception an-exception})]
        (is (= action :abort))
        (is (= an-exception (.getCause value)))))

    (testing "finish request"
      (are [report-result expected-body]
        (let [an-email (str (rand-int 30000) "-user@akvo.org")]
          (= (scheduler/finish-report-in-flow {"opts"               {"email" an-email}
                                               "baseURL"            "http://foobar"
                                               "exportType"         "GEOSHAPE"
                                               "flow-create-result" "id-returned-by-flow"}
                                              report-result)
             {:method :put
              :url    "http://foobar/reports/id-returned-by-flow"
              :body   {:report (merge {:user       an-email
                                       :reportType "GEOSHAPE"}
                                      expected-body)}}))

        {:report-path "some awesome path"} {:state    "FINISHED_SUCCESS"
                                            :filename "some awesome path"}

        {:report-path "INVALID_PATH"} {:state   "FINISHED_ERROR"
                                       :message "Error generating report"}

        {:exception (RuntimeException. "Something very bad")} {:state   "FINISHED_ERROR"
                                                               :message "Something very bad"})))
  )