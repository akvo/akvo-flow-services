(ns akvo.flow-services.aws-s3
  (:require [akvo.commons.config :as config]
            [akvo.flow-services.util :refer [hmac-sha256]]
            [cheshire.core :as json])
  (:import [java.text DateFormat]
           [java.time Instant ZonedDateTime ZoneId]
           [java.time.format DateTimeFormatter]
           [java.util Base64 Date]
           [org.apache.commons.codec.binary.Hex]))

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

(defn utc-datetime
  []
  (ZonedDateTime/now (ZoneId/of "UTC")))

(defn yyyyMMdd
  [dt]
  (.format (DateTimeFormatter/ofPattern "yyyyMMdd") dt))

(defn iso8601-instant
  [dt]
  (.format (DateTimeFormatter/ISO_INSTANT) dt))

(defn x-amz-date
  "20200520T050551Z"
  [dt]
  (.format (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'") dt))

(defn x-aws-credential
  "<access-key-id>/<date-in-yyyyMMdd>/<region>/s3/aws4_request"
  [access-key date region]
  (format "%s/%s/%s/s3/aws4_request"
          access-key
          (yyyyMMdd date)
          region))

(defn policy
  [bucket access-key region ttl-in-seconds acl conditions]
  (let [date (utc-datetime)
        x-credential (x-aws-credential access-key date region)
        x-date (x-amz-date date)
        base-conditions [{"bucket" bucket}
                         {"acl" acl}
                         {"success_action_status" "201"}
                         {"x-amz-algorithm" "AWS4-HMAC-SHA256"}
                         {"x-amz-credential" x-credential}
                         {"x-amz-date" x-date}]
        policy {:expiration (iso8601-instant (.plusSeconds date ttl-in-seconds))
                :conditions (apply conj base-conditions conditions)}]
    {:bucket bucket
     :acl acl
     :success_action_status "201"
     :x-amz-algorithm "AWS4-HMAC-SHA256"
     :x-amz-credential x-credential
     :x-amz-date x-date
     :policy policy}))

(defn image-policy
  [bucket access-key region ttl-in-seconds]
  (policy bucket access-key region ttl-in-seconds "public-read" [["starts-with", "$key", "images/"]
                                                                 ["starts-with", "$content-type", "image/"]]))
(defn data-policy
  [bucket access-key region ttl-in-seconds]
  (policy bucket access-key region ttl-in-seconds "private" [["starts-with", "$key", "devicezip/"]
                                                             ["eq", "$content-type", "application/zip"]]))
(defn base64
  [ba]
  (.encodeToString (Base64/getEncoder) ba))

(defn encode-policy
  [policy]
  (-> policy
      (json/generate-string)
      (.getBytes "UTF-8")
      (base64)))

(defn signature
  [secret region policy]
  (let [encoded (encode-policy policy)
        sk (signing-key secret (yyyyMMdd (utc-datetime)) region "s3")
        sig (hmac-sha256 sk encoded {})]
    (Hex/encodeHexString sig)))


(comment

  (def uat1 (get @config/configs "akvoflow-uat1"))

  (image-policy "akvoflow-uat1" (:access-key uat1) "eu-west-1" 3600)

  (def fields
    (let [bucket "akvoflow-uat1"
          region "eu-west-1"
          access-key (:access-key uat1)
          policy (image-policy bucket access-key region 3600)
          sig (signature (:secret-key uat1) region policy)]
      (-> policy
          (assoc :policy (encode-policy policy))
          (assoc :x-amz-signature sig))))

  (clojure.pprint/pprint fields)

  )
