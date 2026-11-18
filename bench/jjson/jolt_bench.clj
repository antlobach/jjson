(ns jjson.jolt-bench
  (:require [jolt.time]
            [clojure.data.json :as data-json]
            [jjson.core :as jj]
            [jjson.harness :as harness]))

(def libraries
  {:jjson "working-tree"
   :clojure.data.json "2.5.2"
   :jolt.time "0.0.8"})

(defn data-json-read [text]
  (data-json/read-str text :key-fn identity))

(defn data-json-write [value]
  (data-json/write-str value
                       :escape-unicode false
                       :escape-js-separators false
                       :escape-slash false))

(defn runtime-metadata []
  {:name "Jolt" :version "0.8.23" :mode :release-built})

(defn decode-fixtures [fixtures]
  (filterv #(not= "twitterescaped.json" (:name %)) fixtures))

(defn prepare-encode [fixtures]
  (mapv (fn [fixture]
          (assoc fixture :value (jj/from-json (:text fixture))))
        fixtures))

(defn run-case! [library operation output data-directory command
                 progress case-index case-count]
  (let [fixtures (harness/load-fixtures! data-directory)
        items (if (= operation :decode)
                (decode-fixtures fixtures)
                (prepare-encode fixtures))
        run (case [library operation]
              [:jjson :decode] #(jj/from-json (:text %))
              [:data-json :decode] #(data-json-read (:text %))
              [:jjson :encode] #(jj/to-json (:value %))
              [:data-json :encode] #(data-json-write (:value %))
              (throw (ex-info "unknown Jolt benchmark case"
                              {:library library :operation operation})))
        checksum (if (= operation :decode)
                   harness/value-checksum
                   harness/string-checksum)]
    (harness/benchmark!
     {:output output
      :runtime (runtime-metadata)
      :libraries libraries
      :command command
      :library library
      :operation operation
      :items items
      :run run
      :checksum checksum
      :progress progress
      :case-index case-index
      :case-count case-count})))

(defn validate! [data-directory]
  (doseq [{:keys [name text]} (harness/load-fixtures! data-directory)]
    (let [jjson-value (jj/from-json text)
          jjson-output (jj/to-json jjson-value)]
      (harness/ensure! (= jjson-value (jj/from-json jjson-output))
                       "jjson round-trip mismatch"
                       {:fixture name})
      ;; This historical corpus comparison excludes the fixture that the
      ;; reference parser could not decode. Its rejection is not a contract.
      (when-not (= name "twitterescaped.json")
        (let [data-json-value (data-json-read text)
              data-json-output (data-json-write data-json-value)]
          (harness/ensure! (= jjson-value data-json-value)
                           "Jolt decoders disagree"
                           {:fixture name})
          (harness/ensure! (= data-json-value (data-json-read data-json-output))
                           "data.json round-trip mismatch"
                           {:fixture name})))))
  (println "jolt-corpus-validation-ok"))

(defn -main [& args]
  (case (first args)
    "--case"
    (let [[_ library operation output data-directory command
           progress case-index case-count] args]
      (run-case! (keyword library) (keyword operation)
                 output data-directory command progress
                 (parse-long case-index) (parse-long case-count)))

    "--validate"
    (validate! (second args))

    "--combine"
    (let [[_ output & paths] args]
      (harness/consolidate! output paths)
      (println "wrote" output))

    (throw (ex-info "expected --case, --validate, or --combine" {:args args}))))
