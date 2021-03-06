;  Copyright (C) 2013-2014,2019-2021 Stichting Akvo (Akvo Foundation)
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
  (:require [compojure.core :refer [defroutes GET POST OPTIONS routes]]
            [ring.util.response :refer (response charset content-type header)]
            [ring.adapter.jetty :refer (run-jetty)]
            [cheshire.core :as json]
            [compojure [handler :as handler] [route :as route]]
            [clojurewerkz.quartzite.scheduler :as quartzite-scheduler]
            [aero.core :as aero]
            [akvo.commons.config :as config]
            [akvo.flow-services [scheduler :as scheduler]
             [uploader :as uploader]
             [cascade :as cascade]
             [stats :as stats]
             [exporter :as exporter]
             [aws-s3 :as s3]]
            [nrepl.server :as nrepl]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.3rd-party.sentry :as sentry]
            timbre-ns-pattern-level)
  (:gen-class))

(defn- generate-report [criteria callback]
  (let [resp (scheduler/generate-report criteria)]
    (-> (response (format "%s(%s);" callback (json/generate-string resp)))
        (content-type "text/javascript")
        (charset "UTF-8"))))

(defn- invalidate-cache [params]
  (let [criteria (json/parse-string (:criteria params))] ; TODO: validation
    (response (scheduler/invalidate-cache criteria))))

(defn- null? [x]
  (or (nil? x) (= "null" x)))

(defroutes ^:private endpoints
  (GET "/" [] "OK")
  (GET "/healthz" [] "OK")

  (GET "/sign" [:as {params :params}]
    (s3/handler params))

  (GET "/generate" [:as {params :params}]
    (let [criteria (json/parse-string (:criteria params))  ;; TODO: validation
          callback (:callback params)]
      (if (or (null? criteria)
              (null? (get criteria "surveyId"))
              (null? (get criteria "baseURL")))
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

  (OPTIONS "/bulk_image_upload" [:as {params :params}]
      (header (response "OK") "Access-Control-Allow-Origin" "*"))

  (POST "/bulk_image_upload" [:as {params :params}]
    (timbre/debugf "Uploading with params: %s " params)
    (if (:file params)
      (-> params
          uploader/save-chunk
          response
          (header "Access-Control-Allow-Origin" "*")
          (header "Content-Type" "text/plain"))
      (if (:complete params)
        (-> params
            (scheduler/image-bulk-upload)
            :status
            response
            (header "Access-Control-Allow-Origin" "*")
            (header "Content-Type" "text/plain"))
        {:status 400 :headers {} :body "Bad Request"})))

  (POST "/reload" [params]
    (config/reload (:config-folder @config/settings)))

  (GET ["/survey/:bucket/:survey-id", :survey-id #"[0-9]+"] [bucket survey-id]
       (exporter/export-survey-definition bucket (Long/valueOf survey-id)))

  (route/resources "/")

  (route/not-found "Page not found"))

(defn init []
  (quartzite-scheduler/initialize)
  (quartzite-scheduler/start))

(defonce system (atom nil))

(defn log-level [cfg]
  (:log-level cfg :info))

(defn config-logging [cfg]
  (timbre/handle-uncaught-jvm-exceptions!)
  (timbre/set-level! (log-level cfg))
  (timbre/merge-config! {:timestamp-opts {:pattern "yyyy-MM-dd HH:mm:ss,SSS"}
                         :output-fn (partial timbre/default-output-fn {:stacktrace-fonts {}})})
  (when-let [akvo-log-level (:akvo-log-level cfg)]
    (when (timbre/level>= (log-level cfg) akvo-log-level)
      (timbre/set-level! akvo-log-level))
    (timbre/merge-config!
      {:middleware [(timbre-ns-pattern-level/middleware {"akvo.*"                akvo-log-level
                                                         "org.akvo.*"            akvo-log-level
                                                         "org.waterforpeople.*"  akvo-log-level
                                                         "com.gallatinsystems.*" akvo-log-level
                                                         :all                    (log-level cfg)})]}))
  (let [{:keys [dsn env host version]} (:sentry cfg)]
    (when dsn
      (timbre/merge-config! {:appenders {:sentry (-> (sentry/sentry-appender dsn
                                                                             {:environment env
                                                                              :release     version
                                                                              :event-fn    (fn [event]
                                                                                             (assoc event :server_name host))})
                                                     (assoc :min-level :error))}}))))

(defn wrap-content-disposition [handler]
  (fn [req]
    (if-let [res (handler req)]
      (ring.util.response/header
        res
        "Content-Disposition" "attachment"))))

(defn -main [config-file]
  (when-let [cfg (reset! config/settings (aero/read-config config-file))]
    (config-logging cfg)
    (config/reload (:config-folder cfg))
    (when-let [errors-config (seq @config/errors-config)]
      (doseq [error-config errors-config]
        (timbre/log :error "Config error" error-config)))
    (init)
    (stats/schedule-stats-job (:stats-schedule-time cfg))
    (reset! system {:nrepl (nrepl/start-server :port 7888 :bind (:nrepl-bind cfg "localhost"))
                    :jetty (run-jetty (handler/site (routes
                                                      ;; This route is using the config, so we need to create it after the
                                                      ;; config has been loaded
                                                      (wrap-content-disposition
                                                        (route/files "/report/" {:root (exporter/get-report-directory)
                                                                                 :mime-types {"xlsx" "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"}}))
                                                      #'endpoints))
                             {:join? false :port (:http-port cfg)})})))

(comment
  (reset! config/settings (aero/read-config "dev/config.edn"))
  (config-logging (aero/read-config "dev/config.edn"))

  (.stop (:jetty @system)))
