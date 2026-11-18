(require '[clojure.data.json :as reference]
         '[jjson :as jj])

;; Deterministic values cross the array-map and vector chunk boundaries.
;; The reference parser and writer do not use yyjson or this bridge.
(doseq [i (range 1000)]
  (let [value {"id" i
               "text" (str "λ😀\u0000\n\r\t\\\"" (char (+ 32 (mod (* i 17) 1000))))
               "values" [nil true false (- i) (* (- i 500) 0.125)]
               "array" (vec (range (mod i 40)))
               "object" (into {} (map (fn [j] [(str "key" j) [j nil]]) (range (mod i 12))))}
        encoded (jj/to-json value)
        bytes (jj/to-json-bytes value {:pretty (even? i)})
        from-reference (reference/write-str value)]
    (when-not (= value (reference/read-str encoded))
      (throw (ex-info "Reference parser found a string-output mismatch" {:case i})))
    (when-not (= value (reference/read-str (String. bytes "UTF-8")))
      (throw (ex-info "Reference parser found a byte-output mismatch" {:case i})))
    (when-not (= value (jj/from-json from-reference))
      (throw (ex-info "Reference writer found a decode mismatch" {:case i})))
    (when-not (= value (jj/from-json (.getBytes from-reference "UTF-8")))
      (throw (ex-info "Reference writer found a byte-input mismatch" {:case i})))))

(println "PASS: 1000 independent reference comparisons across four input/output paths")
