(ns numbering.service-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [numbering.governor :as governor]
            [numbering.provider :as provider]
            [numbering.service :as service]
            [numbering.store :as store]
            [numbering.telnyx :as telnyx]))

(def now 1800000000000)

(defn proposal
  ([id] (proposal id :number/allocate))
  ([id op]
   {:id id
    :organization-id "org-1"
    :authority :number
    :op op
    :status :approved
    :approved-at "2027-01-15T08:00:00Z"
    :passkey-credential-id "passkey-1"
    :digest "sha256:proposal"
    :value (if (= op :number/allocate)
             {:msisdn "+815012340001"
              :subject "did:key:owner"
              :assignee "did:web:bot.example"
              :assignee-kind :bot
              :route "did:web:bot.example"
              :provider :telnyx
              :capabilities [:voice :sms]
              :quote {:provider :telnyx :upfront "1.00" :monthly "2.00"
                      :currency "USD" :observed-at now}}
             {:msisdn "+815012340001" :subject "did:key:owner"})}))

(def policy {:max-upfront 5.0 :max-monthly 5.0 :max-quote-age-ms 300000})
(def configured {:provider :telnyx :configured? true
                 :mode :mock :requirement-group-id "rg-test"
                 :connection-id "conn-test" :messaging-profile-id "msg-test"})

(defn allocate-state []
  (let [accepted (service/accept (store/empty-state) (proposal "p-1") now policy)
        started (service/decide (:state accepted) "p-1"
                                {:decision :approve :by "operator@example"}
                                now configured)
        completed (service/complete (:state started) (:effect started)
                                    (provider/mock-execute (:effect started)) (inc now))]
    (:state completed)))

(deftest telnyx-request-contract
  (let [search (telnyx/search-request {:country-code "JP" :features [:voice :sms] :limit 3})
        order (telnyx/order-request {:msisdn "+815012340001"
                                     :requirement-group-id "rg"
                                     :connection-id "conn"
                                     :customer-reference "p-1"})]
    (is (= :get (:method search)))
    (is (str/includes? (:url search) "filter%5Bcountry_code%5D=JP"))
    (is (str/includes? (:url search) "filter%5Bfeatures%5D%5B0%5D=voice"))
    (is (= :post (:method order)))
    (is (= "+815012340001" (get-in order [:body :phone_numbers 0 :phone_number])))
    (is (= "rg" (get-in order [:body :phone_numbers 0 :requirement_group_id])))
    (is (= "p-1" (get-in order [:body :customer_reference])))))

(deftest consent-and-price-are-hard-gates
  (testing "an unapproved envelope cannot become pending"
    (let [result (service/accept (store/empty-state)
                                 (assoc (proposal "bad") :status :awaiting-passkey)
                                 now policy)]
      (is (= "held" (get-in result [:response :status])))
      (is (empty? (:numbering/proposals (:state result))))))
  (testing "a stale or over-limit quote cannot reach an operator"
    (doseq [p [(assoc-in (proposal "stale") [:value :quote :observed-at] (- now 400000))
               (assoc-in (proposal "expensive") [:value :quote :monthly] "50.00")]]
      (let [result (service/accept (store/empty-state) p now policy)]
        (is (= "held" (get-in result [:response :status])))
        (is (empty? (:numbering/proposals (:state result)))))))
  (testing "the route is an actor/topic name, never an arbitrary callback URL"
    (let [result (service/accept (store/empty-state)
                                 (assoc-in (proposal "ssrf") [:value :route]
                                           "https://169.254.169.254/latest/meta-data")
                                 now policy)]
      (is (= "held" (get-in result [:response :status]))))))

(deftest consent-never-purchases
  (let [accepted (service/accept (store/empty-state) (proposal "p-1") now policy)]
    (is (= "pending" (get-in accepted [:response :status])))
    (is (empty? (:numbering/numbers (:state accepted))))
    (is (= :pending (get-in accepted [:state :numbering/proposals "p-1"
                                      :numbering.proposal/status])))))

(deftest operator-and-provider-are-separate-events
  (let [accepted (service/accept (store/empty-state) (proposal "p-1") now policy)
        no-provider (service/decide (:state accepted) "p-1"
                                    {:decision :approve :by "operator@example"}
                                    now {:provider :telnyx :configured? false})]
    (is (nil? (:effect no-provider)))
    (is (= "approved-not-actuated" (get-in no-provider [:response :status])))
    (is (= :no-actuator-configured
           (get-in no-provider [:response :refusal :rule]))))
  (let [accepted (service/accept (store/empty-state) (proposal "p-2") now policy)
        started (service/decide (:state accepted) "p-2"
                                {:decision :approve :by "operator@example"}
                                now configured)]
    (is (= :number/allocate (get-in started [:effect :op])))
    (is (= :actuating (get-in started [:state :numbering/proposals "p-2"
                                       :numbering.proposal/status])))))

(deftest one-number-is-issued-to-one-bot-once
  (let [state (allocate-state)
        record (store/number state "+815012340001")
        retry (service/decide state "p-1" {:decision :approve :by "operator@example"}
                              (+ now 2) configured)]
    (is (= :assigned (:phone/state record)))
    (is (= "did:key:owner" (:phone/subject record)))
    (is (= "did:web:bot.example" (:numbering/assignee record)))
    (is (= :bot (:numbering/assignee-kind record)))
    (is (= "did:web:bot.example" (:numbering/route record)))
    (is (= "committed" (get-in retry [:response :status])))
    (is (nil? (:effect retry)) "a repeated approval must not place a second order")))

(deftest pending-order-is-reconciled-without-a-second-purchase
  (let [accepted (service/accept (store/empty-state) (proposal "p-pending") now policy)
        started (service/decide (:state accepted) "p-pending"
                                {:decision :approve :by "operator@example"}
                                now configured)
        pending-result {:ok? true :status 200
                        :payload {:data {:id "order-pending-1"
                                         :status "pending"
                                         :requirements_met false
                                         :phone_numbers []}}}
        pending (service/complete (:state started) (:effect started)
                                  pending-result (inc now))
        resumed (service/reconcile-order (:state pending) "p-pending"
                                         {:by "operator@example"} (+ now 2))
        completed (service/complete (:state resumed) (:effect resumed)
                                    (provider/mock-execute (:effect resumed)) (+ now 3))
        retry (service/reconcile-order (:state completed) "p-pending"
                                       {:by "operator@example"} (+ now 4))]
    (is (= "approved-not-actuated" (get-in pending [:response :status])))
    (is (= "order-pending-1"
           (get-in pending [:response :refusal :provider-order-id])))
    (is (= :number/allocate-status (get-in resumed [:effect :op])))
    (is (str/ends-with? (get-in resumed [:effect :request :url])
                        "/number_orders/order-pending-1"))
    (is (= "committed" (get-in completed [:response :status])))
    (is (= :assigned (:phone/state (store/number (:state completed)
                                                  "+815012340001"))))
    (is (= "committed" (get-in retry [:response :status])))
    (is (nil? (:effect retry)))))

(deftest release-is-owned-gated-and-quarantined
  (let [allocated (allocate-state)
        wrong (service/accept allocated
                              (assoc-in (proposal "r-bad" :number/release)
                                        [:value :subject] "did:key:attacker")
                              (+ now 10) policy)
        accepted (service/accept allocated (proposal "r-1" :number/release)
                                 (+ now 10) policy)
        started (service/decide (:state accepted) "r-1"
                                {:decision :approve :by "operator@example"}
                                (+ now 11) configured)
        completed (service/complete (:state started) (:effect started)
                                    (provider/mock-execute (:effect started)) (+ now 12))]
    (is (= "held" (get-in wrong [:response :status])))
    (is (= "pending" (get-in accepted [:response :status])))
    (is (= :quarantined (:phone/state (store/number (:state completed)
                                                     "+815012340001"))))))

(deftest japan-orders-require-recorded-regulatory-coordinates
  (let [accepted (service/accept (store/empty-state) (proposal "jp") now policy)
        result (service/decide (:state accepted) "jp"
                               {:decision :approve :by "operator@example"}
                               now (dissoc configured :requirement-group-id))]
    (is (nil? (:effect result)))
    (is (= :japan-requirement-config-missing
           (get-in result [:response :refusal :rule])))))

(deftest provider-credential-is-required-and-not-echoed
  (let [result (provider/execute! nil {:method :get :url "https://api.telnyx.com/v2/available_phone_numbers"})]
    (is (false? (:ok? result)))
    (is (= 503 (:status result)))
    (is (not (contains? result :api-key)))))
