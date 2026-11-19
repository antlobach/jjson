# jjson

[![Build](https://github.com/antlobach/jjson/actions/workflows/ci.yml/badge.svg)](https://github.com/antlobach/jjson/actions/workflows/ci.yml)
[![Codecov](https://codecov.io/gh/antlobach/jjson/graph/badge.svg)](https://app.codecov.io/gh/antlobach/jjson)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Version](https://img.shields.io/github/v/release/antlobach/jjson?include_prereleases&label=Version)](https://github.com/antlobach/jjson/releases)
[![Jolt](https://img.shields.io/badge/Jolt-v0.8.27-blue.svg)](https://github.com/jolt-lang/jolt/releases/tag/v0.8.27)
[![Ask DeepWiki](https://img.shields.io/badge/Ask-DeepWiki-blue)](https://deepwiki.com/antlobach/jjson)

JSON encoding and decoding for [Jolt](https://github.com/jolt-lang/jolt), backed by [yyjson](https://github.com/ibireme/yyjson).

## Installation

Requires **Jolt v0.8.27**. Add to `deps.edn`:

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

Native packages install automatically with SHA-256 verification. Supported platforms: macOS 13+ on Intel/Apple Silicon and Linux x86-64 on Ubuntu 22.04 or compatible systems.

Setup tools: Git, `curl`, `tar`, and `sha256sum` or `shasum`.

## API

### `from-json`

Parses a complete JSON document.

```clojure
(jj/from-json input)
(jj/from-json input options)
```

**Arguments**

- `input` — String or UTF-8 byte array containing JSON. Byte arrays remain unchanged and must not be mutated during decoding.
- `options` — Map or `nil`, default `{}`:
  - `:key-fn` — Non-nil callable, default `identity`. Receives each decoded object key as a string and returns its map key.
  - `:bigints` — Boolean, default `false`. Preserves integer tokens exactly, including values outside 64-bit ranges.
  - `:bigdecimals` — Boolean, default `false`. Converts decimal and exponent tokens directly to BigDecimals.
  - `:reject-duplicate-keys` — Boolean, default `false`. Rejects duplicate decoded names and transformed-key collisions within each object. Otherwise, the last value wins.

**Returns:** A Jolt value. Objects become persistent maps, arrays become vectors, and `null` becomes `nil`. Strings and booleans retain their types.

Default integer conversion is exact across signed and unsigned 64-bit ranges. Larger integers and decimal/exponent tokens become doubles. Overflow causes an error. Underflow can round to zero. Exact decimal conversion uses Jolt's BigDecimal exponent limits.

Maximum nesting depth: **1,000**. The complete input and result remain in memory.

### `to-json`

Encodes a Jolt value as a JSON string.

```clojure
(jj/to-json value)
(jj/to-json value options)
```

**Arguments**

- `value` — A map, vector, sequence, set, string, symbol, character, number, boolean, or `nil`.
  - Maps become objects. Vectors, sequences, and sets become arrays.
  - Strings, symbols, and characters become strings.
  - Integers and BigDecimals retain exact decimal digits. Ratios become doubles.
  - `nil` becomes `null`. Booleans remain booleans.
- `options` — Map or `nil`, default `{}`:
  - `:pretty` — Boolean, default `false`. Enables indented output.

**Returns:** A string containing JSON. Object order follows map iteration. Set order is unspecified.

String keys remain unchanged. Keyword keys use `name` or `namespace/name`. Other keys use `str`. NaN, infinities, invalid Unicode, and unsupported values cause errors.

### `to-json-bytes`

Encodes a Jolt value as UTF-8 JSON bytes.

```clojure
(jj/to-json-bytes value)
(jj/to-json-bytes value options)
```

**Arguments**

- `value` — A Jolt value, with the same accepted types and conversions as `to-json`.
- `options` — Map or `nil`, default `{}`:
  - `:pretty` — Boolean, default `false`. Enables indented output.

**Returns:** A UTF-8 byte array containing JSON, without a BOM or trailing NUL.

### Errors

Errors use `ex-info` with `:type :jjson/error`. Additional fields include `:operation`, `:code`, or `:reason`, where applicable.

Syntax and malformed UTF-8 errors include `:byte-offset` (zero-based), `:line` (one-based), and `:column` (one-based UTF-8 bytes). CRLF counts as one newline.

Duplicate-key errors include `:reason :duplicate-key`, `:key`, and `:transformed?`.

## Build from source

Dependencies: Linux or macOS, Git, Bash, Jolt **v0.8.27**, a C11 compiler, `ar`, and matching Chez **10.4.1** headers. yyjson is vendored.

```sh
git clone https://github.com/antlobach/jjson.git
cd jjson
jolt native
```

Outputs: `native/libjjson.dylib` or `native/libjjson.so`, and `native/libjjson.a`.

Local dependency:

```clojure
{:deps {io.github.antlobach/jjson
        {:local/root "/absolute/path/to/jjson"}}}
```

| Override | Purpose |
|---|---|
| `CC` | Compiler executable, default `cc` |
| `AR` | Archiver executable, default `ar` |
| `JOLT_CHEZ_CSV` | Complete Chez runtime directory |
| `CHEZ_INCLUDE` | Header directory, overriding `JOLT_CHEZ_CSV` |

```sh
CC=/path/to/clang AR=/path/to/ar \
CHEZ_INCLUDE=/path/to/chez/lib/csv10.4.1/ta6le \
jolt native
```

Chez target directories: `ta6le` for Linux x86-64, `ta6osx` for Intel macOS, and `tarm64osx` for Apple Silicon. See the [CI toolchain setup](.github/workflows/ci.yml).

## Checks

```sh
jolt test/smoke.clj
jolt -M:test-reference test/differential.clj
jolt test/stress.clj
bash test/run-conformance.sh
bash test/run-native-safety.sh
bash test/run-proofs.sh
```

Coverage includes JSONTestSuite, 1,000 reference comparisons, concurrent GC, allocation failures, and zero-live-allocation checks. CI runs ASan, UBSan, Clang analysis, and Linux LeakSanitizer with an intentional-leak control.

Native safety checks require a complete matching Chez installation. Z3 checks five [buffer-arithmetic models](test/bounds.smt2). Codecov measures the native bridge files `native/jjson.c` and `native/jjson_chez.c`.

[Benchmarks](BENCHMARKS.md) · [Behavior contract](spec/jjson.allium) · [CI](.github/workflows/ci.yml)

## For AI agents

- Require `[jjson :as jj]`.
- Keep unbounded external keys as strings. Use both exact-number options when all numbers must retain precision.
- Classify errors through `ex-data`.
- Preserve collect-safe calls, buffer pins, and exception cleanup. Deactivated C calls must not allocate Scheme objects or call Scheme.
- Native changes require a rebuild and process restart.

## License

[MIT](LICENSE)
