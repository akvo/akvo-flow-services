(ns akvo.flow-services.error-test
  (:require [clojure.test :refer :all]
            [akvo.flow-services.error :as error]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.spec.gen.alpha :as gen]))

(def identity-gen (s/with-gen fn? #(gen/return identity)))

(s/fdef error/fmap
        :args (s/cat :value any?
                     :if-err identity-gen
                     :if-ok identity-gen)
        :ret any?
        :fn (fn [{:keys [args ret]}]
              (= (:value args) ret)))

(deftest does-not-blow-for-any-value
  (is (nil? (:failure
              (stest/abbrev-result
                (first
                  (stest/check `error/fmap {:clojure.spec.test.check/opts {:num-tests 50}})))))))

