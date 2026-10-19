(ns jjson.core
  "Fast yyjson-backed JSON encoding and decoding for Jolt.

   Decoding parses with yyjson inside collect-safe native calls that write
   compact value records and string characters into locked Chez objects; a
   Scheme loop then builds Jolt values while garbage collection proceeds
   normally. Encoding mirrors it: a Scheme walk writes value records, and
   collect-safe calls have yyjson write the JSON into a locked result string.
   The codec is intentionally Chez/Jolt-specific."
  (:require [jolt.ffi :as ffi]
            [jolt.scheme :as scheme]))

(def ^:private min-i64 -9223372036854775808)
(def ^:private max-i64 9223372036854775807)

;; ---------------------------------------------------------------------------
;; Native ABI
;; ---------------------------------------------------------------------------

;; These declarations anchor the codec symbols in packaged static builds.
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
(ffi/defcfn native-encode-anchor "jjson_encode"
  [:pointer :size_t :pointer :size_t :int] :pointer :blocking)
(ffi/defcfn native-encode-error-anchor "jjson_encode_error" [:pointer] :pointer)
(ffi/defcfn native-encode-chars-anchor "jjson_encode_chars" [:pointer] :size_t)
(ffi/defcfn native-encode-fill-anchor "jjson_encode_fill"
  [:pointer :pointer] :int :blocking)
(ffi/defcfn native-encode-free-anchor "jjson_encode_free"
  [:pointer] :void :blocking)

;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

(defn- json-error [message]
  (ex-info message {:type :jjson/error}))

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

;; Host collection internals the codec calls directly. Record predicates and
;; accessors (pmap?, pvec?, pvec-cnt, keyword-t?, ...) compile inline and
;; are exercised when the codec namespaces load their Scheme code.
(def ^:private missing-host-bindings
  (scheme/eval-string
   "(let loop ([names '(make-pvec pv-leaf-for pmap-kv-reduce amap-index
                        amap-slots->pmap vec-copy-range)])
      (cond
        [(null? names) #f]
        [(top-level-bound? (car names)) (loop (cdr names))]
        [else (symbol->string (car names))]))"))

(when missing-host-bindings
  (throw (json-error (str "This Jolt runtime does not expose " missing-host-bindings))))

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

(when-not jolt-array-vec
  (throw (json-error "This Jolt runtime does not expose byte-array storage")))

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
                     (if (#3%fx<= n 8)
                         ;; Up to 8 entries a map stays an array map, so build
                         ;; its slots directly: a repeated key keeps its first
                         ;; position and takes the last value, exactly as
                         ;; assoc! does.
                         (let ([slots (make-vector (#3%fx* 2 n))])
                           (let loop ([i 0] [used 0])
                             (if (#3%fx= i n)
                                 (cond
                                   [(#3%fx= used 0) empty-map]
                                   [(#3%fx= used (#3%vector-length slots))
                                    (amap-slots->pmap slots)]
                                   [else (amap-slots->pmap (vec-copy-range slots 0 used))])
                                 (let* ([k (read-key)]
                                        [k (if key-fn (key-fn k) k)]
                                        [v (read)]
                                        [j (amap-index slots used k)])
                                   (if (#3%fx>= j 0)
                                       (begin
                                         (#3%vector-set! slots (#3%fx+ j 1) v)
                                         (loop (#3%fx+ i 1) used))
                                       (begin
                                         (#3%vector-set! slots used k)
                                         (#3%vector-set! slots (#3%fx+ used 1) v)
                                         (loop (#3%fx+ i 1) (#3%fx+ used 2))))))))
                         (let loop ([i 0] [m (transient empty-map)])
                           (if (#3%fx= i n)
                               (persistent! m)
                               (let* ([k (read-key)]
                                      [k (if key-fn (key-fn k) k)]
                                      [v (read)])
                                 (loop (#3%fx+ i 1) (assoc! m k v)))))))])))
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

(defn- encode-fallback
  "Normalizes values the Scheme fast path does not recognize. Returns
   [0 v] to encode v in place of value, [1 digits] for an exact decimal
   number, or [2 kvs] for a map as a flat key/value vector in iteration
   order."
  [value]
  (cond
    (integer? value) (if (<= min-i64 value max-i64)
                       [0 (long value)]
                       [1 (str value)])
    (decimal? value) [1 (str value)]
    (number? value) [0 (finite-double value)]
    (or (symbol? value) (char? value)) [0 (str value)]
    (map? value) [2 (persistent!
                     (reduce (fn [kvs [k v]] (conj! (conj! kvs k) v))
                             (transient [])
                             value))]
    (or (sequential? value) (set? value)) [0 (vec value)]
    :else (throw (json-error (str "Unsupported JSON value: " (pr-str value))))))

;; Builds the encoder. One Scheme walk writes 16-byte records (the
;; jjson_write_records layout: tag, u64 payload at offset 8) and copies every
;; string's characters into one growing string. Persistent maps, vectors,
;; strings, numbers, nil, and booleans are handled here; every other value
;; goes through encode-fallback. The collect-safe C call reads the locked
;; buffers, has yyjson write the JSON, and a second collect-safe call decodes
;; it into a locked result string. All managed allocation happens in Scheme.
(def ^:private make-encoder
  (scheme/eval-string
   "(lambda (a-encode a-error a-chars a-fill a-free key->string fallback fail fail-native)
      (let ([c-encode (foreign-procedure __collect_safe a-encode
                        (scheme-object size_t scheme-object size_t int) uptr)]
            [c-error (foreign-procedure a-error (uptr) utf-8)]
            [c-chars (foreign-procedure a-chars (uptr) size_t)]
            [c-fill (foreign-procedure __collect_safe a-fill (uptr scheme-object) int)]
            [c-free (foreign-procedure __collect_safe a-free (uptr) void)])
        (define (pvec-ref p i)
          (let-values ([(chunk off) (pv-leaf-for p i)])
            (vector-ref chunk off)))
        (define (encode value pretty)
          (let ([records (make-bytevector 512)] [nrecords 0]
                [chars (make-string 256)] [nchars 0])
            (define (record! tag)
              (let ([off (fx* nrecords 16)])
                (when (fx> (fx+ off 16) (bytevector-length records))
                  (let ([bigger (make-bytevector (fx* 2 (bytevector-length records)))])
                    (bytevector-copy! records 0 bigger 0 off)
                    (set! records bigger)))
                (bytevector-u8-set! records off tag)
                (set! nrecords (fx+ nrecords 1))
                off))
            ;; record! may replace `records`, so it must run before `records`
            ;; is read: argument evaluation order is unspecified.
            (define (put-u64! tag n)
              (let ([off (record! tag)])
                (bytevector-u64-native-set! records (fx+ off 8) n)))
            (define (put-s64! tag n)
              (let ([off (record! tag)])
                (bytevector-s64-native-set! records (fx+ off 8) n)))
            (define (put-string! tag s)
              (let ([n (string-length s)])
                (when (fx> (fx+ nchars n) (string-length chars))
                  (let ([bigger (make-string (fxmax (fx* 2 (string-length chars))
                                                    (fx+ nchars n)))])
                    (string-copy! chars 0 bigger 0 nchars)
                    (set! chars bigger)))
                (string-copy! s 0 chars nchars n)
                (set! nchars (fx+ nchars n))
                (put-u64! tag n)))
            (define (put-key! k)
              (put-string! 6 (cond
                               [(string? k) k]
                               [(and (keyword-t? k) (not (keyword-t-ns k))) (keyword-t-name k)]
                               [else (key->string k)])))
            (define (check-depth! depth)
              (when (fx>= depth 1000)
                (fail \"JSON nesting exceeds the maximum depth of 1000\")))
            (define (put-object! kvs depth)
              (check-depth! depth)
              (let ([n (pvec-cnt kvs)] [child (fx+ depth 1)])
                (put-u64! 8 (fxquotient n 2))
                (let loop ([i 0])
                  (when (fx< i n)
                    (put-key! (pvec-ref kvs i))
                    (enc (pvec-ref kvs (fx+ i 1)) child)
                    (loop (fx+ i 2))))))
            (define (enc v depth)
              (cond
                [(string? v) (put-string! 6 v)]
                [(fixnum? v) (if (fx< v 0) (put-s64! 3 v) (put-u64! 4 v))]
                [(flonum? v)
                 (if (finite? v)
                     (let ([off (record! 5)])
                       (bytevector-ieee-double-native-set! records (fx+ off 8) v))
                     (fail \"JSON cannot represent NaN or infinity\"))]
                [(eq? v jolt-nil) (record! 0)]
                [(eq? v #f) (record! 1)]
                [(eq? v #t) (record! 2)]
                [(pmap? v)
                 (check-depth! depth)
                 (let* ([off (record! 8)]
                        [child (fx+ depth 1)]
                        [n (pmap-kv-reduce v
                                           (lambda (count k x)
                                             (put-key! k)
                                             (enc x child)
                                             (fx+ count 1))
                                           0)])
                   (bytevector-u64-native-set! records (fx+ off 8) n))]
                [(pvec? v)
                 (check-depth! depth)
                 (let ([n (pvec-cnt v)] [child (fx+ depth 1)])
                   (put-u64! 7 n)
                   (let loop ([i 0])
                     (when (fx< i n)
                       (let-values ([(chunk off) (pv-leaf-for v i)])
                         (let ([run (fxmin (fx- (vector-length chunk) off) (fx- n i))])
                           (do ([j 0 (fx+ j 1)]) ((fx= j run))
                             (enc (vector-ref chunk (fx+ off j)) child))
                           (loop (fx+ i run)))))))]
                [(and (integer? v) (exact? v))
                 (cond
                   [(and (>= v -9223372036854775808) (< v 0)) (put-s64! 3 v)]
                   [(and (>= v 0) (<= v 9223372036854775807)) (put-u64! 4 v)]
                   [else (put-string! 9 (number->string v))])]
                [(and (rational? v) (exact? v)) (enc (inexact v) depth)]
                [else
                 (let ([result (fallback v)])
                   (case (pvec-ref result 0)
                     [(0) (enc (pvec-ref result 1) depth)]
                     [(1) (put-string! 9 (pvec-ref result 1))]
                     [else (put-object! (pvec-ref result 1) depth)]))]))
            (enc value 0)
            (let ([handle (begin
                            (lock-object records)
                            (lock-object chars)
                            (dynamic-wind
                              void
                              (lambda () (c-encode records nrecords chars nchars (if pretty 1 0)))
                              (lambda () (unlock-object records) (unlock-object chars))))])
              (when (eqv? handle 0) (fail \"Unable to allocate JSON encode state\"))
              (dynamic-wind
                void
                (lambda ()
                  (let ([message (c-error handle)])
                    (when message (fail-native message)))
                  (let ([output (make-string (c-chars handle))])
                    (lock-object output)
                    (let ([ok (dynamic-wind
                                void
                                (lambda () (c-fill handle output))
                                (lambda () (unlock-object output)))])
                      (when (fx= ok 0)
                        (fail-native (or (c-error handle) \"unknown native error\")))
                      output)))
                (lambda () (c-free handle))))))
        encode))"))

(def ^:private write-json
  (make-encoder (ffi/find-symbol "jjson_encode")
                (ffi/find-symbol "jjson_encode_error")
                (ffi/find-symbol "jjson_encode_chars")
                (ffi/find-symbol "jjson_encode_fill")
                (ffi/find-symbol "jjson_encode_free")
                key->string
                encode-fallback
                (fn [message] (throw (json-error message)))
                (fn [message]
                  (throw (ex-info (str "JSON write failed: " message)
                                  {:type :jjson/error :operation :write})))))

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