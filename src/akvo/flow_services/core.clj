;  Copyright (C) 2013-2014 Stichting Akvo (Akvo Foundation)
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

(ns akvo.flow-services.core
  (:require [compojure.core :refer (defroutes GET POST OPTIONS)]
    [ring.util.response :refer (response charset content-type header)]
    [ring.adapter.jetty :refer (run-jetty)]
    [cheshire.core :as json]
    [compojure [handler :as handler] [route :as route]]
    [clojurewerkz.quartzite.scheduler :as quartzite-scheduler]
    [akvo.flow-services [scheduler :as scheduler]
                        [uploader :as uploader]
                        [cascade :as cascade]
                        [config :as config]
                        [stats :as stats]]
    [clojure.tools.nrepl.server :as nrepl]
    [taoensso.timbre :as timbre])
  (:gen-class))

(defn- generate-report [criteria callback]
  (let [resp (scheduler/generate-report criteria)]
    (-> (response (format "%s(%s);" callback (json/generate-string resp)))
        (content-type "text/javascript")
        (charset "UTF-8"))))

(defn- invalidate-cache [params]
  (let [criteria (json/parse-string (:criteria params))] ; TODO: validation
    (response (scheduler/invalidate-cache criteria))))

(defroutes ^:private endpoints
  (GET "/" [] "OK")

  (GET "/generate" [:as {params :params}]
    (let [criteria (json/parse-string (:criteria params))  ;; TODO: validation
          callback (:callback params)]
      (if (or (nil? criteria) (= "null" criteria))
        {:status 400 :headers {} :body "Bad Request"}
        (generate-report criteria callback))))

  ; example of params: {"uploadUrl": "https://flowaglimmerofhope.s3.amazonaws.com/", "cascadeResourceId": "22164001", "version": "1"}
  (POST "/publish_cascade" req
    (-> req
      :body
      slurp
      json/parse-string
      cascade/schedule-publish-cascade
      json/generate-string
      response
      (content-type "application/json")
      (charset "UTF-8")))

  (GET "/status" []
    (-> {:cache (keys @scheduler/cache)}
      json/generate-string
      response
      (content-type "application/json")
      (charset "UTF-8")))

  (POST "/invalidate" [:as {params :params}]
    (invalidate-cache params))

  (OPTIONS "/upload" [:as {params :params}]
    (header (response "OK") "Access-Control-Allow-Origin" "*"))

  (POST "/upload" [:as {params :params}]
    (if (:file params)
      (-> params
        uploader/save-chunk
        response
        (header "Access-Control-Allow-Origin" "*")
        (header "Content-Type" "text/plain"))
      (if (:complete params)              ;; 1.8.x
        (let [processor (if (:cascadeResourceId params)
                          cascade/schedule-upload-cascade
                          scheduler/process-and-upload)]
          (-> params
            processor
            :status
            response
            (header "Access-Control-Allow-Origin" "*")
            (header "Content-Type" "text/plain")))
        (-> params                        ;; 1.7.x
          (scheduler/process-and-upload)
          :status
          response
          (header "Access-Control-Allow-Origin" "*")
          (header "Content-Type" "text/plain")))))

  (POST "/reload" [params]
    (config/reload (:config-folder @config/settings)))

  (route/resources "/")

  (route/not-found "Page not found"))

(defn init []
  (quartzite-scheduler/initialize)
  (quartzite-scheduler/start))

(def app (handler/site endpoints))

(defonce nrepl-srv (atom nil))

(defn -main [config-file]
  (when-let [cfg (config/set-settings! config-file)]
    (config/reload (:config-folder cfg))
    (init)
    (stats/schedule-stats-job (:stats-schedule-time cfg))
    (reset! nrepl-srv (nrepl/start-server :port 7888))
    (timbre/set-level! (or (:log-level cfg) :info))
    (timbre/merge-config! timbre/example-config {:timestamp-pattern "yyyy-MM-dd HH:mm:ss,SSS"})
    (run-jetty #'app {:join? false :port (:http-port cfg)})))
