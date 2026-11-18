(require '[clojure.string :as str]
         '[jjson.core :as json]
         '[jolt.scheme :as scheme])

(defn fail! [message data]
  (throw (ex-info message data)))

(defn check= [expected actual label]
  (when-not (= expected actual)
    (fail! (str label ": expected " (pr-str expected) ", got " (pr-str actual))
           {:expected expected :actual actual :label label})))

(defn check [value label]
  (when-not value
    (fail! (str label ": check failed") {:label label})))

(defn wait!
  ([future timeout label]
   (deref future timeout :timeout))
  ([future timeout label expected]
   (let [result (deref future timeout :timeout)]
     (check= expected result label)
     result)))

;; Shared immutable input parsed concurrently by every worker.
(def shared-doc
  (str "{\"id\":17,\"active\":true,\"name\":\"λ=🚀\\t\","
       "\"rows\":[1,-2,3.5,null,[4,[5]],{\"a\":1,\"b\":false}],"
       "\"big\":18446744073709551615,"
       "\"txt\":\"\\u0000\\uD83D\\uDE00\"}"))
(def shared-expected
  {"id" 17 "active" true "name" "λ=🚀\t"
   "rows" [1 -2 3.5 nil [4 [5]] {"a" 1 "b" false}]
   "big" 18446744073709551615
   "txt" "\u0000😀"})

;; Write targets that vary per worker and per iteration.
(defn round-trip-value [worker iteration]
  {"worker" worker
   "iteration" iteration
   "values" [nil true false 1.5 -7 9223372036854775807]
   "nested" {"deep" {"deeper" [1 2 3 "mixed"]}}})

(def shared-input (.getBytes shared-doc "UTF-8"))
(def shared-before (vec shared-input))

(check= shared-expected (json/from-json shared-input)
        "shared input parses before stress")
(check= 18446744073709551615 (get shared-expected "big")
        "shared input reference value")

;; 32 workers round-tripping varied maps while sharing the same input bytes.
(let [workers
      (mapv (fn [worker-index]
              (future
                (dotimes [iteration 100]
                  (check= shared-expected
                          (json/from-json shared-input)
                          (str "worker " worker-index " parse of shared input"))
                  (check= (round-trip-value worker-index iteration)
                          (json/from-json
                           (json/to-json (round-trip-value worker-index iteration)))
                          (str "worker " worker-index " round trip"))
                  ;; Keep short-lived allocations hot while native calls run.
                  (byte-array (vec (range 16))))
                true))
            (range 32))]
  (doseq [worker workers]
    (wait! worker 60000 "worker completes before timeout" true))
  (check= shared-before (vec shared-input)
          "shared input bytes unchanged after stress"))

;; Several futures parse a multi-megabyte document while another future races
;; fresh allocations, so a collection stall during native work shows up as a
;; missed deadline instead of a false pass.
(let [events 50000
      doc (str "{\"events\":["
               (str/join "," (map (fn [i]
                                    (str "{\"id\":" i
                                         ",\"active\":" (if (even? i) "true" "false")
                                         ",\"region\":\"eu\",\"scale\":" i ".25}"))
                                  (range events)))
               "]}")
      doc-expected (json/from-json doc)
      doc-bytes (.getBytes doc "UTF-8")]
  (check= events (count (get doc-expected "events")) "large document event count")
  (check= 49999 (get-in doc-expected ["events" 49999 "id"]) "large document final id")
  (check= "eu" (get-in doc-expected ["events" 49999 "region"]) "large document final region")
  (check= events (count (get (json/from-json doc-bytes) "events"))
          "large document byte parse")
  (let [parsers
        (mapv (fn [parser-index]
                (future
                  (let [parsed (json/from-json doc-bytes)]
                    (check= events
                            (count (get parsed "events"))
                            (str "parser " parser-index " event count"))
                    (check= 49999
                            (get-in parsed ["events" 49999 "id"])
                            (str "parser " parser-index " final id")))
                  true))
              (range 4))
        allocator
        (future
          (loop [rounds 0]
            (if (< rounds 50000)
              (do (byte-array (vec (range 64)))
                  (recur (inc rounds)))
              :done)))]
    (doseq [parser parsers]
      (wait! parser 180000 "large document parser completes before timeout" true))
    (wait! allocator 180000 "allocation worker progresses during native churn" :done)))

;; Exercise the new paths while a separate thread explicitly requests GC.
(let [expected {"integer" 184467440737095516161234567890N
                "decimal" 0.123456789012345678901234567890M
                "text" "λ😀\u0000"}
      input (json/to-json-bytes expected)
      before (vec input)
      options {:bigints true :bigdecimals true :reject-duplicate-keys true}
      workers (mapv
               (fn [_]
                 (future
                   (dotimes [_ 100]
                     (check= expected (json/from-json input options) "concurrent exact decode")
                     (check= expected
                             (json/from-json (json/to-json-bytes expected {:pretty true}) options)
                             "concurrent direct byte encoding")
                     (check= :duplicate-key
                             (try
                               (json/from-json "{\"x\":1,\"x\":2}" options)
                               :accepted
                               (catch Exception e (:reason (ex-data e))))
                             "concurrent duplicate error cleanup")
                     (check= :read
                             (try
                               (json/from-json "{\"bad\":}")
                               :accepted
                               (catch Exception e (:operation (ex-data e))))
                             "concurrent syntax error cleanup"))
                   true))
               (range 16))
      collector (future (dotimes [_ 100] (scheme/eval-string "(collect-rendezvous)")) true)]
  (doseq [worker workers]
    (wait! worker 60000 "new API worker completes with GC" true))
  (wait! collector 60000 "collector progresses during new API calls" true)
  (check= before (vec input) "shared exact input remains unchanged"))

(println "ok")
(flush)
(System/exit 0)