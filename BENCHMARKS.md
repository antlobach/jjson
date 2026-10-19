# Benchmarks

These results use the JSON documents from `boostorg/json` tag
`boost-1.83.0`, directory `bench/data`. Every fixture's byte count and SHA-256
digest is checked before the benchmark starts.

## Method

- One library and operation per fresh process.
- Processes run strictly serially; Jolt and the JVM never benchmark
  concurrently.
- No CPU-idle gate.
- Jolt runs from a release-built, closed-world executable rather than the
  development runner.
- Every decoder fully materializes its result.
- Encode input values are materialized before the clock starts.
- Correctness and round-trip checks run in separate processes before timing.
- A process warms up for at least 3 seconds, calibrates a whole-corpus batch to
  at least 1 second, then records five timed samples.
- Reported values are the median of those five samples. Raw elapsed
  nanoseconds, calibrated iteration counts, checksums, commands, runtime
  details, and p25/p75 values are retained in `bench/results/`.

Decode uses the same 13-document, 14,828,548-byte corpus for all three
libraries. `twitterescaped.json` is excluded because `clojure.data.json` 2.5.2
on Jolt rejects its valid UTF-16 surrogate-pair escape. Encode uses all 14
documents and 15,390,956 source bytes.

Host:

- Intel Core i5-7360U, 2 physical cores / 4 logical CPUs, 2.30 GHz
- macOS 13.7.8, x86-64
- Jolt 0.8.23, release-built executable
- Eclipse Adoptium OpenJDK 26.0.2
- Jsonista 1.0.1 / Jackson 2.22.2
- clojure.data.json 2.5.2

## Results

### Current codec

Measured on the same host with the same method, one fresh process per case,
for the current collect-safe decoder and record-based encoder. Throughput in
MiB/s; encode throughput is normalized to source-document bytes.

| Runtime | Library | Operation | Documents | Batch iterations | p25 | Median | p75 |
|---|---|---|---:|---:|---:|---:|---:|
| Jolt | jjson | decode | 13 | 10 | 127.408 | 134.632 | 134.893 |
| Jolt | data.json | decode | 13 | 1 | 1.243 | 1.267 | 1.272 |
| Jolt | jjson | encode | 14 | 7 | 96.918 | 97.429 | 97.445 |
| Jolt | data.json | encode | 14 | 1 | 5.192 | 5.205 | 5.207 |
| JVM | Jsonista | encode | 14 | 14 | 198.112 | 198.271 | 198.285 |

Median ratios:

- Decode: jjson is 106.28× data.json.
- Encode: jjson is 18.72× data.json; Jsonista is 2.04× jjson.

Jsonista executes on OpenJDK, not Jolt; that row is a cross-runtime
comparison. Raw results:
[`bench/results/jolt-current.edn`](bench/results/jolt-current.edn) and
[`bench/results/jsonista-jvm-encode-current.edn`](bench/results/jsonista-jvm-encode-current.edn).

### Earlier full run

The table below is the complete `bench/run.sh` run from before the
collect-safe codec; its jjson rows describe the previous implementation.

Throughput in MiB/s. Encode throughput is normalized to source-document bytes,
giving every library the same workload denominator.

| Runtime | Library | Operation | Documents | Batch iterations | p25 | Median | p75 |
|---|---|---|---:|---:|---:|---:|---:|
| Jolt | jjson | decode | 13 | 8 | 108.234 | 108.403 | 111.216 |
| Jolt | data.json | decode | 13 | 1 | 0.992 | 1.125 | 1.185 |
| JVM | Jsonista | decode | 13 | 9 | 116.120 | 117.241 | 117.347 |
| Jolt | jjson | encode | 14 | 2 | 15.728 | 16.018 | 16.343 |
| Jolt | data.json | encode | 14 | 1 | 3.321 | 3.682 | 3.727 |
| JVM | Jsonista | encode | 14 | 12 | 140.843 | 144.068 | 146.849 |

On this host and corpus, the observed median ratios are:

- Decode: jjson is 96.36× data.json; Jsonista is 1.08× jjson.
- Encode: jjson is 4.35× data.json; Jsonista is 8.99× jjson.

Jsonista executes on OpenJDK, not Jolt. Those rows are a cross-runtime
comparison, not evidence about library performance under one runtime.

The complete serial command took 221.29 seconds, including the release build,
correctness validation, process startup, untimed warmup, calibration, and
timed samples. Only the timed sample intervals contribute to throughput.

## Run

```sh
./bench/run.sh
```

The append-only progress stream is available immediately at
`bench/results/progress.edn`:

```sh
tail -f bench/results/progress.edn
```

Each event identifies the build or validation phase, or the exact case,
library, operation, sample, and completed-sample count. Progress writes occur
outside timed intervals.

Raw results:

- [`bench/results/jolt.edn`](bench/results/jolt.edn)
- [`bench/results/jsonista-jvm.edn`](bench/results/jsonista-jvm.edn)

Corpus source:
[Boost.JSON 1.83 benchmark documentation](https://www.boost.org/doc/libs/1_83_0/libs/json/doc/html/json/benchmarks.html).
