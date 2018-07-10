(ns akvo.flow-services.error)

(defn fmap [v f-error f-ok]
  (if-let [error (get v ::error)]
    (f-error error)
    (f-ok v)))

(defn error
  [e]
  {:pre [(some? e)]}
  {::error e})

(defn if-ok [v f]
  (fmap v (constantly v) f))

(defn unwrap-throwing [v]
  (fmap v
        (fn [e]
          (throw (if-let [cause (:cause e)]
                   (RuntimeException. (:message e) cause)
                   (RuntimeException. (:message e)))))
        identity))

(defmacro wrap-exceptions [body]
  `(try
     ~body
     (catch Exception e# (error {:cause e#}))))

(defn user-friendly-message [{:keys [::error]}]
  (or (:message error)
      (some-> error :cause .getMessage)))

(defn error? [report]
  (::error report))

(def ok? (complement error?))