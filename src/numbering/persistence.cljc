(ns numbering.persistence
  "Small durable host seam for the R0 service. Writes the actor map atomically;
  secrets never enter that map."
  (:require [clojure.edn :as edn]
            [numbering.store :as store])
  #?(:clj (:import [java.nio.charset StandardCharsets]
                   [java.nio.file Files Path Paths StandardCopyOption]
                   [java.nio.file.attribute FileAttribute])))

#?(:clj
   (defn load-state [path]
     (try
       (let [p (Paths/get path (make-array String 0))]
         (if (Files/exists p (make-array java.nio.file.LinkOption 0))
           (edn/read-string (Files/readString p StandardCharsets/UTF_8))
           (store/empty-state)))
       (catch Exception e
         (throw (ex-info "numbering state could not be read"
                         {:type :persistence/read-failed :path path} e))))))

#?(:clj
   (defn save-state! [path state]
     (let [p (Paths/get path (make-array String 0))
           parent (.getParent p)
           tmp (Paths/get (str path ".tmp") (make-array String 0))]
       (when parent
         (Files/createDirectories parent (make-array FileAttribute 0)))
       (Files/writeString tmp (pr-str state) StandardCharsets/UTF_8
                          (into-array java.nio.file.OpenOption
                                      [java.nio.file.StandardOpenOption/CREATE
                                       java.nio.file.StandardOpenOption/TRUNCATE_EXISTING
                                       java.nio.file.StandardOpenOption/WRITE]))
       (try
         (Files/move tmp p (into-array StandardCopyOption
                                       [StandardCopyOption/ATOMIC_MOVE
                                        StandardCopyOption/REPLACE_EXISTING]))
         (catch java.nio.file.AtomicMoveNotSupportedException _
           (Files/move tmp p (into-array StandardCopyOption
                                         [StandardCopyOption/REPLACE_EXISTING]))))
       state)))

