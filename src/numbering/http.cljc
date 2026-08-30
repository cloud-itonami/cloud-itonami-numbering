(ns numbering.http
  "Loopback HTTP host for the numbering actor.

  Consent and operator decisions are different listeners. The consent token can
  submit and inspect an already Passkey-approved proposal, but cannot reach the
  decide route. The operator token can decide, but is never accepted by the
  consent listener as a substitute."
  (:require [clojure.string :as str]
            [kotoba.lang.json :as json]
            [numbering.persistence :as persistence]
            [numbering.provider :as provider]
            [numbering.service :as service]
            [numbering.store :as store]
            [numbering.telnyx :as telnyx])
  #?(:clj (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
                   [java.net InetSocketAddress URLDecoder]
                   [java.nio.charset StandardCharsets]
                   [java.util.concurrent Executors])))

#?(:clj (defonce runtime (atom (store/empty-state))))

(defn- keywordize [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(keyword k) (keywordize v)])) x)
    (vector? x) (mapv keywordize x)
    :else x))

(defn- wire [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) (wire v)])) x)
    (set? x) (mapv wire (sort-by str x))
    (sequential? x) (mapv wire x)
    (keyword? x) (name x)
    :else x))

#?(:clj
   (defn- env [name]
     (some-> (System/getenv name) str/trim not-empty)))

#?(:clj
   (defn- env-long [name default]
     (try (Long/parseLong (or (env name) (str default)))
          (catch Exception _ default))))

#?(:clj
   (defn- env-double [name default]
     (try (Double/parseDouble (or (env name) (str default)))
          (catch Exception _ default))))

#?(:clj
   (defn provider-config []
     (let [mode (keyword (or (env "NUMBERING_ACTUATOR") "none"))]
       (cond-> {:provider :telnyx
                :mode mode
                :configured? (or (= :mock mode)
                                  (and (= :telnyx mode) (seq (env "TELNYX_API_KEY"))))
                :requirement-group-id (env "TELNYX_REQUIREMENT_GROUP_ID")
                :bundle-id (env "TELNYX_BUNDLE_ID")
                :connection-id (env "TELNYX_CONNECTION_ID")
                :messaging-profile-id (env "TELNYX_MESSAGING_PROFILE_ID")
                :billing-group-id (env "TELNYX_BILLING_GROUP_ID")}
         (= :mock mode) (assoc :requirement-group-id "mock-requirement-group")))))

#?(:clj
   (defn policy []
     {:max-upfront (env-double "NUMBERING_MAX_UPFRONT" 20.0)
      :max-monthly (env-double "NUMBERING_MAX_MONTHLY" 20.0)
      :max-quote-age-ms (env-long "NUMBERING_MAX_QUOTE_AGE_MS" (* 5 60 1000))}))

#?(:clj
   (defn- state-path []
     (or (env "NUMBERING_STATE_PATH") ".numbering/state.edn")))

#?(:clj
   (defn- decode-body [^HttpExchange exchange]
     (let [body (String. (.readAllBytes (.getRequestBody exchange)) StandardCharsets/UTF_8)]
       (if (str/blank? body) {} (-> body json/decode keywordize)))))

#?(:clj
   (defn- send! [^HttpExchange exchange status payload]
     (let [bytes (.getBytes (json/encode (wire payload)) StandardCharsets/UTF_8)]
       (.set (.getResponseHeaders exchange) "Content-Type" "application/json; charset=utf-8")
       (.sendResponseHeaders exchange status (long (count bytes)))
       (with-open [out (.getResponseBody exchange)] (.write out bytes)))))

#?(:clj
   (defn- token-result [^HttpExchange exchange env-name header-name]
     (let [expected (env env-name)
           actual (.getFirst (.getRequestHeaders exchange) header-name)]
       (cond
         (nil? expected) {:status 503 :body {:status "held" :refusal {:rule :token/unconfigured}}}
         (not= expected actual) {:status 401 :body {:status "held" :refusal {:rule :token/invalid}}}
         :else nil))))

#?(:clj
   (defn- path-parts [^HttpExchange exchange]
     (->> (str/split (.getPath (.getRequestURI exchange)) #"/")
          (remove str/blank?) vec)))

#?(:clj
   (defn- query-map [^HttpExchange exchange]
     (let [q (.getRawQuery (.getRequestURI exchange))]
       (if (str/blank? q) {}
           (into {}
                 (map (fn [part]
                        (let [[k v] (str/split part #"=" 2)]
                          [(keyword (URLDecoder/decode k "UTF-8"))
                           (URLDecoder/decode (or v "") "UTF-8")]))
                      (str/split q #"&")))))))

#?(:clj
   (defn- transact! [f]
     (locking runtime
       (let [{:keys [state] :as result} (f @runtime)]
         (when state
           (reset! runtime state)
           (persistence/save-state! (state-path) state))
         result))))

#?(:clj
   (defn- execute-effect! [effect]
     (case (:mode (provider-config))
       :mock (provider/mock-execute effect)
       :telnyx (provider/execute! (env "TELNYX_API_KEY") (:request effect))
       {:ok? false :status 503 :error "NUMBERING_ACTUATOR is not configured"})))

#?(:clj
   (defn- decide! [reference body]
     (let [started (transact! #(service/decide % reference body
                                               (System/currentTimeMillis)
                                               (provider-config)))]
       (if-let [effect (:effect started)]
         (let [provider-result (execute-effect! effect)]
           (:response
            (transact! #(service/complete % effect provider-result
                                          (System/currentTimeMillis)))))
         (:response started)))))

#?(:clj
   (defn- search! [query]
     (let [features (->> (str/split (or (:features query) "voice") #",")
                         (remove str/blank?) vec)
           request (telnyx/search-request
                    {:country-code (or (:country query) "JP")
                     :number-type (some-> (:type query) keyword)
                     :features features
                     :limit (env-long "NUMBERING_SEARCH_LIMIT"
                                      (try (Long/parseLong (or (:limit query) "10"))
                                           (catch Exception _ 10)))})
           result (case (:mode (provider-config))
                    :mock {:ok? true :status 200
                           :payload {:data [{:phone_number "+815012340001"
                                             :cost_information {:upfront_cost "1.00"
                                                                :monthly_cost "2.00"
                                                                :currency "USD"}
                                             :features [{:name "voice"} {:name "sms"}]
                                             :reservable true :quickship true}]}}
                    :telnyx (provider/execute! (env "TELNYX_API_KEY") request)
                    {:ok? false :status 503 :error "number search provider is not configured"})]
       (if (:ok? result)
         {:status 200
          :body {:schema "cloud-itonami.numbering.available.v1"
                 :data (telnyx/available-numbers (:payload result)
                                                 (System/currentTimeMillis))}}
         {:status (:status result)
          :body {:status "held" :refusal {:rule :provider/search-failed
                                           :detail (:error result)}}}))))

#?(:clj
   (defn- consent-handler [^HttpExchange exchange]
     (let [method (.getRequestMethod exchange)
           parts (path-parts exchange)]
       (cond
         (and (= "GET" method) (= ["healthz"] parts))
         (send! exchange 200 {:status "ok"
                              :schema "cloud-itonami.numbering.health.v1"
                              :provider-mode (name (:mode (provider-config)))
                              :actuator-configured (true? (:configured? (provider-config)))
                              :numbers (count (:numbering/numbers @runtime))})

         :else
         (if-let [{:keys [status body]}
                  (token-result exchange "NUMBER_CONSENT_TOKEN" "X-NUMBER-CONSENT-TOKEN")]
           (send! exchange status body)
           (cond
             (and (= "POST" method) (= ["commit"] parts))
             (let [proposal (:proposal (decode-body exchange))
                   result (transact! #(service/accept % proposal
                                                     (System/currentTimeMillis)
                                                     (policy)))]
               (send! exchange 200 (:response result)))

             (and (= "GET" method) (= "proposals" (first parts)) (= 2 (count parts)))
             (send! exchange 200 (service/status @runtime (second parts)))

             (and (= "GET" method) (= ["v1" "available-numbers"] parts))
             (let [{:keys [status body]} (search! (query-map exchange))]
               (send! exchange status body))

             (and (= "GET" method) (= ["v1" "numbers"] parts))
             (send! exchange 200
                    {:schema "cloud-itonami.numbering.lines.v1"
                     :data (mapv store/public-number
                                 (vals (:numbering/numbers @runtime)))})

             (and (= "GET" method) (= ["v1" "resolve"] parts))
             (let [record (store/number @runtime (:to (query-map exchange)))]
               (if record
                 (send! exchange 200 {:status "found" :line (store/public-number record)})
                 (send! exchange 404 {:status "unknown"})))

             :else (send! exchange 404 {:status "unknown"})))))))

#?(:clj
   (defn- operator-handler [^HttpExchange exchange]
     (let [method (.getRequestMethod exchange)
           parts (path-parts exchange)]
       (cond
         (and (= "GET" method) (= ["healthz"] parts))
         (send! exchange 200 {:status "ok" :surface "operator"})

         :else
         (if-let [{:keys [status body]}
                  (token-result exchange "NUMBER_OPERATOR_TOKEN" "X-NUMBER-OPERATOR-TOKEN")]
           (send! exchange status body)
           (if (and (= "POST" method) (= "proposals" (first parts))
                    (= "decide" (nth parts 2 nil)) (= 3 (count parts)))
             (send! exchange 200 (decide! (second parts) (decode-body exchange)))
             (send! exchange 404 {:status "unknown"})))))))

#?(:clj
   (defn- server [port handler]
     (let [s (HttpServer/create (InetSocketAddress. "127.0.0.1" port) 0)]
       (.createContext s "/" (reify HttpHandler (handle [_ exchange] (handler exchange))))
       (.setExecutor s (Executors/newVirtualThreadPerTaskExecutor))
       (.start s)
       s)))

#?(:clj
   (defn -main [& _]
     (reset! runtime (persistence/load-state (state-path)))
     (let [consent-port (int (env-long "NUMBER_CONSENT_PORT" 1345))
           operator-port (int (env-long "NUMBER_OPERATOR_PORT" 1346))]
       (server consent-port consent-handler)
       (server operator-port operator-handler)
       (println (str "cloud-itonami-numbering consent=127.0.0.1:" consent-port
                     " operator=127.0.0.1:" operator-port
                     " provider=" (name (:mode (provider-config)))))
       @(promise))))

