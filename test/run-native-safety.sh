#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
chez="${JOLT_CHEZ_CSV:-${CHEZ_INCLUDE:-$HOME/.local/lib/csv10.4.1/ta6osx}}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cc="${CC:-cc}"
flags=(-std=c11 -O1 -g -fsanitize=address,undefined -fno-sanitize-recover=all
       -Wall -Wextra -Werror -Wno-unused-parameter -I native -I "$chez")

"$cc" "${flags[@]}" '-DJJSON_READ_FLAGS=(YYJSON_READ_INSITU|YYJSON_READ_ALLOW_INVALID_UNICODE)' \
  test/native_test.c native/jjson.c native/yyjson.c -o "$work/native-test"
ASAN_OPTIONS="allocator_may_return_null=1${ASAN_OPTIONS:+:$ASAN_OPTIONS}" "$work/native-test"

for source in jjson jjson_chez yyjson; do
  "$cc" "${flags[@]}" -include test/native_alloc.h -c "native/$source.c" -o "$work/$source.o"
done
libs=("$chez/libkernel.a" "$chez/libz.a" "$chez/liblz4.a" -lm -lpthread)
case "$(uname -s)" in
  Darwin) libs+=(-liconv) ;;
  Linux) libs+=(-ldl) ;;
esac
"$cc" "${flags[@]}" -I test test/native_chez_test.c \
  "$work/jjson.o" "$work/jjson_chez.o" "$work/yyjson.o" \
  "${libs[@]}" -o "$work/chez-test"
ASAN_OPTIONS="allocator_may_return_null=1${ASAN_OPTIONS:+:$ASAN_OPTIONS}" "$work/chez-test" "$chez"
