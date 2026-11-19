# jjson

[![Build](https://github.com/antlobach/jjson/actions/workflows/ci.yml/badge.svg)](https://github.com/antlobach/jjson/actions/workflows/ci.yml)
[![Codecov](https://codecov.io/gh/antlobach/jjson/graph/badge.svg)](https://app.codecov.io/gh/antlobach/jjson)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Version](https://img.shields.io/github/v/release/antlobach/jjson?include_prereleases&label=Version)](https://github.com/antlobach/jjson/releases)
[![Jolt](https://img.shields.io/badge/Jolt-v0.8.27-blue.svg)](https://github.com/jolt-lang/jolt/releases/tag/v0.8.27)
[![Ask DeepWiki](https://img.shields.io/badge/Ask-DeepWiki-blue)](https://deepwiki.com/antlobach/jjson)

JSON encoding and decoding for [Jolt](https://github.com/jolt-lang/jolt), backed by [yyjson](https://github.com/ibireme/yyjson).

## Installation

Use **Jolt v0.8.27**. Add this dependency to `deps.edn`:

```clojure
{:deps {io.github.antlobach/jjson
        {:git/sha "e0312c24b260a15e04c7b62b9d8f513c84edf41d"}}}
```

```clojure
(require '[jjson :as jj])

(jj/from-json "{\"answer\":42}")
;; => {"answer" 42}

(jj/to-json {:answer 42})
;; => "{\"answer\":42}"

(jj/from-json (jj/to-json-bytes {:message "λ😀"}))
;; => {"message" "λ😀"}
```

Jolt downloads a prebuilt library on first use and checks its SHA-256 checksum.

Prebuilt libraries support macOS 13+ on Intel and Apple Silicon, and Linux x86-64 on Ubuntu 22.04 or compatible systems. Setup requires Git, `curl`, `tar`, and a SHA-256 tool. Other systems require a source build.

## API

| Function | Input | Output | Accepted options |
|---|---|---|---|
| `jj/from-json` | JSON string or UTF-8 byte array | Jolt value | `:key-fn`, `:bigints`, `:bigdecimals`, `:reject-duplicate-keys` |
| `jj/to-json` | Jolt value | JSON string | `:pretty` |
| `jj/to-json-bytes` | Jolt value | UTF-8 byte array, without a BOM or trailing NUL | `:pretty` |

Each function accepts one required argument and an optional options map. Omitted options and `nil` are equivalent to `{}`. Unknown options and invalid option types cause `ex-info`.

### Decode options

| Option | Type | Default | Effect |
|---|---|---|---|
| `:key-fn` | Non-nil callable | `identity` | Converts each object key, including nested keys. |
| `:bigints` | Boolean | `false` | Preserves integer tokens without rounding, including values outside 64-bit ranges. |
| `:bigdecimals` | Boolean | `false` | Converts decimal and exponent tokens directly to BigDecimals. |
| `:reject-duplicate-keys` | Boolean | `false` | Rejects duplicate decoded names and collisions after `:key-fn` within each object. |

```clojure
(jj/from-json "{\"user\":{\"id\":7}}" {:key-fn keyword})
;; => {:user {:id 7}}

(jj/from-json "[18446744073709551616,1.2300]"
              {:bigints true :bigdecimals true})
;; => [18446744073709551616N 1.2300M]
```

Objects become persistent maps. Arrays become vectors. JSON `null` becomes `nil`.

By default, integer tokens remain exact within the signed and unsigned 64-bit ranges. Larger integers and decimal or exponent tokens become doubles. Overflow causes an error. Underflow can round to zero.

The exact-number options act independently. Use both to preserve all numeric precision. Decimal exponents must fit Jolt's BigDecimal representation.

Duplicate keys keep their last value unless strict checking is enabled. Decoding does not change input bytes. Do not change a shared byte array during decoding.

### Encode options and values

| Option | Type | Default | Effect |
|---|---|---|---|
| `:pretty` | Boolean | `false` | Writes indented JSON instead of compact JSON. Applies to both encoders. |

Boolean options accept only `true` or `false`. `to-json-bytes` does not create an intermediate JSON string.

Maps become objects. Vectors, sequences, and sets become arrays. Strings, symbols, and characters become strings. Integers and BigDecimals retain exact decimal digits. Ratios become doubles.

Keyword keys become `name` or `namespace/name`. String keys do not change. Other keys use `str`. Map iteration determines object order. Set order is unspecified. Output is not canonical JSON.

NaN, infinities, invalid Unicode, and unsupported values cause errors.

### Errors and limits

Public failures use `ex-info` with `:type :jjson/error` in `ex-data`. Inspect `:operation`, `:code`, and `:reason` rather than message text.

Syntax and malformed UTF-8 errors include `:byte-offset`, `:line`, and `:column`. Offsets are zero-based. Lines and columns are one-based. Columns count UTF-8 bytes. CRLF counts as one newline.

Positive codes are yyjson reader codes. Code `-2` means malformed UTF-8. Code `-3` means failure after parsing. Errors without a source position can omit location fields.

Duplicate-key errors include `:reason :duplicate-key`, `:key`, and `:transformed?`. The last field identifies collisions after key conversion.

The decoder supports nesting through depth **1,000** and holds the complete input and result in memory. Calls are synchronous. Streaming, NDJSON, selective queries, and public native handles are not supported.

## Build from source

Requirements:

- Linux or macOS, Git, Bash, and Jolt **v0.8.27**.
- A C11 compiler, such as Clang or GCC, and `ar`.
- Chez **10.4.1** headers for the target architecture, including `scheme.h`.

yyjson is included. No separate yyjson package is required.

```sh
git clone https://github.com/antlobach/jjson.git
cd jjson
jolt native
```

`jolt native` compiles the source. It does not download a prebuilt package. It creates the shared library and `native/libjjson.a`.

To use this checkout from another project, replace the Git dependency with:

```clojure
{:deps {io.github.antlobach/jjson
        {:local/root "/absolute/path/to/jjson"}}}
```

The build detects standard Chez paths. Set these variables when detection fails:

| Variable | Purpose |
|---|---|
| `CC` | Compiler executable |
| `AR` | Archiver executable |
| `JOLT_CHEZ_CSV` | Complete Chez runtime directory |
| `CHEZ_INCLUDE` | Header directory override |

```sh
CC=/path/to/clang AR=/path/to/ar \
CHEZ_INCLUDE=/path/to/chez/lib/csv10.4.1/ta6le \
jolt native
```

`CHEZ_INCLUDE` takes precedence over `JOLT_CHEZ_CSV`. Use `ta6osx` on Intel macOS or `tarm64osx` on Apple Silicon.

Use matching headers for the Chez version and target architecture. The [CI workflow](.github/workflows/ci.yml) shows the complete toolchain setup.

### Checks

```sh
jolt test/smoke.clj
jolt -M:test-reference test/differential.clj
jolt test/stress.clj
bash test/run-conformance.sh
bash test/run-native-safety.sh
bash test/run-proofs.sh
```

The suite includes pinned [JSONTestSuite](https://github.com/nst/JSONTestSuite) fixtures, 1,000 reference comparisons, concurrent GC, allocation failures, and zero-live-allocation checks. CI runs ASan, UBSan, Clang analysis, and Linux LeakSanitizer with an intentional-leak negative control.

Native safety checks require a complete matching Chez installation. The proof runner requires Z3. Five proofs cover selected buffer calculations, not the complete codec or garbage collector.

Codecov covers `native/jjson.c` and `native/jjson_chez.c`, not Clojure or vendored yyjson. Set `JJSON_COVERAGE_DIR` to export local coverage. Clang and matching `LLVM_PROFDATA` and `LLVM_COV` tools are required.

See [benchmarks](BENCHMARKS.md), the [behavior contract](spec/jjson.allium), and [CI](.github/workflows/ci.yml) for details.

## For AI agents

- Require `[jjson :as jj]`. Use only `from-json`, `to-json`, and `to-json-bytes`.
- Keep unbounded external keys as strings. Use both exact-number options when all numbers must retain precision.
- Inspect `ex-data`. Do not infer errors from message text or invent streaming APIs.
- Preserve collect-safe calls, buffer pins, and cleanup after exceptions. C must not allocate Scheme objects or call Scheme during deactivated calls.
- Rebuild native code after bridge changes. Restart the process after replacing a loaded shared library.
- Run the checks above. Do not treat tests or limited arithmetic proofs as complete verification.

## License

[MIT](LICENSE). Vendored yyjson retains its MIT notices.
