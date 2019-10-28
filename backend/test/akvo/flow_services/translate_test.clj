(ns akvo.flow-services.translate-test
  (:require [aero.core :as aero]
            [akvo.flow-services.translate :as t]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest translating-strings
  (with-redefs [t/translation-map {:some-key {:en "%s is the answer"
                                              :es "%s es la respuesta"}}]
    (is (= (t/t> :en :some-key 42) "42 is the answer"))
    (is (= (t/t> :es :some-key 42) "42 es la respuesta"))
    (is (= (t/t> :fr :some-key 42) "42 is the answer"))
    (is (thrown? AssertionError (t/t> :en :unknown-key)))))

(deftest aero-ref-configs
  (with-redefs [t/translation-map (aero/read-config (io/resource "test-ui-strings.edn"))]
    (is (= (t/t> :en "GEOSHAPE") (get-in t/translation-map [:report-body :en])))
    (is (= (t/t> :es "STATISTICS") (get-in t/translation-map [:statistics-body :es])))))
