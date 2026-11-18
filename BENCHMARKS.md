# Benchmarks

## Latest completed run

Measured on 18 November 2026, from 23:20 to 23:25 UTC. This report contains only the completed eight-case run. It does not include measurements from the subsequent cancelled rerun.

Throughput is in MiB/s. Both decode and encode use the same source-document byte count as the denominator. Encode throughput is not the number of output bytes per second.

| Runtime | Library | Operation | Batch iterations | p25 | Median | p75 |
|---|---|---|---:|---:|---:|---:|
| Jolt | jjson | decode | 12 | 47.66 | 55.60 | 58.11 |
| Jolt | clojure.data.json | decode | 1 | 1.21 | 1.26 | 1.32 |
| JVM | Jsonista | decode | 7 | 76.61 | 94.51 | 100.61 |
| JVM | clojure.data.json | decode | 3 | 18.75 | 27.23 | 29.21 |
| Jolt | jjson | encode | 8 | 61.79 | 62.65 | 67.37 |
| Jolt | clojure.data.json | encode | 1 | 3.25 | 3.58 | 3.72 |
| JVM | Jsonista | encode | 21 | 157.59 | 160.74 | 164.27 |
| JVM | clojure.data.json | encode | 8 | 51.10 | 53.18 | 54.71 |

The p25–p75 ranges are wide in several cases. These medians are observations from one run on this host, not precise performance guarantees. Jsonista runs on the JVM, not Jolt. Cross-runtime differences include the runtime, compiler, collection implementation, allocation, and garbage collector; they do not isolate the JSON library.

## Method

- All cases use all 14 corpus documents: 15,390,956 source bytes. No fixture is excluded.
- The corpus comes from `boostorg/json`, tag `boost-1.83.0`, directory `bench/data`. The runner checks each byte count and SHA-256 digest.
- Each library and operation runs in a fresh process. Cases run serially, alternating Jolt and JVM cases. There is no CPU-idle or temperature gate, and the order is fixed.
- Jolt uses a release-built standalone executable with `--closed-world`. The compiler retains the core because the codec constructs Scheme procedures at runtime; this is not a claim that the complete core was tree-shaken.
- Decoders fully materialize persistent maps and vectors with string keys. There is no lazy or view-only decode path.
- Encoders receive already materialized values. Each runtime gives both of its encoders the same prepared values.
- String input and string output are measured. Output is compact. The data.json writers disable Unicode, JavaScript-separator, and slash escaping to match the other writers.
- Validation runs outside timing. Both decoders must agree on every fixture, including `twitterescaped.json`. Each writer's output is parsed by the other decoder on the same runtime.
- Each process warms up for at least 10 seconds, calibrates a whole-corpus batch to at least 2 seconds, then records seven samples. A slow whole-corpus iteration can exceed these minimum intervals.
- p25 and p75 use linear interpolation between sorted samples. Allocation and garbage collection during the timed calls remain included.
- Jolt ignores user dependency configuration. The JVM uses `-Srepro` and `-Dclojure.compiler.direct-linking=true`.
- Exact-number modes, byte APIs, and pretty output are not measured.

## Host and versions

- Intel Core i5-7360U at 2.30 GHz; 2 physical cores and 4 logical CPUs.
- macOS 13.7.8, x86-64.
- Jolt compiler CLI: **v0.8.27**, invoked from `/tmp/jjson-toolchain/jolt-v0.8.27-x86_64-macos/jolt`.
- Eclipse Adoptium OpenJDK 26.0.2+10; Clojure 1.12.4.
- yyjson 0.13.0; Jsonista 1.0.1; Jackson 2.22.2; clojure.data.json 2.5.2.

The completed run's standalone Jolt image reported `jolt.version` as `dev`. The compiler version above comes from the verified CLI used to build it, not from that embedded property. The run preceded the public namespace rename from `jjson.core` to `jjson`.

The cancelled rerun replaced the local result files before it was stopped. The table preserves the completed run's recorded summary; its full sample files are not retained. Files from older committed runs are not evidence for this table.

## Run

```sh
./bench/run.sh
```

Set `JOLT` and `CLOJURE` to absolute executable paths if they are not at the default locations.

The current runner captures the builder's version directly from `"$JOLT" --version` and records the standalone image's embedded version separately. It also explicitly sets `JOLT_TRACE=0` and `JOLT_AOT_ASYNC=0`; those environment overrides were not recorded in the completed run above.

A successful run writes `bench/results/jolt.edn` and `bench/results/jsonista-jvm.edn`. These contain samples, calibrated iteration counts, checksums, commands, and runtime metadata. `bench/results/progress.edn` records progress outside the timed intervals.

Corpus reference: [Boost.JSON 1.83 benchmark documentation](https://www.boost.org/doc/libs/1_83_0/libs/json/doc/html/json/benchmarks.html).
