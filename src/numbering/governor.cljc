(ns numbering.governor
  "Independent, deterministic admission for a number assignment.

  The owner is the Passkey subject. The assignee is an agent/bot DID or actor
  target that receives calls and messages. Keeping those separate means a bot
  can use a number without becoming able to transfer or release it."
  (:require [clojure.string :as str]
            [kotoba.phone :as phone]
            [kotoba.phone.numbering :as numbering]))

(def allowed-assignee-kinds #{:agent :bot})
(def allowed-providers #{:telnyx})
(def allowed-ops #{:number/allocate :number/release})
(def default-max-quote-age-ms (* 5 60 1000))

(defn- present? [x] (and (string? x) (not (str/blank? x))))
(defn- kw [x] (cond (keyword? x) x (string? x) (keyword x) :else x))
(defn- decimal [x]
  (try #?(:clj (Double/parseDouble (str x)) :cljs (js/Number x))
       (catch #?(:clj Exception :cljs :default) _ nil)))

(defn- positive-or-zero? [x]
  (when-let [n (decimal x)] (not (neg? n))))

(defn approved-envelope-issues [proposal]
  (cond-> []
    (not= :approved (kw (:status proposal)))
    (conj {:rule :consent/not-approved})

    (not (present? (:approved-at proposal)))
    (conj {:rule :consent/approved-at-missing})

    (not (present? (:passkey-credential-id proposal)))
    (conj {:rule :consent/passkey-missing})

    (not (present? (:digest proposal)))
    (conj {:rule :consent/digest-missing})))

(defn quote-issues
  [{:keys [provider upfront monthly currency observed-at]} now-ms
   {:keys [max-upfront max-monthly max-quote-age-ms]
    :or {max-upfront 20M max-monthly 20M max-quote-age-ms default-max-quote-age-ms}}]
  (let [provider (kw provider)
        u (decimal upfront)
        m (decimal monthly)]
    (cond-> []
      (not (contains? allowed-providers provider))
      (conj {:rule :quote/provider-unsupported})

      (or (nil? u) (not (positive-or-zero? upfront)))
      (conj {:rule :quote/upfront-invalid})

      (or (nil? m) (not (positive-or-zero? monthly)))
      (conj {:rule :quote/monthly-invalid})

      (not (present? currency))
      (conj {:rule :quote/currency-missing})

      (or (not (number? observed-at))
          (not (number? now-ms))
          (neg? (- now-ms observed-at))
          (> (- now-ms observed-at) max-quote-age-ms))
      (conj {:rule :quote/stale})

      (and u (> u max-upfront))
      (conj {:rule :quote/upfront-over-limit :actual u :limit max-upfront})

      (and m (> m max-monthly))
      (conj {:rule :quote/monthly-over-limit :actual m :limit max-monthly}))))

(defn allocation-issues
  [state proposal now-ms policy]
  (let [value (:value proposal)
        msisdn (phone/normalize-e164 (:msisdn value))
        subject (:subject value)
        assignee (:assignee value)
        assignee-kind (kw (:assignee-kind value))
        route (:route value)
        provider (kw (:provider value))
        quote (update (:quote value) :provider kw)
        exact-block (when msisdn
                      (numbering/block "provider-observation" msisdn msisdn :machine
                                       :operator provider))
        plan (when exact-block
               (numbering/plan-allocation
                {:blocks [exact-block]
                 :records (vec (vals (:numbering/numbers state)))
                 :msisdn msisdn :subject subject :now-ms now-ms}))]
    (cond-> (vec (concat (approved-envelope-issues proposal)
                         (quote-issues quote now-ms policy)))
      (not= :number/allocate (kw (:op proposal)))
      (conj {:rule :proposal/op-mismatch})

      (nil? msisdn)
      (conj {:rule :number/msisdn-invalid})

      (not (present? subject))
      (conj {:rule :number/owner-missing})

      (not (present? assignee))
      (conj {:rule :number/assignee-missing})

      (not (contains? allowed-assignee-kinds assignee-kind))
      (conj {:rule :number/assignee-kind-invalid})

      (not (and (present? route)
                (or (str/starts-with? route "did:")
                    (str/starts-with? route "topic:"))))
      (conj {:rule :number/route-invalid})

      (not (contains? allowed-providers provider))
      (conj {:rule :number/provider-unsupported})

      (and plan (not (:phone/ok? plan)))
      (into (map (fn [issue] {:rule (:phone/issue issue)})
                 (:phone/issues plan))))))

(defn release-issues [state proposal]
  (let [value (:value proposal)
        msisdn (phone/normalize-e164 (:msisdn value))
        record (get-in state [:numbering/numbers msisdn])]
    (cond-> (approved-envelope-issues proposal)
      (not= :number/release (kw (:op proposal)))
      (conj {:rule :proposal/op-mismatch})
      (nil? msisdn)
      (conj {:rule :number/msisdn-invalid})
      (nil? record)
      (conj {:rule :number/not-found})
      (and record (not= (:subject value) (:phone/subject record)))
      (conj {:rule :number/not-yours})
      (and record (not (contains? #{:assigned :active :suspended} (:phone/state record))))
      (conj {:rule :number/release-unreachable}))))

(defn proposal-issues [state proposal now-ms policy]
  (case (kw (:op proposal))
    :number/allocate (allocation-issues state proposal now-ms policy)
    :number/release (release-issues state proposal)
    [{:rule :proposal/op-unsupported}]))
