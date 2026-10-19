(ns jjson.core
  "Fast yyjson-backed JSON encoding and decoding for Jolt.

   Decoding parses with yyjson inside collect-safe native calls that write
   compact value records and string characters into locked Chez objects; a
   Scheme loop then builds Jolt values while garbage collection proceeds
   normally. Encoding uses a malloc-backed tape passed to yyjson. The decoder
   is intentionally Chez/Jolt-specific."
  (:require [jolt.ffi :as ffi]
            [jolt.scheme :as scheme]))

(def ^:private max-u32 4294967295)
(def ^:private min-i64 -9223372036854775808)
(def ^:private max-i64 9223372036854775807)

(def ^:private tag-null 0)
(def ^:private tag-false 1)
(def ^:private tag-true 2)
(def ^:private tag-sint 3)
(def ^:private tag-uint 4)
(def ^:private tag-real 5)
(def ^:private tag-string 6)
(def ^:private tag-array 7)
(def ^:private tag-object 8)
(def ^:private tag-raw-number 9)

(def ^:private max-depth 1000)

;; ---------------------------------------------------------------------------
;; Native ABI
;; ---------------------------------------------------------------------------

;; These declarations anchor the decoder symbols in packaged static builds.
;; Calls use their addresses through Chez foreign procedures below, which pass
;; locked Scheme objects to the collect-safe entry points.
(ffi/defcfn native-decode-string-anchor "jjson_decode_string"
  [:pointer] :pointer :blocking)
(ffi/defcfn native-decode-bytes-anchor "jjson_decode_bytes"
  [:pointer] :pointer :blocking)
(ffi/defcfn native-decode-error-anchor "jjson_decode_error" [:pointer] :pointer)
(ffi/defcfn native-decode-records-anchor "jjson_decode_records"
  [:pointer] :size_t)
(ffi/defcfn native-decode-chars-anchor "jjson_decode_chars" [:pointer] :size_t)
(ffi/defcfn native-decode-keys-anchor "jjson_decode_keys" [:pointer] :size_t)
(ffi/defcfn native-decode-fill-anchor "jjson_decode_fill"
  [:pointer :pointer :pointer] :int :blocking)
(ffi/defcfn native-decode-free-anchor "jjson_decode_free"
  [:pointer] :void :blocking)
(ffi/defcfn native-write-tape "jjson_write_tape"
  [:pointer :size_t :pointer :size_t :pointer :size_t :pointer :size_t
   :pointer :size_t :int :pointer :pointer] :pointer :blocking)
(ffi/defcfn native-alloc "jjson_alloc" [:size_t] :pointer)
(ffi/defcfn native-free "jjson_free" [:pointer] :void)

;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

(defn- json-error [message]
  (ex-info message {:type :jjson/error}))

(defn- error-message [message-out fallback]
  (let [pointer (ffi/read message-out :pointer)]
    (if (ffi/null? pointer) fallback (ffi/ptr->string pointer))))

(defn- wrap-error
  "Rethrows jjson errors unchanged and normalizes every other ordinary
  failure into one; fatal runtime errors are not caught."
  [f]
  (try
    (f)
    (catch Exception e
      (if (= :jjson/error (:type (ex-data e)))
        (throw e)
        (throw (ex-info (or (.getMessage e) "JSON operation failed")
                        {:type :jjson/error}
                        e))))))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(def ^:private make-pvec
  (scheme/eval-string
   "(if (top-level-bound? 'make-pvec) make-pvec #f)"))

;; Jolt byte arrays are host records whose `vec` field holds the bytevector.
(def ^:private jolt-array-vec
  ((scheme/eval-string
    "(lambda (sample)
       (let* ([rtd (record-rtd sample)]
              [names (record-type-field-names rtd)]
              [n (vector-length names)])
         (let loop ([i 0])
           (cond
             [(= i n) #f]
             [(eq? (vector-ref names i) 'vec) (record-accessor rtd i)]
             [else (loop (+ i 1))]))))")
   (byte-array 0)))

(when-not (and make-pvec jolt-array-vec)
  (throw (json-error "This Jolt runtime does not expose make-pvec or byte-array storage")))

;; Builds the decoder. Every C call that touches a Scheme object is
;; __collect_safe and receives only objects locked for the duration of the
;; call; all managed allocation happens in Scheme, so collection can run at
;; any point of a decode. Records are 16 bytes: tag, u32 key id at offset 4,
;; u64 payload at offset 8 (see native/jjson_chez.c). Unchecked primitives
;; (#3%) index only buffers whose sizes C verified against the record walk.
(def ^:private make-decoder
  (scheme/eval-string
   "(lambda (a-string a-bytes a-error a-records a-chars a-keys a-fill a-free
             make-pvec array-vec transient assoc! persistent! empty-map fail)
      (let ([c-string (foreign-procedure __collect_safe a-string (scheme-object) uptr)]
            [c-bytes (foreign-procedure __collect_safe a-bytes (scheme-object) uptr)]
            [c-error (foreign-procedure a-error (uptr) utf-8)]
            [c-records (foreign-procedure a-records (uptr) size_t)]
            [c-chars (foreign-procedure a-chars (uptr) size_t)]
            [c-keys (foreign-procedure a-keys (uptr) size_t)]
            [c-fill (foreign-procedure __collect_safe a-fill
                      (uptr scheme-object scheme-object) int)]
            [c-free (foreign-procedure __collect_safe a-free (uptr) void)])
        (define (locked-call f obj)
          (lock-object obj)
          (dynamic-wind void (lambda () (f obj)) (lambda () (unlock-object obj))))
        (define (fill handle)
          (let ([message (c-error handle)])
            (when message (fail message)))
          (let* ([nrecords (c-records handle)]
                 [chars (make-string (c-chars handle))]
                 [records (make-bytevector (fx* nrecords 16))]
                 [ok (begin
                       (lock-object chars)
                       (lock-object records)
                       (dynamic-wind
                         void
                         (lambda () (c-fill handle chars records))
                         (lambda () (unlock-object chars) (unlock-object records))))])
            (when (fx= ok 0) (fail (or (c-error handle) \"JSON decode failed\")))
            (values records chars (c-keys handle))))
        (define (materialize records chars nkeys key-fn)
          (let ([keys (make-vector nkeys #f)] [ri 0] [ci 0])
            (define (payload off) (#3%bytevector-u64-native-ref records (#3%fx+ off 8)))
            (define (key-id off) (#3%bytevector-u32-native-ref records (#3%fx+ off 4)))
            (define (take! n)
              (let* ([start ci] [end (#3%fx+ start n)])
                (set! ci end)
                (substring chars start end)))
            (define (read-key)
              (let* ([off (#3%fx* ri 16)] [tag (#3%bytevector-u8-ref records off)])
                (set! ri (#3%fx+ ri 1))
                (cond
                  [(#3%fx= tag 10) (#3%vector-ref keys (key-id off))]
                  [(#3%fx= tag 9)
                   (let ([s (take! (payload off))])
                     (#3%vector-set! keys (key-id off) s)
                     s)]
                  [else (take! (payload off))])))
            (define (read)
              (let* ([off (#3%fx* ri 16)] [tag (#3%bytevector-u8-ref records off)])
                (set! ri (#3%fx+ ri 1))
                (case tag
                  [(0) jolt-nil]
                  [(1) #f]
                  [(2) #t]
                  [(3) (#3%bytevector-s64-native-ref records (#3%fx+ off 8))]
                  [(4) (payload off)]
                  [(5) (#3%bytevector-ieee-double-native-ref records (#3%fx+ off 8))]
                  [(6) (take! (payload off))]
                  [(7)
                   (let* ([n (payload off)] [v (make-vector n)])
                     (let loop ([i 0])
                       (when (#3%fx< i n)
                         (#3%vector-set! v i (read))
                         (loop (#3%fx+ i 1))))
                     (make-pvec v))]
                  [else
                   (let ([n (payload off)])
                     (let loop ([i 0] [m (transient empty-map)])
                       (if (#3%fx= i n)
                           (persistent! m)
                           (let* ([k (read-key)]
                                  [k (if key-fn (key-fn k) k)]
                                  [v (read)])
                             (loop (#3%fx+ i 1) (assoc! m k v))))))])))
            (read)))
        (lambda (input key-fn)
          (let ([handle (if (string? input)
                            (locked-call c-string input)
                            (locked-call c-bytes (array-vec input)))])
            (when (eqv? handle 0) (fail \"Unable to allocate JSON decode state\"))
            (let-values ([(records chars nkeys)
                          (dynamic-wind void
                                        (lambda () (fill handle))
                                        (lambda () (c-free handle)))])
              (materialize records chars nkeys key-fn))))))"))

(def ^:private decode
  (make-decoder (ffi/find-symbol "jjson_decode_string")
                (ffi/find-symbol "jjson_decode_bytes")
                (ffi/find-symbol "jjson_decode_error")
                (ffi/find-symbol "jjson_decode_records")
                (ffi/find-symbol "jjson_decode_chars")
                (ffi/find-symbol "jjson_decode_keys")
                (ffi/find-symbol "jjson_decode_fill")
                (ffi/find-symbol "jjson_decode_free")
                make-pvec jolt-array-vec transient assoc! persistent! {}
                (fn [message]
                  (throw (ex-info (str "JSON read failed: " message)
                                  {:type :jjson/error :operation :read})))))

(defn- read-json [input key-fn]
  (if (or (string? input) (bytes? input))
    ;; false, not nil: jolt's nil is a true value in Scheme.
    (decode input (if (identical? key-fn identity) false key-fn))
    (throw (json-error "JSON input must be a string or byte array"))))
;; ---------------------------------------------------------------------------
;; Writing
;; ---------------------------------------------------------------------------

(defn- key->string [key]
  (cond
    (string? key) key
    (keyword? key) (if-let [ns (namespace key)]
                     (str ns "/" (name key))
                     (name key))
    :else (str key)))

(defn- finite-double [value]
  (let [number (double value)]
    (when (or (not= number number) (= number ##Inf) (= number ##-Inf))
      (throw (json-error "JSON cannot represent NaN or infinity")))
    number))

(defn- check-depth! [depth]
  (when (>= depth max-depth)
    (throw (json-error "JSON nesting exceeds the maximum depth of 1000"))))

(defn- checked-count [value]
  (let [length (count value)]
    (when (> length max-u32)
      (throw (json-error "JSON collection exceeds the native tape size limit")))
    length))

(defn- measure-value [root]
  (let [counters (long-array 5)
        pieces (volatile! (transient []))]
    (letfn [(add-counter! [index amount]
              (let [result (+ (aget counters index) amount)]
                (when (> result max-u32)
                  (throw (json-error "JSON value exceeds the native tape size limit")))
                (aset counters index result)))
            (add-piece! [bytes]
              (add-counter! 4 (alength bytes))
              (vswap! pieces conj! bytes))
            (add-raw! [digits]
              (add-counter! 0 5)
              (add-piece! (.getBytes digits "UTF-8")))
            (measure! [value depth]
              (cond
                (nil? value)
                (add-counter! 0 1)

                (or (true? value) (false? value))
                (add-counter! 0 1)

                (integer? value)
                (cond
                  (< value min-i64)
                  (add-raw! (str value))

                  (neg? value)
                  (do (add-counter! 0 1)
                      (add-counter! 1 1))

                  (<= value max-i64)
                  (do (add-counter! 0 1)
                      (add-counter! 2 1))

                  :else
                  (add-raw! (str value)))

                (decimal? value)
                (add-raw! (str value))

                (ratio? value)
                (do (finite-double value)
                    (add-counter! 0 1)
                    (add-counter! 3 1))

                (number? value)
                (do (finite-double value)
                    (add-counter! 0 1)
                    (add-counter! 3 1))

                (string? value)
                (do (add-counter! 0 5)
                    (add-piece! (.getBytes value "UTF-8")))

                (or (symbol? value) (char? value))
                (do (add-counter! 0 5)
                    (add-piece! (.getBytes (str value) "UTF-8")))

                (map? value)
                (do (check-depth! depth)
                    (add-counter! 0 5)
                    (checked-count value)
                    (doseq [[key item] value]
                      (let [key-bytes (.getBytes (key->string key) "UTF-8")]
                        (add-counter! 0 4)
                        (add-piece! key-bytes))
                      (measure! item (inc depth))))

                (or (sequential? value) (set? value))
                (do (check-depth! depth)
                    (add-counter! 0 5)
                    (checked-count value)
                    (doseq [item value] (measure! item (inc depth))))

                :else
                (throw (json-error (str "Unsupported JSON value: " (pr-str value))))))]
      (measure! root 0)
      {:counts counters :pieces (persistent! @pieces)})))

(defn- put-byte! [bytes index value]
  (let [octet (mod value 256)]
    (aset-byte bytes index (if (> octet 127) (- octet 256) octet))))

(defn- put-u32! [bytes index value]
  (put-byte! bytes index value)
  (put-byte! bytes (+ index 1) (quot value 256))
  (put-byte! bytes (+ index 2) (quot value 65536))
  (put-byte! bytes (+ index 3) (quot value 16777216)))

(defn- value->tape [value]
  (let [{:keys [counts pieces]} (measure-value value)
        ops (byte-array (aget counts 0))
        sints (long-array (aget counts 1))
        uints (long-array (aget counts 2))
        reals (double-array (aget counts 3))
        strings (byte-array (aget counts 4))
        indexes (long-array 6)]
    (letfn [(copy-piece! []
              (let [piece-index (aget indexes 5)
                    piece (nth pieces piece-index)
                    length (alength piece)
                    string-index (aget indexes 4)]
                (System/arraycopy piece 0 strings string-index length)
                (aset indexes 4 (+ string-index length))
                (aset indexes 5 (inc piece-index))
                length))
            (tag! [tag]
              (let [index (aget indexes 0)]
                (put-byte! ops index tag)
                (aset indexes 0 (inc index))))
            (length! [length]
              (let [index (aget indexes 0)]
                (put-u32! ops index length)
                (aset indexes 0 (+ index 4))))
            (raw! []
              (tag! tag-raw-number)
              (length! (alength (nth pieces (aget indexes 5))))
              (copy-piece!))
            (string! []
              (tag! tag-string)
              (length! (alength (nth pieces (aget indexes 5))))
              (copy-piece!))
            (write! [item depth]
              (cond
                (nil? item) (tag! tag-null)
                (false? item) (tag! tag-false)
                (true? item) (tag! tag-true)

                (integer? item)
                (cond
                  (< item min-i64) (raw!)
                  (neg? item)
                  (let [index (aget indexes 1)]
                    (tag! tag-sint)
                    (aset sints index item)
                    (aset indexes 1 (inc index)))
                  (<= item max-i64)
                  (let [index (aget indexes 2)]
                    (tag! tag-uint)
                    (aset uints index item)
                    (aset indexes 2 (inc index)))
                  :else (raw!))

                (decimal? item) (raw!)
                (number? item)
                (let [index (aget indexes 3)]
                  (tag! tag-real)
                  (aset reals index (finite-double item))
                  (aset indexes 3 (inc index)))

                (string? item) (string!)
                (or (symbol? item) (char? item)) (string!)

                (map? item)
                (do (check-depth! depth)
                    (tag! tag-object)
                    (length! (count item))
                    (doseq [[_ child] item]
                      (length! (alength (nth pieces (aget indexes 5))))
                      (copy-piece!)
                      (write! child (inc depth))))

                (or (sequential? item) (set? item))
                (do
                  (check-depth! depth)
                  (tag! tag-array)
                  (length! (count item))
                  (doseq [child item] (write! child (inc depth))))

                :else
                (throw (json-error
                        (str "Unsupported JSON value: " (pr-str item))))))]
      (write! value 0)
      {:ops ops :sints sints :uints uints :reals reals :strings strings})))

(defn- write-lanes! [ops-ptr sints-ptr uints-ptr reals-ptr strings-ptr
                     ops sints uints reals strings]
  (when (pos? (alength ops))
    (ffi/write-array ops-ptr ops))
  (when (pos? (alength sints))
    (ffi/write-array sints-ptr :int64 sints))
  (when (pos? (alength uints))
    (ffi/write-array uints-ptr :uint64 uints))
  (when (pos? (alength reals))
    (ffi/write-array reals-ptr :double reals))
  (when (pos? (alength strings))
    (ffi/write-array strings-ptr strings)))

(defn- write-and-free! [ops-ptr sints-ptr uints-ptr reals-ptr strings-ptr
                        ops sints uints reals strings pretty]
  ;; Manual out-parameters: nested with-out forms inline into one enormous
  ;; top-level form, which trips the AOT artifact writer.
  (let [output-length-ptr (ffi/alloc (ffi/sizeof :size_t))
        message-ptr (ffi/alloc (ffi/sizeof :pointer))]
    (try
      (let [output (native-write-tape
                    ops-ptr (alength ops)
                    sints-ptr (alength sints)
                    uints-ptr (alength uints)
                    reals-ptr (alength reals)
                    strings-ptr (alength strings)
                    (if pretty 1 0)
                    output-length-ptr message-ptr)]
        (when (ffi/null? output)
          (throw (ex-info (str "JSON write failed: "
                               (error-message message-ptr "unknown native error"))
                          {:type :jjson/error
                           :operation :write})))
        (try
          (ffi/read-bytes output (ffi/read output-length-ptr :size_t))
          (finally
            (native-free output))))
      (finally
        (ffi/free output-length-ptr)
        (ffi/free message-ptr)))))

(defn- write-json [value pretty]
  (let [{:keys [ops sints uints reals strings]} (value->tape value)
        ops-ptr (native-alloc (max 1 (alength ops)))]
    (try
      (let [sints-ptr (native-alloc (max 1 (* 8 (alength sints))))]
        (try
          (let [uints-ptr (native-alloc (max 1 (* 8 (alength uints))))]
            (try
              (let [reals-ptr (native-alloc (max 1 (* 8 (alength reals))))]
                (try
                  (let [strings-ptr (native-alloc (max 1 (alength strings)))]
                    (try
                      (write-lanes! ops-ptr sints-ptr uints-ptr reals-ptr strings-ptr
                                    ops sints uints reals strings)
                      (write-and-free! ops-ptr sints-ptr uints-ptr reals-ptr strings-ptr
                                       ops sints uints reals strings pretty)
                      (finally
                        (native-free strings-ptr))))
                  (finally
                    (native-free reals-ptr))))
              (finally
                (native-free uints-ptr))))
          (finally
            (native-free sints-ptr))))
      (finally
        (native-free ops-ptr)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- validate-read-options! [options]
  (cond
    (nil? options) nil
    (map? options)
    (do (doseq [key (keys options)]
          (when-not (= key :key-fn)
            (throw (json-error "Unknown from-json option"))))
        (if (contains? options :key-fn)
          (let [key-fn (:key-fn options)]
            (when-not (and key-fn (ifn? key-fn))
              (throw (json-error "from-json :key-fn must be callable")))
            key-fn)
          nil))
    :else (throw (json-error "from-json options must be a map"))))

(defn- validate-write-options! [options]
  (cond
    (nil? options) false
    (map? options)
    (do (doseq [key (keys options)]
          (when-not (= key :pretty)
            (throw (json-error "Unknown to-json option"))))
        (if (contains? options :pretty)
          (let [pretty (:pretty options)]
            (when-not (boolean? pretty)
              (throw (json-error "to-json :pretty must be a boolean")))
            pretty)
          false))
    :else (throw (json-error "to-json options must be a map"))))

(defn from-json
  "Parses a JSON string or UTF-8 byte array into Jolt values. Object keys are
   strings. Pass an options map containing only :key-fn to transform every
   object key (including nested ones); transformed-key collisions keep the
   last source value. Every failure is ex-info with {:type :jjson/error}."
  ([input]
   (wrap-error #(read-json input identity)))
  ([input options]
   (wrap-error #(read-json input
                           (or (validate-read-options! options) identity)))))

(defn to-json
  "Encodes VALUE as compact JSON. Pass {:pretty true} for indented output;
   that is the only option and it must be a boolean."
  ([value]
   (wrap-error #(write-json value false)))
  ([value options]
   (wrap-error #(write-json value (validate-write-options! options)))))