;  Copyright (C) 2020 Stichting Akvo (Akvo Foundation)
;
;  This file is part of Akvo FLOW.
;
;  Akvo FLOW is free software: you can redistribute it and modify it under the terms of
;  the GNU Affero General Public License (AGPL) as published by the Free Software Foundation,
;  either version 3 of the License or any later version.
;
;  Akvo FLOW is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
;  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
;  See the GNU Affero General Public License included below for more details.
;
;  The full license text can also be seen at <http://www.gnu.org/licenses/agpl.html>.

(ns akvo.flow-services.aws-s3
  (:require [akvo.commons.config :as config]
            [akvo.flow-services.util :refer [hmac-sha256]]
            [cheshire.core :as json]
            [ring.util.response :as resp])
  (:import [java.text DateFormat]
           [java.time Instant ZonedDateTime ZoneId]
           [java.time.format DateTimeFormatter]
           [java.util Base64 Date]
           [org.apache.commons.codec.binary Hex]))

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


(defn handler
  [params]
  (if-let [instance (get params :instance)]
    (if-let [cfg (get @config/configs instance)]
      (let [bucket (:s3bucket cfg)
            region "eu-west-1"
            access-key (:access-key cfg)
            img-policy (image-policy bucket access-key region 3600) ;; TODO: short TTL
            img-sig (signature (:secret-key cfg) region (:policy img-policy))
            zip-policy (data-policy bucket access-key region 3600) ;; TODO: short TTL
            zip-sig (signature (:secret-key cfg) region (:policy zip-policy))]
        (-> {:image (-> img-policy
                        (assoc :policy (encode-policy (:policy img-policy)))
                        (assoc :x-amz-signature img-sig))
             :zip (-> zip-policy
                      (assoc :policy (encode-policy (:policy zip-policy)))
                      (assoc :x-amz-signature zip-sig))}
            (json/generate-string)
            (resp/response)
            (resp/header "content-type" "application/json"))) ;; TODO: move content-type header to middleware
      (-> {:message (str "Config for instance " instance " not found")}
          (json/generate-string)
          (resp/not-found)
          (resp/header "content-type" "application/json")))
    (-> {:message (str "instance parameter is required")}
        (json/generate-string)
        (resp/response)
        (resp/status 400)
        (resp/header "content-type" "application/json"))))


(comment

  (handler {})

  (handler {:instance "non-existent"})

  (handler {:instance "akvoflow-uat1"})

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
