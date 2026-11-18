#!/usr/bin/env bash
# Jolt runs this task once when the declared native library is absent.
set -euo pipefail
cd "$(dirname "$0")/.."

case "$(uname -s):$(uname -m)" in
  Darwin:x86_64) platform=x86_64-macos; shared=libjjson.dylib ;;
  Darwin:arm64|Darwin:aarch64) platform=aarch64-macos; shared=libjjson.dylib ;;
  Linux:x86_64) platform=x86_64-linux; shared=libjjson.so ;;
  *)
    printf 'jjson: no prebuilt library for %s/%s\n' "$(uname -s)" "$(uname -m)" >&2
    printf 'Supported: macOS arm64/x86_64 and Linux x86_64.\n' >&2
    exit 1
    ;;
esac

if [ -s "native/$shared" ] && [ -s native/libjjson.a ]; then
  exit 0
fi
IFS= read -r version < native/VERSION
if [[ ! "$version" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]]; then
  printf 'jjson: invalid native release version\n' >&2
  exit 1
fi
asset="jjson-$version-$platform.tar.gz"
base="https://github.com/antlobach/jjson/releases/download/$version"
work="$(mktemp -d native/.jjson-install.XXXXXX)"
trap 'rm -rf "$work"' EXIT

printf 'jjson: downloading %s\n' "$asset" >&2
curl --fail --silent --show-error --location --retry 2 --connect-timeout 15 --max-time 120 \
  "$base/$asset" -o "$work/$asset"
curl --fail --silent --show-error --location --retry 2 --connect-timeout 15 --max-time 120 \
  "$base/$asset.sha256" -o "$work/checksum"
read -r expected _ < "$work/checksum"
if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "$work/$asset")"
else
  actual="$(shasum -a 256 "$work/$asset")"
fi
if [[ ! "$expected" =~ ^[a-f0-9]{64}$ ]] || [ "${actual%% *}" != "$expected" ]; then
  printf 'jjson: native archive checksum mismatch\n' >&2
  exit 1
fi

tar -xzf "$work/$asset" -C "$work" "$shared" libjjson.a
for file in "$shared" libjjson.a; do
  if [ ! -s "$work/$file" ] || [ -L "$work/$file" ]; then
    printf 'jjson: invalid native archive member: %s\n' "$file" >&2
    exit 1
  fi
done
chmod 755 "$work/$shared"
chmod 644 "$work/libjjson.a"
mv "$work/libjjson.a" native/libjjson.a
mv "$work/$shared" "native/$shared"
printf 'jjson: installed %s\n' "$platform" >&2
