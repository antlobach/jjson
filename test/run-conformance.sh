#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

revision=1ef36fa01286573e846ac449e8683f8833c5b26a
cache=".cache/JSONTestSuite-$revision"
jolt="${JOLT:-jolt}"

# Fetch only the pinned parsing corpus. Git verifies fetched object hashes;
# sparse checkout avoids downloading the upstream parser implementations.
if [ ! -d "$cache/.git" ]; then
  git init -q "$cache"
  git -C "$cache" remote add origin https://github.com/nst/JSONTestSuite.git
fi
git -C "$cache" sparse-checkout set test_parsing
if ! git -C "$cache" cat-file -e "$revision^{commit}" 2>/dev/null; then
  git -C "$cache" -c protocol.version=2 fetch --depth=1 --filter=blob:none origin "$revision"
fi
git -C "$cache" checkout -q --detach "$revision"
printf 'JSONTestSuite revision: %s\n' "$revision"
exec "$jolt" test/conformance.clj "$cache/test_parsing"
