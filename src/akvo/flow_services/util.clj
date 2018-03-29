(ns akvo.flow-services.util
  (:require [akvo.commons.config :as config]))

(defn datastore-spec [app-id-or-bucket]
  (let [cfg (config/find-config app-id-or-bucket)]
    {:hostname (:domain cfg)
     :service-account-id (:service-account-id cfg)
     :private-key-file (:private-key-file cfg)
     :port 443}))