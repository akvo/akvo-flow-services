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
    [akvo.flow-services [scheduler :as scheduler] [uploader :as uploader]
    [config :as config] [stats :as stats]])
  (:gen-class))

(defn- generate-report [params]
  (let [criteria (json/parse-string (:criteria params)) ; TODO: validation
        callback (:callback params)
        resp (scheduler/generate-report criteria)]
    (-> (response (format "%s(%s);" callback (json/generate-string resp)))
        (content-type "text/javascript")
        (charset "UTF-8"))))

(defn- invalidate-cache [params]
  (let [criteria (json/parse-string (:criteria params))] ; TODO: validation
    (response (scheduler/invalidate-cache criteria))))

(defroutes ^:private endpoints
  (GET "/" [] "OK")

  (GET "/generate" [:as {params :params}]
    (generate-report params))

  (GET "/status" []
    (-> {:cache (keys @scheduler/cache)}
      json/generate-string
      response
      (content-type "application/json")
      (charset "UTF-8")))

  (POST "/invalidate" [:as {params :params}]
    (invalidate-cache params))
  
  (POST "/upload" [:as {params :params}]
    (if (contains? params :file)
      (-> (response (uploader/save-chunk params))
        (header "Access-Control-Allow-Origin" "*"))
      (-> (response (:status (scheduler/process-and-upload params)))
        (header "Access-Control-Allow-Origin" "*"))))

  (POST "/reload" [params]
    (config/reload (:config-folder @config/settings)))
  
  (OPTIONS "/upload" [:as {params :params}] 
    (-> (response "OK")
      (header "Access-Control-Allow-Origin" "*")))
  
  (route/resources "/")

  (route/not-found "Page not found"))

(defn init []
  (quartzite-scheduler/initialize)
  (quartzite-scheduler/start))

(def app (handler/site endpoints))

(defn -main [config-file]
  (when-let [cfg (config/set-settings! config-file)]
    (config/reload (:config-folder cfg))
    (init)
    (stats/schedule-stats-job (:stats-schedule-time cfg))
    (run-jetty #'app {:join? false :port (:http-port cfg)})))
