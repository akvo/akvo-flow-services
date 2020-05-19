(ns akvo.flow-services.util-test
  (:require [akvo.flow-services.util :refer :all]
            [clojure.test :refer [deftest is testing]]))

;;
(deftest hmac-sha1-test
  (testing "Base64 encoded HMAC-SHA1"
    ;; echo -n "value" | openssl dgst -binary -sha1 -hmac "key" | openssl base64 -A
    (is (= "V0Q6TAUjUKRGOINdZP1mgi+BMxk="
           (hmac-sha1 "key" "value")))))
