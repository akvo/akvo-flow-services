(ns akvo.flow-services.translate-test
  (:require [akvo.flow-services.translate :as t]
            [clojure.test :refer :all]))

(deftest translating-strings
  (with-redefs [t/translation-map {:some-key {:en "%s is the answer"
                                              :es "%s es la respuesta"}}]
    (is (= (t/t> :en :some-key 42) "42 is the answer"))
    (is (= (t/t> :es :some-key 42) "42 es la respuesta"))
    (is (= (t/t> :fr :some-key 42) "42 is the answer"))
    (is (thrown? AssertionError (t/t> :en :unknown-key)))))
