(require '[clojure.string :as str]
         '[jjson.core :as json])

(defn fail! [message data]
  (throw (ex-info message data)))

(defn check= [expected actual label]
  (when-not (= expected actual)
    (fail! (str label ": expected " (pr-str expected) ", got " (pr-str actual))
           {:expected expected :actual actual :label label})))

(defn check [value label]
  (when-not value
    (fail! (str label ": check failed") {:label label})))

(defn error-data [f]
  (try
    (f)
    nil
    (catch Exception e
      (ex-data e))))

(defn check-json-error [f label]
  (check= :jjson/error (:type (error-data f)) label))

(let [source "{\"user\":{\"name\":\"Ada \\uD83D\\uDE80\",\"active\":true},\"items\":[1,-2,3.5,null],\"escaped\":\"a\\nb\\t\\\"c\"}"
      expected {"user" {"name" "Ada 🚀" "active" true}
                "items" [1 -2 3.5 nil]
                "escaped" "a\nb\t\"c"}
      bytes (.getBytes source "UTF-8")]
  (check= expected (json/from-json source) "nested string parse")
  (check= expected (json/from-json bytes) "nested byte parse")
  (check= (json/from-json source) (json/from-json bytes) "string/byte equality")
  (check= expected (json/from-json (json/to-json expected)) "nested round trip"))

(check= {"k" 3} (json/from-json "{\"k\":1,\"k\":2,\"k\":3}")
        "duplicate keys keep last value")
(check= {"nul" "\u0000" "bmp" "λ" "astral" "😀"}
        (json/from-json "{\"nul\":\"\\u0000\",\"bmp\":\"λ\",\"astral\":\"\\uD83D\\uDE00\"}")
        "Unicode decoding")

(check= {"a" 1} (json/from-json "{\"a\":1}" nil) "nil options keeps string keys")
(check= {:a 1} (json/from-json "{\"a\":1}" {:key-fn keyword}) "keyword key function")
(check= {:a {:b {:c 1}}}
        (json/from-json "{\"a\":{\"b\":{\"c\":1}}}" {:key-fn keyword})
        "key function reaches every nested object")
(check= [{:b 2} {:c 3}]
        (json/from-json "[{\"b\":2},{\"c\":3}]" {:key-fn keyword})
        "key function reaches objects inside arrays")
(check= {"A" 1}
        (json/from-json "{\"a\":1}" {:key-fn (fn [key] (str/upper-case key))})
        "custom key function")
(check= {:k 3}
        (json/from-json "{\"k\":1,\"k\":3}" {:key-fn keyword})
        "transformed duplicate keys keep last value")
(check= {:same 7}
        (json/from-json "{\"a\":5,\"A\":7}" {:key-fn (fn [_] :same)})
        "distinct keys transformed into one key keep last value")

(let [make (fn [bad]
             (byte-array (vec (concat (.getBytes "{\"v\":\"" "UTF-8")
                                      bad
                                      (.getBytes "\"}" "UTF-8")))))]
  (doseq [[label bad]
          [["truncated two-byte sequence" [-61]]
           ["truncated three-byte sequence" [-30 -126]]
           ["truncated four-byte sequence" [-16 -97]]
           ["overlong two-byte encoding" [-64 -128]]
           ["overlong three-byte encoding" [-32 -128 -128]]
           ["overlong four-byte encoding" [-16 -128 -128 -128]]
           ["encoded surrogate" [-19 -96 -128]]
           ["code point above U+10FFFF" [-12 -112 -128 -128]]
           ["code point far above U+10FFFF" [-10 -128 -128 -128]]]]
    (check-json-error #(json/from-json (make bad))
                      (str "malformed UTF-8 rejected: " label))))
(check-json-error #(json/from-json "\"\\ud800\"") "lone high surrogate escape rejected")
(check-json-error #(json/from-json "\"\\ud83dx\"") "high surrogate without low pair rejected")
(check-json-error #(json/from-json "\"\\ude00\"") "lone low surrogate escape rejected")

(doseq [number [-9223372036854775808 -1 0 9223372036854775807
                9223372036854775808 18446744073709551615]]
  (check= number (json/from-json (str number)) (str "parse integer " number))
  (check= number (json/from-json (json/to-json number)) (str "integer round trip " number)))
(check= 125.0 (json/from-json "1.25e2") "real numbers become doubles")
(check= "1.2300" (json/to-json 1.2300M) "BigDecimal digits remain exact")
(check= (double 1/3) (json/from-json (json/to-json 1/3)) "ratios become doubles")

(let [value {"plain" 1 :keyword 2 :qualified/key 3 4 4}
      parsed (json/from-json (json/to-json value))]
  (check= {"plain" 1 "keyword" 2 "qualified/key" 3 "4" 4} parsed
          "map key conversion"))
(check= [0 1 2] (json/from-json (json/to-json (range 3))) "seqs become arrays")
(check= #{1 2 3} (set (json/from-json (json/to-json #{1 2 3}))) "sets become arrays")
(check= ["name" "x"] (json/from-json (json/to-json ['name \x]))
        "symbols and characters become strings")

(let [compact (json/to-json {"a" [1 2]})
      explicit-compact (json/to-json {"a" [1 2]} {:pretty false})
      pretty (json/to-json {"a" [1 2]} {:pretty true})]
  (check= compact explicit-compact "false pretty option stays compact")
  (check (str/includes? pretty "\n") "pretty output contains line breaks")
  (check= (json/from-json compact) (json/from-json pretty) "pretty output is equivalent"))
(check= "1" (json/to-json 1 nil) "nil write options")
(check-json-error #(json/to-json 1 "{}") "to-json rejects non-map options")
(check-json-error #(json/to-json 1 {:pretty 1}) "to-json rejects non-boolean pretty")
(check-json-error #(json/to-json 1 {:pretty nil}) "to-json rejects nil pretty")
(check-json-error #(json/to-json 1 {:indent true}) "to-json rejects unknown options")

(check-json-error #(json/from-json "{}" [:bad]) "from-json rejects non-map options")
(check-json-error #(json/from-json "{}" {:unknown 1}) "from-json rejects unknown options")
(check-json-error #(json/from-json "{}" {:key-fn nil}) "from-json rejects nil key-fn")
(check-json-error #(json/from-json "{}" {:key-fn 7}) "from-json rejects non-callable key-fn")
(check-json-error #(json/from-json "{}" {:key-fn keyword :pretty true})
                  "from-json rejects write-only options")

(doseq [[label f] [["invalid JSON" #(json/from-json "{\"missing\":}")]
                   ["invalid UTF-8" #(json/from-json (byte-array [-61 40]))]
                   ["wrong input" #(json/from-json 1)]
                   ["NaN" #(json/to-json ##NaN)]
                   ["positive infinity" #(json/to-json ##Inf)]
                   ["negative infinity" #(json/to-json ##-Inf)]
                   ["unsupported value" #(json/to-json (atom 1))]]]
  (check-json-error f label))

(let [count-keys 5000
      source (str "{" (str/join "," (map #(str "\"k" % "\":" %) (range count-keys))) "}")
      parsed (json/from-json source)]
  (check= count-keys (count parsed) "key interning capacity fallback")
  (check= 4999 (get parsed "k4999") "key interning fallback value"))

(defn nested-json [depth]
  (str (apply str (repeat depth "[")) "0" (apply str (repeat depth "]"))))

(check= 0 (loop [value (json/from-json (nested-json 1000)) depth 1000]
            (if (zero? depth) value (recur (first value) (dec depth))))
        "depth 1000 parses")
(check-json-error #(json/from-json (nested-json 1001)) "depth 1001 fails")

(doseq [size [0 1 32 33 1056 1057 40000]]
  (let [source (str "[" (str/join "," (range size)) "]")
        value (json/from-json source)]
    (check (vector? value) (str "array size " size " is a vector"))
    (check= size (count value) (str "array size " size " count"))
    (when (pos? size)
      (check= 0 (first value) (str "array size " size " first"))
      (check= (dec size) (peek value) (str "array size " size " last")))))

(let [value (vec (range 2000))]
  (check= value
          (json/from-json (json/to-json value))
          "collection lengths use unsigned tape bytes"))

(let [input (.getBytes "{\"stable\":true}" "UTF-8")
      before (vec input)]
  (check= {"stable" true} (json/from-json input) "byte input parses")
  (check= before (vec input) "byte input is not modified"))

(let [workers
      (mapv (fn [worker]
              (future
                (dotimes [iteration 100]
                  (let [value {"worker" worker
                               "iteration" iteration
                               "values" [nil true false 18446744073709551615]}]
                    (check= value
                            (json/from-json (json/to-json value))
                            "concurrent round trip")))
                true))
            (range 8))]
  (doseq [worker workers]
    (check= true @worker "concurrent worker completed")))

;; Byte output is a distinct UTF-8 contract, including escaping, empty values,
;; non-ASCII characters and no byte-order mark or trailing NUL.
(doseq [[value text] [[nil "null"] [true "true"] ["" "\"\""]
                     ["é😀\u0000" "\"é😀\\u0000\""]
                     [{"x" [1 false nil]} "{\"x\":[1,false,null]}"]]]
  (let [output (json/to-json-bytes value)]
    (check (bytes? output) "byte output is a Jolt byte array")
    (check= (vec (.getBytes text "UTF-8")) (vec output) "exact UTF-8 output")
    (check= value (json/from-json output) "byte output decodes to original value")))
(let [value {"λ" ["😀" 42]}
      output (json/to-json-bytes value {:pretty true})]
  (check= value (json/from-json output) "pretty UTF-8 output")
  (check (some #{10} (vec output)) "pretty byte output contains newlines"))
(check= [49] (vec (json/to-json-bytes 1 nil)) "nil byte output options")
(doseq [options [{:pretty nil} {:pretty 1} {:unknown true} []]]
  (check-json-error #(json/to-json-bytes 1 options) "invalid byte output options"))
(doseq [value [##NaN ##Inf ##-Inf (atom 1)]]
  (check-json-error #(json/to-json-bytes value) "invalid byte output value"))

;; Exact modes must retain the token's digits, not decimalize an already
;; rounded double. Each mode leaves the other numeric category unchanged.
(let [integer "1844674407370955161612345678901234567890"
      negative (str "-" integer)
      decimal "0.123456789012345678901234567890"]
  (doseq [text [integer negative]]
    (check= (bigint text) (json/from-json text {:bigints true}) "exact large integer")
    (check= (bigint text)
            (json/from-json (.getBytes text "UTF-8") {:bigints true})
            "exact large integer from bytes"))
  (check= (bigdec decimal) (json/from-json decimal {:bigdecimals true}) "exact decimal")
  (check= "1.2300" (json/to-json (json/from-json "1.2300" {:bigdecimals true}))
          "decimal scale survives decode and encode")
  (check= (bigdec "1.234567890123456789e400")
          (json/from-json "1.234567890123456789e400" {:bigdecimals true})
          "exact exponent outside double range")
  (check= (bigdec "1e-400")
          (json/from-json "1e-400" {:bigdecimals true}) "exact tiny exponent")
  (check= [42 (bigint integer) (bigdec decimal)]
          (json/from-json (str "[42," integer "," decimal "]")
                          {:bigints true :bigdecimals true})
          "combined exact numeric modes")
  (check= 1.25 (json/from-json "1.25" {:bigints true}) "bigints preserves double defaults")
  (check= 18446744073709551615
          (json/from-json "18446744073709551615" {:bigdecimals true})
          "bigdecimals preserves exact uint64"))
(doseq [options [{:bigints true} {:bigdecimals true} {:bigints true :bigdecimals true}]]
  (doseq [text ["01" "1." "1e" "+1" "NaN" "Infinity"]]
    (check-json-error #(json/from-json text options) "exact modes keep JSON number grammar strict")))
(doseq [key [:bigints :bigdecimals :reject-duplicate-keys]
        value [nil 1 "true"]]
  (check-json-error #(json/from-json "0" {key value}) "decode flags require booleans"))

;; Duplicate checks are object-local, compare decoded raw names, and also
;; protect callers whose key transformation collapses distinct names.
(doseq [text ["{\"a\":1,\"a\":2}" "{\"a\":1,\"\\u0061\":2}"
              "[{\"nested\":{\"x\":null,\"x\":false}}]"
              "{\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5,\"f\":6,\"g\":7,\"h\":8,\"a\":9}"]]
  (let [data (error-data #(json/from-json text {:reject-duplicate-keys true}))]
    (check= :duplicate-key (:reason data) "duplicate keys rejected")
    (check= false (:transformed? data) "raw duplicate classification")))
(check= [{"a" 1} {"a" 2}]
        (json/from-json "[{\"a\":1},{\"a\":2}]" {:reject-duplicate-keys true})
        "same key in independent objects is allowed")
(check= {:same 2}
        (json/from-json "{\"a\":1,\"A\":2}"
                        {:key-fn (constantly :same) :reject-duplicate-keys false})
        "explicit false keeps last-wins behavior")
(let [data (error-data #(json/from-json "{\"a\":null,\"A\":2}"
                                       {:key-fn str/lower-case :reject-duplicate-keys true}))]
  (check= :duplicate-key (:reason data) "transformed collision rejected")
  (check= true (:transformed? data) "transformed collision classification")
  (check= "a" (:key data) "collision reports transformed key"))
(let [calls (atom 0)]
  (check-json-error
   #(json/from-json "{\"a\":1,\"a\":2}"
                    {:key-fn (fn [_] (swap! calls inc)) :reject-duplicate-keys true})
   "raw duplicates rejected even with stateful noncolliding key-fn"))

;; Source locations refer to the original input, not yyjson's mutated in-situ
;; buffer. Columns are one-based UTF-8 bytes, offsets are zero-based.
(doseq [[text offset line column]
        [["{\"s\":\"a\\nb\",\"bad\":}" 18 1 19]
         ["{\n \"é\": 1,\n \"bad\":\n}" 20 4 1]
         ["{\r\n\"bad\":\r\n}" 11 3 1]
         ["[" 1 1 2]]]
  (doseq [input [text (.getBytes text "UTF-8")]]
    (let [data (error-data #(json/from-json input))]
      (check= :read (:operation data) "parse error operation")
      (check (integer? (:code data)) "parse error has numeric code")
      (check= offset (:byte-offset data) "parse error byte offset")
      (check= line (:line data) "parse error line")
      (check= column (:column data) "parse error byte column"))))
(let [input (byte-array [123 34 118 34 58 34 -61 40 34 125])
      before (vec input)
      data (error-data #(json/from-json input))]
  (check= -2 (:code data) "malformed UTF-8 error code")
  (check= 6 (:byte-offset data) "malformed UTF-8 sequence offset")
  (check= 7 (:column data) "malformed UTF-8 column")
  (check= before (vec input) "invalid byte input remains unchanged"))

(println "ok")