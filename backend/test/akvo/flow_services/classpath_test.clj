(ns akvo.flow-services.classpath-test
  (:require [clojure.test :refer :all]))

(deftest test-remote-api-unshaded-deps-are-on-the-classpath
  ;; appengine-remote-api repackages google-http-client but leaves its gson and
  ;; opencensus references unshaded, and declares neither. Nothing else supplies them,
  ;; so RemoteApiInstaller.install dies on the first datastore call. One class per
  ;; missing artifact; the jar has 22 such references and these cover all three.
  (are [c] (Class/forName c)
    "com.google.gson.stream.JsonWriter"                  ; gson
    "io.opencensus.trace.propagation.TextFormat$Setter"  ; opencensus-api
    "io.opencensus.contrib.http.util.HttpPropagationUtil")) ; opencensus-contrib-http-util
