#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
actual="$(z3 test/bounds.smt2)"
expected="$(printf 'unsat\nunsat\nunsat\nunsat\nunsat')"
if [ "$actual" != "$expected" ]; then
  printf 'FAIL: expected five UNSAT results, got:\n%s\n' "$actual" >&2
  exit 1
fi
printf 'PASS: five 64-bit buffer arithmetic obligations proved UNSAT by Z3\n'
