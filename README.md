# jjson

`jjson` is a yyjson-backed JSON codec for [Jolt](https://github.com/jolt-lang/jolt). Its public namespace is `jjson.core`.

## Requirements

- Jolt 0.8.23 or newer
- A C11 compiler and the `scheme.h` matching the installed Jolt/Chez runtime

The repository vendors yyjson 0.13.0. Build the native bridge before running from source:

```sh
./native/build.sh
```

The script builds `native/libjjson.dylib` or `native/libjjson.so` for development and `native/libjjson.a` for standalone builds. `deps.edn` declares both forms through `:jolt/native`; callers do not configure library paths. On the persistent x86-64 macOS installation the build finds `scheme.h` automatically; other installations set `CHEZ_INCLUDE` to its directory.

## API

```clojure
(require '[jjson.core :as json])

(json/from-json "{\"answer\":42}")
;; => {"answer" 42}

(json/from-json (.getBytes "[true,null]" "UTF-8"))
;; => [true nil]

(json/from-json "{\"user\":{\"id\":7}}" {:key-fn keyword})
;; => {:user {:id 7}}

(json/to-json {:answer 42})
;; => "{\"answer\":42}"

(json/to-json {:answer 42} {:pretty true})
;; => formatted JSON
```

`from-json` accepts a JSON string or a UTF-8 byte array. An optional `:key-fn` transforms every object key, including nested keys. Duplicate keys and transformed-key collisions keep the last value. Omitting `:key-fn` leaves keys as strings.

Do not use `keyword` as `:key-fn` for untrusted or unbounded keys. Clojure keywords are interned and cannot be reclaimed. Keep untrusted keys as strings or use a bounded transformer.

`to-json` encodes maps as objects and vectors, sequential values, and sets as arrays. String keys stay unchanged. Keywords become `name` or `namespace/name`; other map keys use `str`. Symbols and characters become strings. Bignums and BigDecimals retain their exact decimal digits, while ratios become doubles. NaN, infinities, and unsupported values fail.

The option maps are strict:

- `from-json`: only `:key-fn`; its value must be a function.
- `to-json`: only `:pretty`; its value must be `true` or `false`.
- `nil` is equivalent to an empty option map.

JSON inputs nested to depth 1000 are accepted. Deeper inputs fail. Integer tokens remain exact throughout the signed and unsigned 64-bit ranges. Other parsed number tokens become doubles. Input byte arrays are not modified.

Every public failure is `ex-info` with `{:type :jjson/error}` in `ex-data`.

## Native execution

Neither `from-json` nor `to-json` holds the Chez runtime while native code runs, so garbage collection on other threads proceeds during a decode or encode of any size. Decoding has three steps:

1. A collect-safe (`__collect_safe`) native call reads the locked input string or byte array, performs strict UTF-8 validation and yyjson parsing, and counts the values and characters the result needs.
2. Scheme allocates one character string and one record bytevector of exactly that size; a second collect-safe call fills them, locked, with one 16-byte record per JSON value and the decoded characters of every string. Repeated object keys are emitted once.
3. A Scheme loop walks the records and builds strings, numbers, persistent vectors, and maps; objects of up to eight entries are built directly as array maps. All managed allocation happens here, in ordinary Scheme code, where collection may run at any point.

Encoding is the mirror image:

1. One Scheme walk writes a 16-byte record per value into a growing bytevector and copies every string's characters into one growing string. Persistent maps and vectors, strings, numbers, nil, and booleans are handled directly; other values (sets, seqs, sorted maps, records, symbols, characters, decimals, ratios) are normalized by a Clojure fallback.
2. A collect-safe call reads both buffers, locked, and has yyjson write the JSON, so number formatting and pretty layout are yyjson's.
3. Scheme allocates the result string at its exact length; a second collect-safe call decodes the output into it, locked.

Native code touches only objects locked for the duration of its call and never allocates Scheme objects. All parser, writer, and key-interning state is request-local.

Standalone applications link `libjjson.a` into the executable:

```sh
jolt -A:build-smoke build -m jjson.build-smoke --closed-world -o /tmp/jjson-build-smoke
(cd / && /tmp/jjson-build-smoke)
```

The resulting executable does not need a `libjjson` sidecar or a repository-relative working directory.

## Verification

```sh
./native/build.sh
jolt test/smoke.clj
jolt test/stress.clj
```

The smoke suite covers value mapping, options, duplicate and transformed keys, Unicode and malformed UTF-8, numeric boundaries, exact decimal output, nesting limits, input immutability, and public error classification. The stress suite exercises concurrent parse/write operations, large documents, allocation churn, and termination without corruption or deadlock.

## Benchmarks

`bench/run.sh` compares `jjson` with `clojure.data.json` on Jolt and Jsonista on the JVM across the Boost.JSON 1.83 corpus in `bench/data/`. It release-builds the Jolt runner, validates semantics outside the clock, and runs one fresh benchmark process at a time. Five whole-corpus samples plus exact batch counts and elapsed nanoseconds are written to `bench/results/`; append-only live status is written to `bench/results/progress.edn`.

See [BENCHMARKS.md](BENCHMARKS.md) for the measured commands, environment, and results.
