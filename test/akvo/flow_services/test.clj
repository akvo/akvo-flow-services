(ns akvo.flow-services.test
  (:require [akvo.flow-services.config :as config]
            [akvo.flow-services.scheduler :as sched]
            [clojure.test :refer :all]))

(config/set-config! "/home/ivan/workspace/akvo/src/akvo-flow-server-config")

(def instance-id "akvoflow-12.appspot.com")

(def instance-alias "indiiwatsan2.akvoflow.org")

(def survey-id 123)

(def sample-1 {{:id "id1"
                :surveyId (str survey-id)
                :baseURL instance-id} "/tmp/dummy.xlsx"})

(def sample-2 {{:id "id2"
                :surveyId (str survey-id)
                :baseURL instance-alias} "/tmp/dummy.xlsx"})

(def sample-3 {{:id "id3"
                :surveyId (str survey-id)
                :baseURL "akvoflow-13.appspot.com"} "/tmp/dummy.xlsx"})


(defn- add-sample []
   (dosync
    (alter sched/cache conj sample-1)
    (alter sched/cache conj sample-2)
    (alter sched/cache conj sample-3)))

(deftest test-instance-alias
  (do
    (add-sample)
    (is (= instance-alias (config/get-alias instance-id)))))

(deftest test-cache-invalidation
  (do
    (add-sample)

    (is (= 3 (count @sched/cache)))

    (sched/invalidate-cache {:baseURL "https://akvoflow-12.appspot.com"
                             :surveyIds [survey-id]})

    (is (= 1 (count @sched/cache)))))