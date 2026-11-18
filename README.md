# jjson

[![CI](https://github.com/antlobach/jjson/actions/workflows/ci.yml/badge.svg)](https://github.com/antlobach/jjson/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/antlobach/jjson)](LICENSE)
[![Last commit](https://img.shields.io/github/last-commit/antlobach/jjson)](https://github.com/antlobach/jjson/commits/main)
[![GitHub stars](https://img.shields.io/github/stars/antlobach/jjson?style=flat)](https://github.com/antlobach/jjson/stargazers)

jjson converts JSON to Jolt values and Jolt values to JSON. It uses [yyjson](https://github.com/ibireme/yyjson) through a native bridge. Its public namespace is `jjson.core`.

The decoder returns persistent Jolt maps and vectors. The encoder returns a string or UTF-8 bytes. Long native calls permit garbage collection on other threads.

```clojure
(require '[jjson.core :as jj])

(jj/from-json "{\"answer\":42}")
;; => {"answer" 42}

(jj/to-json {:answer 42})
;; => "{\"answer\":42}"

(jj/from-json (jj/to-json-bytes {:message "Hello, λ😀"}))
;; => {"message" "Hello, λ😀"}
```

## Installation

Use **Jolt v0.8.27**.

Add this dependency to your application's `deps.edn`:

```clojure
{:deps {io.github.antlobach/jjson
        {:git/sha "b7ba11dc4421738edcb914aab06b7e7c2a5037f4"}}}
```

Then require the namespace:

```clojure
(require '[jjson.core :as jj])
```

Jolt obtains the source and runs the native setup task on first use. The task downloads a prebuilt library and checks its SHA-256 checksum. Later runs use the installed file.

Normal use does not require a C compiler, Chez headers, a manual clone, or library search paths. The setup task uses Git, `curl`, `tar`, and a SHA-256 tool.

Prebuilt libraries support these systems:

- macOS 13 or later on Apple Silicon.
- macOS 13 or later on Intel x86-64.
- Linux x86-64, built on Ubuntu 22.04.

This source pin uses the `v0.1.0-rc.1` preview binaries. Unsupported systems need a source build.

## API

### `from-json`

```clojure
(jj/from-json input)
(jj/from-json input options)
```

The input must be a JSON string or a UTF-8 byte array. The decoder does not change input byte arrays.

| JSON value | Jolt value |
|---|---|
| Object | Map with string keys |
| Array | Vector |
| `null` | `nil` |
| Boolean | Boolean |
| String | String |
| Number | Integer or double, unless an exact-number option changes the conversion |

```clojure
(jj/from-json (.getBytes "[true,null,42]" "UTF-8"))
;; => [true nil 42]

(jj/from-json "{\"user\":{\"id\":7}}" {:key-fn keyword})
;; => {:user {:id 7}}
```

| Option | Default | Function |
|---|---|---|
| `:key-fn` | Identity | Applies a callable to each object key, including keys in nested objects. |
| `:bigints` | `false` | Keeps integer tokens exact, including integers outside the signed and unsigned 64-bit ranges. |
| `:bigdecimals` | `false` | Converts decimal and exponent tokens to exact BigDecimals without an intermediate double. |
| `:reject-duplicate-keys` | `false` | Rejects repeated decoded names and collisions after `:key-fn` within each object. |

Boolean options accept only `true` or `false`. Unknown options cause an error. An options argument of `nil` has the same effect as an empty map.

#### Exact numbers

```clojure
(jj/from-json "184467440737095516161234567890" {:bigints true})
;; => 184467440737095516161234567890N

(jj/from-json "1.2300000000000000000001" {:bigdecimals true})
;; => 1.2300000000000000000001M

(jj/from-json "[18446744073709551616,1.2300]"
              {:bigints true :bigdecimals true})
;; => [18446744073709551616N 1.2300M]
```

Without exact-number options, the decoder keeps integer tokens exact throughout the signed and unsigned 64-bit ranges. It converts larger integers, decimal tokens, and exponent tokens to doubles.

Numbers outside the finite double range cause an error. Underflow can round a number to zero.

The two exact-number options act independently. Use both options when all numbers must retain precision. Exact decimal exponents must fit Jolt's BigDecimal representation. Unsupported decimal values cause an error instead of silent rounding.

#### Duplicate keys

The default decoder keeps the last value for each key. This rule also applies when `:key-fn` converts different names to the same key.

Strict mode checks decoded names before the key conversion. It also checks the converted keys. The check applies separately to each object.

For example, `"\u0061"` and `"a"` have the same decoded name. The same name in two separate objects does not cause a collision.

```clojure
(jj/from-json "{\"id\":1,\"id\":2}")
;; => {"id" 2}

(jj/from-json "{\"id\":1,\"id\":2}" {:reject-duplicate-keys true})
;; throws ex-info with :reason :duplicate-key
```

### `to-json` and `to-json-bytes`

```clojure
(jj/to-json value)
(jj/to-json value {:pretty true})

(jj/to-json-bytes value)
(jj/to-json-bytes value {:pretty true})
```

`to-json` returns a string. `to-json-bytes` returns a Jolt byte array with UTF-8 JSON. The byte array has no byte-order mark or trailing NUL.

The byte encoder copies yyjson's UTF-8 output into the result buffer. It does not create an intermediate JSON string.

Both functions accept only the `:pretty` option. This option must be a boolean. It defaults to `false`. Unknown options cause an error. An options argument of `nil` has the same effect as an empty map.

| Jolt value | JSON representation |
|---|---|
| Map | Object |
| Vector or sequence | Array |
| Set | Array with unspecified order |
| String, symbol, or character | String |
| `nil` | `null` |
| Boolean | Boolean |
| Integer or BigDecimal | Exact decimal digits |
| Ratio | Double |

String map keys do not change. Keyword keys become `name` or `namespace/name`. The encoder applies `str` to other map keys.

NaN, infinities, unsupported values, and invalid Unicode cause an error. Map iteration order determines the output order. The encoder does not produce canonical JSON.

### Errors

Public failures use `ex-info`. Their `ex-data` contains `:type :jjson/error`. The library does not catch fatal runtime failures.

```clojure
(try
  (jj/from-json "{\"x\":}")
  (catch Exception e
    (ex-data e)))
;; => {:type :jjson/error, :operation :read, :code 6,
;;     :byte-offset 5, :line 1, :column 6}
```

Syntax errors and malformed UTF-8 errors include these source fields:

| Field | Meaning |
|---|---|
| `:byte-offset` | Zero-based byte position in the original input |
| `:line` | One-based line number |
| `:column` | One-based column in UTF-8 bytes, not Unicode code points |

The decoder counts CRLF as one newline.

Positive native error codes are yyjson reader codes. Code `-2` identifies malformed UTF-8. Code `-3` identifies a failure after parsing, such as a depth-limit error.

Errors without a source position need not include location fields. Allocation errors are one example.

Duplicate-key errors include `:reason :duplicate-key`, `:key`, and `:transformed?`. The last field identifies a collision after key conversion.

### Limits

- The decoder accepts nesting through depth **1,000**. Greater depth causes an error.
- The decoder holds the complete input and result in memory.
- The API does not include streaming, NDJSON, selective queries, or native document handles.
- Do not change a shared input byte array while a decode runs.

## Runtime and memory safety

The library does not change Jolt's runtime or garbage collector. It uses Jolt collection internals and Chez's object ABI. It checks several required bindings when the namespace loads.

| Term | Meaning in this library |
|---|---|
| Collect-safe call | A native call that deactivates its Scheme thread while C runs. Other threads can request garbage collection. |
| Pinned buffer | A Scheme object that the garbage collector cannot move or reclaim during the native call. |
| Native handle | Request state that C allocates and the library releases. The public API does not expose this state. |

During decoding, collect-safe native calls parse the input and fill value records and character buffers. Scheme allocates these buffers. Scheme then constructs the persistent collections.

Encoding uses the same separation between C work and Scheme allocation. C does not allocate Scheme objects or call Scheme.

The library pins only the buffers that C accesses. It releases the pins when each call ends. The `dynamic-wind` cleanup releases pins and native handles after normal returns and exceptions.

Each request has separate parser, writer, and key-table state. Calls are synchronous. The API does not provide asynchronous execution or cancellation.

### Standalone applications

Build and run the example:

```sh
jolt -A:build-smoke build -m jjson.build-smoke --closed-world -o /tmp/jjson-build-smoke
(cd / && /tmp/jjson-build-smoke)
```

Jolt links the static archive into the executable. The executable does not require a separate native library or a repository-relative working directory.

## Development builds

Normal installation does not need these steps.

A source build needs a C11 compiler, `ar`, and Chez 10.4.1 headers for the target architecture. yyjson is part of the source.

```sh
git clone https://github.com/antlobach/jjson.git
cd jjson
jolt native
```

The build searches standard Chez installation paths. Set these variables if automatic detection fails:

| Variable | Function |
|---|---|
| `CC` | Selects the compiler executable. The default is `cc` on `PATH`. |
| `AR` | Selects the archiver executable. The default is `ar` on `PATH`. |
| `JOLT_CHEZ_CSV` | Selects the complete Chez runtime directory, including headers and static libraries. |
| `CHEZ_INCLUDE` | Overrides the header directory for the native build. It takes precedence over `JOLT_CHEZ_CSV` for headers. |

```sh
CC=/path/to/clang \
AR=/path/to/ar \
JOLT_CHEZ_CSV=/path/to/chez/lib/csv10.4.1/ta6le \
jolt native
```

Use `ta6osx` on Intel macOS or `tarm64osx` on Apple Silicon. Do not use headers from a different Chez version or architecture.

The [CI workflow](.github/workflows/ci.yml) includes commands to build the matching Chez installation.

## Tests and proofs

Run these commands from the repository directory:

```sh
jolt native
jolt test/smoke.clj
jolt -M:test-reference test/differential.clj
jolt test/stress.clj
bash test/run-conformance.sh
bash test/run-native-safety.sh
bash test/run-proofs.sh
```

If Jolt is not on `PATH`, set `JOLT=/absolute/path/to/jolt` for the conformance runner.

The reference tests compare 1,000 deterministic values with `clojure.data.json` on Jolt. They check string output, byte output, string input, and byte input.

### JSON conformance

The first run obtains a pinned [JSONTestSuite](https://github.com/nst/JSONTestSuite) revision in `.cache/`. The runner tests each fixture through the public byte-input API. It tests the default mode and the exact-number mode.

| Prefix | Required result |
|---|---|
| `y_` | Accept |
| `n_` | Reject |
| `i_` | Accept or reject. The runner reports these results separately. |

A watchdog stops a test process that exceeds the time limit. The runner treats required-result failures and process crashes as failures.

### Native memory checks

The safety harness needs a complete matching Chez installation beside `scheme.h`. This installation must include boot files and static libraries.

The harness uses:

- Real Chez objects with forced garbage collection.
- AddressSanitizer and UndefinedBehaviorSanitizer.
- Deterministic allocation failures.
- Allocation counts for the bridge and yyjson.

Each completed request must leave zero live codec allocations. The harness checks byte output, string output, malformed input, and output buffers with insufficient space.

### Formal checks

The proof runner requires Z3. Five bit-vector obligations check selected 64-bit calculations for buffer sizes and indexes. The models use the guards from the bridge.

These models do not prove the complete C implementation, yyjson, or the garbage collector. They do not prove the absence of all possible leaks. CI also runs Clang's path-sensitive static analyzer.

The [Allium contract](spec/jjson.allium) describes public behavior. Run `allium check spec/jjson.allium` to check its structure. A successful structure check does not prove that the implementation meets the contract.

See [BENCHMARKS.md](BENCHMARKS.md) for recorded measurements and methods. That report does not include measurements for the new exact-number modes.

## For AI agents

- Use `jjson.core` with `from-json`, `to-json`, and `to-json-bytes`.
- Use `jj` as the namespace alias for `jjson.core`.
- Do not invent Cheshire aliases, streaming methods, or native document handles.
- Keep external keys as strings unless the schema bounds the key set.
- Do not convert an unbounded set of external keys to keywords.
- Use `{:bigints true}` for integer identifiers outside the 64-bit ranges.
- Use `{:bigdecimals true}` when decimal values must retain precision.
- Use both options when all numeric input must retain precision within the supported decimal range.
- Add `{:reject-duplicate-keys true}` when duplicate keys must cause an error.
- Inspect `ex-data` to classify errors. Do not classify errors from message text.
- Use `to-json-bytes` when the next component requires UTF-8 bytes.
- Do not convert that byte output to a string without a consumer requirement.
- Rebuild the native library after bridge changes.
- Restart the process after rebuilding a loaded shared library. Namespace reload does not reliably replace loaded machine code.
- Preserve collect-safe declarations, buffer pins, and cleanup after exceptions.
- Do not allocate managed objects or call Scheme from a deactivated native call.
- Run the test commands after changes.
- Report the actual results and the limits of formal checks.
- Do not treat parser acceptance, round trips, or a successful sanitizer run as complete proof of correctness.
- Change this library to correct codec defects. Do not patch Jolt's runtime to conceal a codec defect.

## License

This project uses the [MIT license](LICENSE). Vendored yyjson retains its own MIT notices in `native/yyjson.c` and `native/yyjson.h`.
