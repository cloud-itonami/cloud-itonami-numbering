(ns numbering.service
  "The governed number-inbox service.

  `accept` never calls a provider. `decide` records the named operator decision
  and returns an effect only after approval. `complete` records the provider
  outcome. The host performs that effect between decide and complete, so tests
  can prove that consent alone cannot spend money or acquire a number."
  (:require [clojure.string :as str]
            [kotoba.phone.lifecycle :as lifecycle]
            [kotoba.phone.numbering :as numbering]
            [numbering.governor :as governor]
            [numbering.store :as store]
            [numbering.telnyx :as telnyx]))

(defn- kw [x] (cond (keyword? x) x (string? x) (keyword x) :else x))
(defn- now-string [now-ms] (str now-ms))

(defn accept
  [state proposal now-ms policy]
  (let [reference (:id proposal)
        issues (governor/proposal-issues state proposal now-ms policy)]
    (cond
      (or (nil? reference) (and (string? reference) (str/blank? reference)))
      {:state state :response {:status "held" :refusal {:rule :proposal/reference-missing}}}

      (store/proposal state reference)
      (let [existing (store/proposal state reference)]
        {:state state
         :response (case (:numbering.proposal/status existing)
                     :pending {:status "pending" :reference reference}
                     :committed {:status "committed" :record (:numbering.proposal/record existing)}
                     :approved-not-actuated
                     {:status "approved-not-actuated"
                      :approval-recorded true
                      :decided-by (:numbering.proposal/decided-by existing)
                      :refusal (:numbering.proposal/refusal existing)}
                     {:status "held" :refusal (:numbering.proposal/refusal existing)})})

      (seq issues)
      {:state state :response {:status "held" :refusal {:rule :governor-refused
                                                         :issues issues}}}

      :else
      (let [p {:numbering.proposal/reference reference
               :numbering.proposal/organization-id (:organization-id proposal)
               :numbering.proposal/op (kw (:op proposal))
               :numbering.proposal/value (:value proposal)
               :numbering.proposal/digest (:digest proposal)
               :numbering.proposal/passkey-credential-id (:passkey-credential-id proposal)
               :numbering.proposal/status :pending
               :numbering.proposal/changed-at (now-string now-ms)}]
        {:state (store/put-proposal state p)
         :response {:status "pending" :reference reference}}))))

(defn decide
  "Return an optional provider effect. Calling this twice never returns a second
  effect; the reference is the provider idempotency boundary."
  [state reference {:keys [decision by]} now-ms provider-config]
  (let [p (store/proposal state reference)
        decision (kw decision)]
    (cond
      (nil? p)
      {:state state :response {:status "unknown" :detail "proposal not found"}}

      (not= :pending (:numbering.proposal/status p))
      {:state state
       :response (case (:numbering.proposal/status p)
                   :committed {:status "committed" :record (:numbering.proposal/record p)}
                   :approved-not-actuated
                   {:status "approved-not-actuated" :approval-recorded true
                    :decided-by (:numbering.proposal/decided-by p)
                    :refusal (:numbering.proposal/refusal p)}
                   {:status "held" :refusal (:numbering.proposal/refusal p)})}

      (or (not (string? by)) (str/blank? by))
      {:state state :response {:status "held" :refusal {:rule :operator/by-missing}}}

      (= :reject decision)
      (let [refusal {:rule :operator/rejected}]
        {:state (store/update-proposal state reference merge
                                       {:numbering.proposal/status :held
                                        :numbering.proposal/refusal refusal
                                        :numbering.proposal/decided-by by
                                        :numbering.proposal/changed-at (now-string now-ms)})
         :response {:status "held" :refusal refusal}})

      (not= :approve decision)
      {:state state :response {:status "held" :refusal {:rule :operator/decision-invalid}}}

      (not= :telnyx (kw (:provider provider-config)))
      (let [refusal {:rule :no-actuator-configured}]
        {:state (store/update-proposal state reference merge
                                       {:numbering.proposal/status :approved-not-actuated
                                        :numbering.proposal/refusal refusal
                                        :numbering.proposal/decided-by by
                                        :numbering.proposal/changed-at (now-string now-ms)})
         :response {:status "approved-not-actuated" :approval-recorded true
                    :decided-by by :refusal refusal}})

      (not (true? (:configured? provider-config)))
      (let [refusal {:rule :no-actuator-configured}]
        {:state (store/update-proposal state reference merge
                                       {:numbering.proposal/status :approved-not-actuated
                                        :numbering.proposal/refusal refusal
                                        :numbering.proposal/decided-by by
                                        :numbering.proposal/changed-at (now-string now-ms)})
         :response {:status "approved-not-actuated" :approval-recorded true
                    :decided-by by :refusal refusal}})

      (and (= :number/allocate (:numbering.proposal/op p))
           (str/starts-with? (get-in p [:numbering.proposal/value :msisdn] "") "+81")
           (not (or (seq (:requirement-group-id provider-config))
                    (seq (:bundle-id provider-config)))))
      (let [refusal {:rule :japan-requirement-config-missing}]
        {:state (store/update-proposal state reference merge
                                       {:numbering.proposal/status :approved-not-actuated
                                        :numbering.proposal/refusal refusal
                                        :numbering.proposal/decided-by by
                                        :numbering.proposal/changed-at (now-string now-ms)})
         :response {:status "approved-not-actuated" :approval-recorded true
                    :decided-by by :refusal refusal}})

      :else
      (let [value (:numbering.proposal/value p)
            op (:numbering.proposal/op p)
            request (case op
                      :number/allocate
                      (telnyx/order-request
                       (merge provider-config
                              {:msisdn (:msisdn value)
                               :customer-reference reference}))
                      :number/release
                      (telnyx/release-request
                       (:numbering/provider-number-id
                        (store/number state (:msisdn value)))))
            approved (store/update-proposal state reference merge
                                            {:numbering.proposal/status :actuating
                                             :numbering.proposal/decided-by by
                                             :numbering.proposal/changed-at (now-string now-ms)})]
        {:state approved
         :effect {:reference reference :op op :request request}
         :response {:status "actuating" :reference reference}}))))

(defn reconcile-order
  "Resume an already-created Telnyx order by its provider order id.

  This is operator-only at the HTTP edge. It never creates a second order: the
  only effect it can return is GET /number_orders/:id for a proposal whose last
  provider outcome was pending."
  [state reference {:keys [by]} now-ms]
  (let [p (store/proposal state reference)
        refusal (:numbering.proposal/refusal p)
        order-id (:provider-order-id refusal)]
    (cond
      (nil? p)
      {:state state :response {:status "unknown" :detail "proposal not found"}}

      (= :committed (:numbering.proposal/status p))
      {:state state :response {:status "committed"
                               :record (:numbering.proposal/record p)}}

      (or (not (string? by)) (str/blank? by))
      {:state state :response {:status "held" :refusal {:rule :operator/by-missing}}}

      (not (and (= :approved-not-actuated (:numbering.proposal/status p))
                (= :provider-pending (:rule refusal))
                (string? order-id) (not (str/blank? order-id))))
      {:state state :response {:status "held"
                               :refusal {:rule :provider/order-not-reconcilable}}}

      :else
      {:state (store/update-proposal state reference merge
                                     {:numbering.proposal/status :actuating
                                      :numbering.proposal/reconciled-by by
                                      :numbering.proposal/changed-at (now-string now-ms)})
       :effect {:reference reference
                :op :number/allocate-status
                :request (telnyx/order-status-request order-id)}
       :response {:status "actuating" :reference reference}})))

(defn complete
  [state {:keys [reference op]} provider-result now-ms]
  (let [p (store/proposal state reference)]
    (cond
      (nil? p)
      {:state state :response {:status "unknown" :detail "proposal not found"}}

      (not= :actuating (:numbering.proposal/status p))
      {:state state :response {:status "held" :refusal {:rule :proposal/not-actuating}}}

      (not (:ok? provider-result))
      (let [refusal {:rule :provider-refused
                     :provider-status (:status provider-result)
                     :detail (:error provider-result)}]
        {:state (store/update-proposal state reference merge
                                       {:numbering.proposal/status :approved-not-actuated
                                        :numbering.proposal/refusal refusal
                                        :numbering.proposal/changed-at (now-string now-ms)})
         :response {:status "approved-not-actuated" :approval-recorded true
                    :decided-by (:numbering.proposal/decided-by p)
                    :refusal refusal}})

      (contains? #{:number/allocate :number/allocate-status} op)
      (let [outcome (telnyx/order-outcome (:payload provider-result))
            value (:numbering.proposal/value p)]
        (if (and (= :success (:status outcome)) (:requirements-met? outcome))
          (let [record (merge
                        (numbering/record (:msisdn value) :assigned
                                          :subject (:subject value)
                                          :changed-at-ms now-ms)
                        {:numbering/assignee (:assignee value)
                         :numbering/assignee-kind (kw (:assignee-kind value))
                         :numbering/route (:route value)
                         :numbering/provider :telnyx
                         :numbering/provider-number-id (:provider-number-id outcome)
                         :numbering/provider-order-id (:provider-order-id outcome)
                         :numbering/capabilities (set (:capabilities value))})
                public (store/public-number record)
                state' (-> state
                           (store/put-number record)
                           (store/update-proposal reference merge
                                                  {:numbering.proposal/status :committed
                                                   :numbering.proposal/record public
                                                   :numbering.proposal/refusal nil
                                                   :numbering.proposal/changed-at (now-string now-ms)}))]
            {:state state' :response {:status "committed" :record public}})
          (let [refusal {:rule :provider-pending
                         :provider-order-id (:provider-order-id outcome)
                         :provider-status (:status outcome)
                         :requirements-met (:requirements-met? outcome)}]
            {:state (store/update-proposal state reference merge
                                           {:numbering.proposal/status :approved-not-actuated
                                            :numbering.proposal/refusal refusal
                                            :numbering.proposal/changed-at (now-string now-ms)})
             :response {:status "approved-not-actuated" :approval-recorded true
                        :decided-by (:numbering.proposal/decided-by p)
                        :refusal refusal}})))

      (= :number/release op)
      (let [value (:numbering.proposal/value p)
            record (store/number state (:msisdn value))
            released (lifecycle/apply-event [record] (:phone/msisdn record) :release)
            quarantined (when (:phone/ok? released)
                          (lifecycle/apply-event (:phone/records released)
                                                 (:phone/msisdn record) :quarantine))]
        (if (:phone/ok? quarantined)
          (let [record' (assoc (first (:phone/records quarantined))
                               :phone/changed-at-ms now-ms
                               :phone/released-at-ms now-ms)
                public (store/public-number record')
                state' (-> state
                           (store/put-number record')
                           (store/update-proposal reference merge
                                                  {:numbering.proposal/status :committed
                                                   :numbering.proposal/record public
                                                   :numbering.proposal/changed-at (now-string now-ms)}))]
            {:state state' :response {:status "committed" :record public}})
          {:state state :response {:status "held" :refusal {:rule :number/release-unreachable}}}))

      :else
      {:state state :response {:status "held" :refusal {:rule :proposal/op-unsupported}}})))

(defn status [state reference]
  (if-let [p (store/proposal state reference)]
    (case (:numbering.proposal/status p)
      :pending {:status "pending" :reference reference}
      :actuating {:status "pending" :reference reference :detail "provider call in progress"}
      :committed {:status "committed" :record (:numbering.proposal/record p)}
      :approved-not-actuated {:status "approved-not-actuated"
                              :approval-recorded true
                              :decided-by (:numbering.proposal/decided-by p)
                              :refusal (:numbering.proposal/refusal p)}
      {:status "held" :refusal (:numbering.proposal/refusal p)})
    {:status "unknown" :detail "proposal not found"}))
