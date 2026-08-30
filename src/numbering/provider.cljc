(ns numbering.provider
  "Telnyx effect executor. Credentials are accepted as arguments and are never
  returned, persisted or included in exception data."
  (:require [kotoba.lang.json :as json]
            [numbering.telnyx :as telnyx])
  #?(:clj (:import [java.net URI]
                   [java.net.http HttpClient HttpClient$Redirect HttpRequest
                    HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
                   [java.time Duration])))

(defn- keywordize [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(keyword k) (keywordize v)])) x)
    (vector? x) (mapv keywordize x)
    :else x))

(defn execute!
  "Execute one pure Telnyx request map. `api-key` is used only for the
  Authorization header. Returns refusal as data and never includes the key."
  [api-key {:keys [method url body]}]
  #?(:clj
     (if-not (and (string? api-key) (seq api-key))
       {:ok? false :status 503 :error "TELNYX_API_KEY is not configured"}
       (try
         (let [client (-> (HttpClient/newBuilder)
                          (.connectTimeout (Duration/ofSeconds 10))
                          (.followRedirects HttpClient$Redirect/NEVER)
                          (.build))
               builder (-> (HttpRequest/newBuilder (URI/create url))
                           (.timeout (Duration/ofSeconds 25))
                           (.header "Accept" "application/json")
                           (.header "Authorization" (str "Bearer " api-key)))
               builder (if body
                         (-> builder
                             (.header "Content-Type" "application/json")
                             (.method (.toUpperCase (name method))
                                      (HttpRequest$BodyPublishers/ofString
                                       (json/encode body))))
                         (.method (.toUpperCase (name method))
                                  (HttpRequest$BodyPublishers/noBody)))
               response (.send client (.build builder)
                               (HttpResponse$BodyHandlers/ofString))
               status (.statusCode response)
               payload (try (some-> (.body response) json/decode keywordize)
                            (catch Exception _ nil))]
           (if (<= 200 status 299)
             {:ok? true :status status :payload payload}
             {:ok? false :status status
              :error (or (get-in payload [:errors 0 :detail])
                         (get-in payload [:errors 0 :title])
                         "Telnyx refused the request")}))
         (catch Exception e
           {:ok? false :status 502 :error (or (.getMessage e) "Telnyx transport failed")})))
     :cljs
     {:ok? false :status 501 :error "This host has no Telnyx executor"}))

(defn mock-execute
  "Deterministic non-network actuator used by tests and local qualification."
  [{:keys [op request reference]}]
  (case op
    :number/allocate
    (let [msisdn (get-in request [:body :phone_numbers 0 :phone_number])]
      {:ok? true :status 200
       :payload {:data {:id (str "mock-order-" reference)
                        :status "success"
                        :requirements_met true
                        :phone_numbers [{:id (str "mock-number-" reference)
                                         :phone_number msisdn}]}}})
    :number/release {:ok? true :status 200 :payload {:data {:deleted true}}}
    {:ok? false :status 422 :error "mock provider does not support this op"}))

