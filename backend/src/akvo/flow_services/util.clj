(ns akvo.flow-services.util
  (:require [akvo.commons.config :as config]))

(defn datastore-spec [app-id-or-bucket]
  (let [cfg (config/find-config app-id-or-bucket)]
    (if (= "flowservices-dev-config" (:app-id cfg))
      {:hostname           "localhost"
       :port               8888}
      {:hostname           (:domain cfg)
       :service-account-id (:service-account-id cfg)
       :private-key-file   (:private-key-file cfg)
       :port               443})))