# Benchmarks

Throughput in MiB/s, normalized to source-document bytes for both operations.

| Runtime / library | Decode median | Decode p25–p75 | Encode median | Encode p25–p75 |
|---|---:|---:|---:|---:|
| Jolt / jjson | 55.60 | 47.66–58.11 | 62.65 | 61.79–67.37 |
| Jolt / data.json | 1.26 | 1.21–1.32 | 3.58 | 3.25–3.72 |
| JVM / Jsonista | 94.51 | 76.61–100.61 | 160.74 | 157.59–164.27 |
| JVM / data.json | 27.23 | 18.75–29.21 | 53.18 | 51.10–54.71 |

## Method

- All 14 documents from `boostorg/json` tag `boost-1.83.0`: 15,390,956 bytes. The runner checks fixture sizes, SHA-256 digests, and decoder agreement. Each encoder's output is parsed by the other decoder on its runtime.
- One library and operation per fresh process. Cases run serially, alternating runtimes. Each process has at least 10 seconds of warmup, 2 seconds of batch calibration, and seven timed samples.
- Decoders produce persistent maps and vectors with string keys. Encoders receive values prepared outside timing. Timed calls include allocation and garbage collection.
- Compact string APIs use default numeric modes. The data.json writers disable Unicode, slash, and JavaScript-separator escaping. Exact-number modes, byte APIs, and pretty output are not measured.
- Jolt uses a release-built executable with `--closed-world`. Runtime Scheme compilation requires core retention. User dependency configuration is disabled. The JVM enables direct linking.

## Environment

Intel Core i5-7360U, 2 cores / 4 threads, 2.30 GHz. macOS 13.7.8, x86-64.

Jolt compiler 0.8.27. OpenJDK 26.0.2+10 and Clojure 1.12.4. yyjson 0.13.0, Jsonista 1.0.1, Jackson 2.22.2, and data.json 2.5.2.

## Limits

Several cases have wide p25–p75 ranges. These are single-host measurements, not performance guarantees. Cross-runtime results do not isolate library performance. Case order is fixed, without CPU-idle or temperature controls. Raw samples and Jolt tracing/AOT settings are unavailable for this table.

## Run

```sh
./bench/run.sh
```

Override `JOLT` and `CLOJURE` with executable paths if required. Results and progress are written to `bench/results/`. The runner records compiler provenance and sets `JOLT_TRACE=0` and `JOLT_AOT_ASYNC=0`.
