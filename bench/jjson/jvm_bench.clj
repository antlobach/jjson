(ns jjson.jvm-bench
  (:require [jsonista.core :as jsonista]
            [jjson.harness :as harness])
  (:import [java.lang.management ManagementFactory]))

(def libraries {:jsonista "1.0.1" :jackson "2.22.2"})

(defn runtime-metadata []
  {:name "OpenJDK"
   :version (System/getProperty "java.version")
   :vendor (System/getProperty "java.vendor")
   :vm (System/getProperty "java.vm.name")
   :vm-version (System/getProperty "java.vm.version")
   :vm-info (System/getProperty "java.vm.info")
   :input-arguments (vec (.getInputArguments (ManagementFactory/getRuntimeMXBean)))})

(defn decode-fixtures [fixtures]
  (filterv #(not= "twitterescaped.json" (:name %)) fixtures))

(defn prepare-encode [fixtures]
  (mapv (fn [fixture]
          (assoc fixture :value (jsonista/read-value (:text fixture))))
        fixtures))

(defn run-case! [operation output data-directory command
                 progress case-index case-count]
  (let [fixtures (harness/load-fixtures! data-directory)
        items (if (= operation :decode)
                (decode-fixtures fixtures)
                (prepare-encode fixtures))
        run (case operation
              :decode #(jsonista/read-value (:text %))
              :encode #(jsonista/write-value-as-string (:value %))
              (throw (ex-info "unknown Jsonista benchmark operation"
                              {:operation operation})))
        checksum (if (= operation :decode)
                   harness/value-checksum
                   harness/string-checksum)]
    (harness/benchmark!
     {:output output
      :runtime (runtime-metadata)
      :libraries libraries
      :command command
      :library :jsonista
      :operation operation
      :items items
      :run run
      :checksum checksum
      :progress progress
      :case-index case-index
      :case-count case-count})))

(defn validate! [data-directory]
  (doseq [{:keys [name text]} (harness/load-fixtures! data-directory)]
    (let [value (jsonista/read-value text)
          output (jsonista/write-value-as-string value)]
      (harness/ensure! (= value (jsonista/read-value output))
                       "Jsonista round-trip mismatch"
                       {:fixture name})))
  (println "jsonista-corpus-validation-ok"))

(defn -main [& args]
  (case (first args)
    "--case"
    (let [[_ operation output data-directory command
           progress case-index case-count] args]
      (run-case! (keyword operation) output data-directory command
                 progress (parse-long case-index) (parse-long case-count)))

    "--validate"
    (validate! (second args))

    "--combine"
    (let [[_ output & paths] args]
      (harness/consolidate! output paths)
      (println "wrote" output))

    (throw (ex-info "expected --case, --validate, or --combine" {:args args}))))
