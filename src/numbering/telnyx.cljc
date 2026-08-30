(ns numbering.telnyx
  "Pure Telnyx API v2 request construction and response interpretation.

  The API key is injected only by the HTTP edge. Request maps deliberately do
  not carry it, so logging a request cannot log a credential. The shapes follow
  Telnyx's official OpenAPI document; provider inventory, prices and regulatory
  admission remain observations, never constants."
  (:require [clojure.string :as str]
            [kotoba.phone :as phone]))

(def api-base "https://api.telnyx.com/v2")
(def provider :telnyx)

(def supported-features #{"voice" "sms" "mms" "fax" "hd_voice" "local_calling"})

(defn- encode-component [x]
  #?(:clj (java.net.URLEncoder/encode (str x) "UTF-8")
     :cljs (js/encodeURIComponent (str x))))

(defn search-request
  [{:keys [country-code number-type features limit]
    :or {country-code "JP" features ["voice"] limit 10}}]
  (let [features (->> features (map name) (filter supported-features) distinct vec)
        limit (-> limit (or 10) long (max 1) (min 100))
        params (cond-> [["filter[country_code]" (str/upper-case country-code)]
                        ["filter[limit]" limit]
                        ["filter[exclude_held_numbers]" "true"]]
                 number-type (conj ["filter[phone_number_type]" (name number-type)])
                 (seq features) (into (map-indexed
                                       (fn [i feature]
                                         [(str "filter[features][" i "]") feature])
                                       features)))]
    {:method :get
     :url (str api-base "/available_phone_numbers?"
               (str/join "&" (map (fn [[k v]]
                                      (str (encode-component k) "=" (encode-component v)))
                                    params)))}))

(defn order-request
  [{:keys [msisdn requirement-group-id bundle-id connection-id
           messaging-profile-id billing-group-id customer-reference]}]
  (let [m (phone/normalize-e164 msisdn)]
    (when-not m
      (throw (ex-info "Telnyx order requires an E.164 number"
                      {:type :telnyx/msisdn-invalid :msisdn msisdn})))
    {:method :post
     :url (str api-base "/number_orders")
     :body (cond-> {:phone_numbers
                    [(cond-> {:phone_number m}
                       requirement-group-id (assoc :requirement_group_id requirement-group-id)
                       bundle-id (assoc :bundle_id bundle-id))]
                    :customer_reference customer-reference}
             connection-id (assoc :connection_id connection-id)
             messaging-profile-id (assoc :messaging_profile_id messaging-profile-id)
             billing-group-id (assoc :billing_group_id billing-group-id))}))

(defn order-status-request [order-id]
  {:method :get :url (str api-base "/number_orders/" (encode-component order-id))})

(defn release-request [provider-number-id]
  {:method :delete
   :url (str api-base "/phone_numbers/" (encode-component provider-number-id))})

(defn available-numbers
  "Normalize a Telnyx search response to the fields this service binds into
  human consent. Unknown/malformed entries are omitted rather than guessed."
  [payload observed-at]
  (->> (:data payload)
       (keep (fn [entry]
               (when-let [m (phone/normalize-e164 (:phone_number entry))]
                 (let [cost (:cost_information entry)]
                   {:numbering/provider provider
                    :numbering/msisdn m
                    :numbering/quote
                    {:provider provider
                     :upfront (:upfront_cost cost)
                     :monthly (:monthly_cost cost)
                     :currency (:currency cost)
                     :observed-at observed-at}
                    :numbering/features (->> (:features entry)
                                             (keep :name)
                                             set)
                    :numbering/reservable? (true? (:reservable entry))
                    :numbering/quickship? (true? (:quickship entry))}))))
       vec))

(defn order-outcome [payload]
  (let [data (:data payload)
        number-entry (first (:phone_numbers data))]
    {:provider provider
     :provider-order-id (:id data)
     :provider-number-id (:id number-entry)
     :msisdn (or (:phone_number number-entry) (:phone_number data))
     :status (some-> (:status data) keyword)
     :requirements-met? (true? (:requirements_met data))}))

