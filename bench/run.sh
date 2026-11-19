#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JOLT="${JOLT:-$HOME/.local/bin/jolt}"
CLOJURE="${CLOJURE:-clojure}"
DATA="$ROOT/bench/data"
RESULTS="$ROOT/bench/results"
BENCH_EXE="${BENCH_EXE:-/tmp/jjson-bench}"
LOCK="$RESULTS/.benchmark-lock"
PROGRESS="$RESULTS/progress.edn"
CASE_COUNT=8
# This documented equivalent of -Srepro also works with Jolt's lazy build loader.
export JOLT_NO_USER_DEPS=1
export JOLT_TRACE=0
export JOLT_AOT_ASYNC=0
JOLT_BENCH_VERSION="$("$JOLT" --version)"
export JOLT_BENCH_VERSION="${JOLT_BENCH_VERSION#jolt }"

mkdir -p "$RESULTS"
if ! mkdir "$LOCK" 2>/dev/null; then
  printf 'another benchmark run holds %s\n' "$LOCK" >&2
  exit 1
fi
cleanup() {
  local code=$?
  if (( code != 0 )); then
    printf '{:status :failed, :exit %d}\n' "$code" >> "$PROGRESS"
  fi
  rmdir "$LOCK"
}
trap cleanup EXIT

verify_fixture() {
  local name="$1" expected_bytes="$2" expected_sha256="$3"
  local path="$DATA/$name" hash_line actual_sha256 actual_bytes
  hash_line="$(shasum -a 256 "$path")"
  actual_sha256="${hash_line%% *}"
  actual_bytes="$(wc -c < "$path")"
  actual_bytes="${actual_bytes//[[:space:]]/}"
  if [[ "$actual_bytes" != "$expected_bytes" || "$actual_sha256" != "$expected_sha256" ]]; then
    printf 'corpus mismatch: %s expected %s bytes/%s, got %s bytes/%s\n' \
      "$name" "$expected_bytes" "$expected_sha256" "$actual_bytes" "$actual_sha256" >&2
    exit 1
  fi
}

verify_fixture apache_builds.json 127275 f8e3422ac7d3c3550674afcb37e979e4e9bbeccffdb66933423495d55b6f5c74
verify_fixture canada.json 2251051 f83b3b354030d5dd58740c68ac4fecef64cb730a0d12a90362a7f23077f50d78
verify_fixture citm_catalog.json 1727204 a73e7a883f6ea8de113dff59702975e60119b4b58d451d518a929f31c92e2059
verify_fixture github_events.json 65132 c9eebb2cf2d46649059e9d48700919bacb3e8e0fb58452065a1a9de7778fd22e
verify_fixture gsoc-2018.json 3327831 72f1ef4898d88049da856c2ab8f4ec3e2c968ce209b2bbfd16cef842eb2e185f
verify_fixture instruments.json 220346 f3069235d4e2695d36c0c7735a435a7abb279fc4d64bbcf4ed9f888b8da1fdb9
verify_fixture marine_ik.json 2983466 61590a397542ae274ccd8a47ba9f64e7c9216a223a5e7e702dea5b2375411d62
verify_fixture mesh.json 723597 45bc8bf429340a874a7af8ea7056d60497402f80f55dba1e6ecc4ca8f1e46aff
verify_fixture mesh.pretty.json 1577353 b08b8b1881b131f82f8a6b8f8b0226235488154d7363cc8302153e724776ae8e
verify_fixture numbers.json 150124 82e9ddfe00963110ed8a0704e7df4d1ad1af9c0f336d1b24431ebc63cf430a2b
verify_fixture random.json 510476 61a3544f2bc987b7378c66a9025b1f23eb5456d4f0443595c06d6fc20f3b0a68
verify_fixture twitter.json 631515 549fce17ccd0ecc9605a12ea9adfbf3c92c7cce4fd6305e863ca710a4fabada5
verify_fixture twitterescaped.json 562408 2a288b5af4691c55b6f40fa534225b3e08b8d8b7f7ca4ed29bc5c7c81566ed4a
verify_fixture update-center.json 533178 e7b6a037bfdb5abdf122fa3445ddfacea78522a23df31c4799884c4ebf4c01fa

rm -f "$RESULTS"/*.edn "$RESULTS"/.*-case.edn "$BENCH_EXE"
printf '{:status :running, :phase :build, :completed 0, :percentage 0}\n' > "$PROGRESS"

printf 'rebuilding native codec and release-mode Jolt benchmark executable\n'
(
  cd "$ROOT"
  "$JOLT" native
  "$JOLT" -A:bench-jolt build -m jjson.jolt-bench --closed-world -o "$BENCH_EXE"
)

printf '{:status :running, :phase :validate-jolt, :completed 0, :percentage 0}\n' >> "$PROGRESS"
"$BENCH_EXE" --validate "$DATA"
printf '{:status :running, :phase :validate-jvm, :completed 0, :percentage 0}\n' >> "$PROGRESS"
(
  cd "$ROOT"
  "$CLOJURE" -Srepro -J-Dclojure.compiler.direct-linking=true -M:bench-jvm -m jjson.jvm-bench --validate "$DATA"
)

run_jolt_case() {
  local library="$1" operation="$2" output="$3" case_index="$4"
  local command="$BENCH_EXE --case $library $operation"
  printf 'running fresh Jolt process: %s %s\n' "$library" "$operation"
  "$BENCH_EXE" --case "$library" "$operation" "$output" "$DATA" "$command" \
    "$PROGRESS" "$case_index" "$CASE_COUNT"
}

run_jvm_case() {
  local library="$1" operation="$2" output="$3" case_index="$4"
  local command="$CLOJURE -Srepro -J-Dclojure.compiler.direct-linking=true -M:bench-jvm -m jjson.jvm-bench --case $library $operation"
  printf 'running fresh JVM process: %s %s\n' "$library" "$operation"
  (
    cd "$ROOT"
    "$CLOJURE" -Srepro -J-Dclojure.compiler.direct-linking=true -M:bench-jvm -m jjson.jvm-bench \
      --case "$library" "$operation" "$output" "$DATA" "$command" \
      "$PROGRESS" "$case_index" "$CASE_COUNT"
  )
}

run_jolt_case jjson decode "$RESULTS/.jjson-decode-case.edn" 1
run_jvm_case jsonista decode "$RESULTS/.jsonista-decode-case.edn" 2
run_jolt_case data-json decode "$RESULTS/.data-json-decode-case.edn" 3
run_jvm_case data-json decode "$RESULTS/.jvm-data-json-decode-case.edn" 4
run_jolt_case jjson encode "$RESULTS/.jjson-encode-case.edn" 5
run_jvm_case jsonista encode "$RESULTS/.jsonista-encode-case.edn" 6
run_jolt_case data-json encode "$RESULTS/.data-json-encode-case.edn" 7
run_jvm_case data-json encode "$RESULTS/.jvm-data-json-encode-case.edn" 8

"$BENCH_EXE" --combine "$RESULTS/jolt.edn" \
  "$RESULTS/.jjson-decode-case.edn" "$RESULTS/.data-json-decode-case.edn" \
  "$RESULTS/.jjson-encode-case.edn" "$RESULTS/.data-json-encode-case.edn"
(
  cd "$ROOT"
  "$CLOJURE" -Srepro -J-Dclojure.compiler.direct-linking=true -M:bench-jvm -m jjson.jvm-bench --combine \
    "$RESULTS/jsonista-jvm.edn" \
    "$RESULTS/.jsonista-decode-case.edn" "$RESULTS/.jsonista-encode-case.edn" \
    "$RESULTS/.jvm-data-json-decode-case.edn" "$RESULTS/.jvm-data-json-encode-case.edn"
)

rm -f "$RESULTS"/.*-case.edn
printf '{:status :complete, :phase :done, :percentage 100}\n' >> "$PROGRESS"
printf 'wrote %s and %s\n' "$RESULTS/jolt.edn" "$RESULTS/jsonista-jvm.edn"
