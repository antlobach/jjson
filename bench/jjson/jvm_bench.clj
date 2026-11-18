(ns jjson.jvm-bench
  (:require [jsonista.core :as jsonista]
            [clojure.data.json :as data-json]
            [jjson.harness :as harness])
  (:import [java.lang.management ManagementFactory]))

(def libraries {:jsonista "1.0.1" :jackson "2.22.2" :clojure.data.json "2.5.2"})

(defn runtime-metadata []
  {:name "OpenJDK"
   :version (System/getProperty "java.version")
   :clojure-version (clojure-version)
   :vendor (System/getProperty "java.vendor")
   :vm (System/getProperty "java.vm.name")
   :vm-version (System/getProperty "java.vm.version")
   :vm-info (System/getProperty "java.vm.info")
   :input-arguments (vec (.getInputArguments (ManagementFactory/getRuntimeMXBean)))})

(defn data-json-read [text]
  (data-json/read-str text))

(defn data-json-write [value]
  (data-json/write-str value
                       :escape-unicode false
                       :escape-js-separators false
                       :escape-slash false))

(defn prepare-encode [fixtures]
  (mapv (fn [fixture]
          (assoc fixture :value (jsonista/read-value (:text fixture))))
        fixtures))

(defn run-case! [library operation output data-directory command
                 progress case-index case-count]
  (let [fixtures (harness/load-fixtures! data-directory)
        items (if (= operation :decode)
                fixtures
                (prepare-encode fixtures))
        run (case [library operation]
              [:jsonista :decode] #(jsonista/read-value (:text %))
              [:data-json :decode] #(data-json-read (:text %))
              [:jsonista :encode] #(jsonista/write-value-as-string (:value %))
              [:data-json :encode] #(data-json-write (:value %))
              (throw (ex-info "unknown JVM benchmark case"
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
  (harness/validate-collection-model! jsonista/read-value :jsonista)
  (harness/validate-collection-model! data-json-read :data-json)
  (doseq [{:keys [name text]} (harness/load-fixtures! data-directory)]
    (let [value (jsonista/read-value text)
          reference (data-json-read text)
          jsonista-output (jsonista/write-value-as-string value)
          data-json-output (data-json-write value)]
      (harness/ensure! (= value reference)
                       "JVM decoders disagree"
                       {:fixture name})
      (harness/ensure! (= value (data-json-read jsonista-output))
                       "data.json rejected or changed Jsonista output"
                       {:fixture name})
      (harness/ensure! (= value (jsonista/read-value data-json-output))
                       "Jsonista rejected or changed data.json output"
                       {:fixture name})))
  (println "jsonista-corpus-validation-ok"))

(defn -main [& args]
  (case (first args)
    "--case"
    (let [[_ library operation output data-directory command
           progress case-index case-count] args]
      (run-case! (keyword library) (keyword operation) output data-directory command
                 progress (parse-long case-index) (parse-long case-count)))

    "--validate"
    (validate! (second args))

    "--combine"
    (let [[_ output & paths] args]
      (harness/consolidate! output paths)
      (println "wrote" output))

    (throw (ex-info "expected --case, --validate, or --combine" {:args args}))))
