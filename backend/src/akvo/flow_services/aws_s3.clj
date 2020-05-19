(ns akvo.flow-services.aws-s3
  (:require [akvo.commons.config :as config]
            [akvo.flow-services.util :refer [hmac-sha256]]
            [cheshire.core :as json])
  (:import [java.text SimpleDateFormat]
           [java.util Date]
           [java.time Instant]))

;; https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-post-example.html
;; https://docs.aws.amazon.com/general/latest/gr/signature-v4-examples.html


(defn signing-key
  [key date region service]
  (let [secret (str "AWS4" key)
        date-key (hmac-sha256 secret date {})
        region-key (hmac-sha256 date-key region {})
        service-key (hmac-sha256 region-key service {})
        signing (hmac-sha256 service-key "aws4_request" {})]
    signing))

(defn policy
  [bucket]
  (let [policy {:expiration (-> (Instant/now)
                                (.plusSeconds 3600)
                                (.toString))
                :conditions [{"bucket" bucket}
                             ["starts-with" "$key" "images/"]
                             {"acl" "public-read"}
                             {"success_action_status" "201"}]}]
    (json/generate-string policy)))
