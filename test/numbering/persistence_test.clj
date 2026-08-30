(ns numbering.persistence-test
  (:require [clojure.test :refer [deftest is]]
            [numbering.persistence :as persistence]
            [numbering.store :as store])
  (:import [java.nio.file Files]))

(deftest state-round-trips-without-secrets
  (let [dir (Files/createTempDirectory "numbering-state" (make-array java.nio.file.attribute.FileAttribute 0))
        path (str (.resolve dir "state.edn"))
        state (assoc (store/empty-state) :probe {:safe true})]
    (try
      (persistence/save-state! path state)
      (is (= state (persistence/load-state path)))
      (is (not (contains? (persistence/load-state path) :telnyx-api-key)))
      (finally
        (Files/deleteIfExists (.resolve dir "state.edn"))
        (Files/deleteIfExists dir)))))

