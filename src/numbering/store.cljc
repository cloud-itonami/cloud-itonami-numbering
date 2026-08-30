(ns numbering.store
  "The actor-owned numbering ledger. Pure data in, pure data out.

  The HTTP host may persist this map, but provider keys and operator tokens never
  enter it. A proposal, an operator decision and a provider outcome are separate
  ledger entries because approval is not proof that a number was purchased."
  (:require [kotoba.phone :as phone]))

(def schema "cloud-itonami.numbering.v1")

(defn empty-state []
  {:numbering/schema schema
   :numbering/proposals {}
   :numbering/numbers {}
   :numbering/ledger []})

(defn proposal [state reference]
  (get-in state [:numbering/proposals reference]))

(defn number [state msisdn]
  (when-let [m (phone/normalize-e164 msisdn)]
    (get-in state [:numbering/numbers m])))

(defn append-event
  [state event]
  (update state :numbering/ledger conj (assoc event :numbering/schema schema)))

(defn put-proposal
  [state proposal]
  (let [reference (:numbering.proposal/reference proposal)]
    (-> state
        (assoc-in [:numbering/proposals reference] proposal)
        (append-event {:numbering.event/type :proposal/recorded
                       :numbering.event/reference reference
                       :numbering.event/status (:numbering.proposal/status proposal)
                       :numbering.event/at (:numbering.proposal/changed-at proposal)}))))

(defn update-proposal
  [state reference f & args]
  (let [state' (apply update-in state [:numbering/proposals reference] f args)
        p (proposal state' reference)]
    (append-event state'
                  {:numbering.event/type :proposal/changed
                   :numbering.event/reference reference
                   :numbering.event/status (:numbering.proposal/status p)
                   :numbering.event/at (:numbering.proposal/changed-at p)})))

(defn put-number
  [state record]
  (let [msisdn (:phone/msisdn record)]
    (-> state
        (assoc-in [:numbering/numbers msisdn] record)
        (append-event {:numbering.event/type :number/changed
                       :numbering.event/msisdn msisdn
                       :numbering.event/state (:phone/state record)
                       :numbering.event/subject (:phone/subject record)
                       :numbering.event/at (:phone/changed-at-ms record)}))))

(defn public-number
  "The routing record returned to authenticated clients. Provider order IDs are
  retained for audit; provider credentials are structurally absent."
  [record]
  (select-keys record
               [:phone/msisdn :phone/state :phone/subject :phone/changed-at-ms
                :numbering/assignee :numbering/assignee-kind :numbering/route
                :numbering/provider :numbering/provider-number-id
                :numbering/provider-order-id :numbering/capabilities]))

