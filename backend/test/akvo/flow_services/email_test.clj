(ns akvo.flow-services.email-test
  (:require [clojure.test :refer :all]
            [akvo.flow-services.email :as email]))

(deftest hide-emails
  (is (= ["****@akvo.org"
          "****@foo.org"
          "****"
          nil]
         (email/obfuscate ["any.email@akvo.org"
                           "x@foo.org"
                           "no at symbol"
                           nil]))))
