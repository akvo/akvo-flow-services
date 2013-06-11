(ns akvo.flow-services.test
  (:use clojure.test)
  (:require [akvo.flow-services.config :as config]
            [akvo.flow-services.scheduler :as sched]))

(config/set-config! "/home/ivan/workspace/akvo/src/akvo-flow-server-config")
(config/set-instance-alias! "/home/ivan/workspace/akvo/src/akvo-flow-server-config")

(def instance-id "http://akvoflow-12.appspot.com")

(def instance-alias "http://indiiwatsan2.akvoflow.org")

(def survey-id 123)

(def sample-1 {{:id "id1"
                :surveyId (str survey-id)
                :baseURL instance-id} "/tmp/dummy.xlsx"})

(def sample-2 {{:id "id2"
                :surveyId (str survey-id)
                :baseURL instance-alias} "/tmp/dummy.xlsx"})


(defn- add-sample []
   (dosync
    (alter sched/cache conj sample-1)
    (alter sched/cache conj sample-2)))

(deftest test-instance-alias
  (do
    (add-sample)
    (is (= instance-id (@config/instance-alias instance-alias)))))

(deftest test-cache-invalidation
  (do
    (add-sample)

    (is (= 2 (count @sched/cache)))
    
    (sched/invalidate-cache {"baseURL" instance-alias
                             "surveIds" [survey-id]})
    
    (is (= 1 (count @sched/cache)))))