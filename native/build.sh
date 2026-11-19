#!/usr/bin/env bash
set -euo pipefail

# Builds the platform shared library (native/libjjson.dylib / .so) and static
# archive (native/libjjson.a). The collect-safe decoder reads and writes
# locked Chez objects through the Jolt-matched scheme.h. CC and AR select tools.
# CHEZ_INCLUDE overrides headers; JOLT_CHEZ_CSV names a complete Chez directory.

cd "$(dirname "$0")/.."

lib="libjjson.so"
case "$(uname -s)" in
  Darwin) lib="libjjson.dylib" ;;
  Linux)  lib="libjjson.so" ;;
  *)
    echo "Unsupported operating system: $(uname -s)" >&2
    exit 1
    ;;
esac
cc="${CC:-cc}"
ar="${AR:-ar}"
command -v "$cc" >/dev/null || { printf 'Compiler not found: %s (set CC)\n' "$cc" >&2; exit 1; }
command -v "$ar" >/dev/null || { printf 'Archiver not found: %s (set AR)\n' "$ar" >&2; exit 1; }

objects="$(mktemp -d)"
trap 'rm -rf "$objects"' EXIT

chez_include="${CHEZ_INCLUDE:-${JOLT_CHEZ_CSV:-}}"
if [ -z "$chez_include" ]; then
  case "$(uname -s):$(uname -m)" in
    Darwin:x86_64) machine=ta6osx ;;
    Darwin:arm64|Darwin:aarch64) machine=tarm64osx ;;
    Linux:x86_64) machine=ta6le ;;
    Linux:aarch64) machine=tarm64le ;;
    *) printf 'Set CHEZ_INCLUDE for this machine.\n' >&2; exit 1 ;;
  esac
  for prefix in "$HOME/.local" /usr/local /opt/homebrew /usr; do
    candidate="$prefix/lib/csv10.4.1/$machine"
    if [ -f "$candidate/scheme.h" ]; then chez_include="$candidate"; break; fi
  done
fi
if [ ! -f "$chez_include/scheme.h" ]; then
  printf 'Chez 10.4.1 scheme.h not found. Set CHEZ_INCLUDE or JOLT_CHEZ_CSV.\n' >&2
  exit 1
fi
case "$(< "$chez_include/scheme.h")" in
  *'#define VERSION "10.4.1"'*) ;;
  *) printf 'Expected Chez 10.4.1 headers under %s.\n' "$chez_include" >&2; exit 1 ;;
esac

"$cc" -std=c11 -O3 -DNDEBUG -fPIC -fvisibility=hidden \
   -Wall -Wextra -Werror -Wno-unused-parameter \
   -I native -c native/jjson.c -o "$objects/jjson.o"
"$cc" -std=c11 -O3 -DNDEBUG -fPIC -fvisibility=hidden \
   -Wall -Wextra -Werror -Wno-unused-parameter \
   -I native -c native/yyjson.c -o "$objects/yyjson.o"
"$cc" -std=c11 -O3 -DNDEBUG -fPIC -fvisibility=hidden \
   -Wall -Wextra -Werror -Wno-unused-parameter \
   -I native -I "$chez_include" -c native/jjson_chez.c -o "$objects/jjson_chez.o"

# Only the declared jjson_* API is exported dynamically; yyjson's own surface
# stays local to the library (the static archive still carries the objects
# JJSON needs, so :static linking resolves everything itself).
if [ "$(uname -s)" = "Darwin" ]; then
  printf '_jjson_*\n' > "$objects/exports.txt"
  "$cc" -dynamiclib -undefined dynamic_lookup -Wl,-all_load \
     "$objects/jjson.o" "$objects/jjson_chez.o" "$objects/yyjson.o" \
     -Wl,-exported_symbols_list,"$objects/exports.txt" \
     -o "native/$lib"
else
  printf '{\n  global: jjson_*;\n  local: *;\n};\n' > "$objects/version.map"
  "$cc" -shared \
     "$objects/jjson.o" "$objects/jjson_chez.o" "$objects/yyjson.o" \
     -Wl,--version-script="$objects/version.map" \
     -o "native/$lib"
fi

"$ar" rcs native/libjjson.a \
   "$objects/jjson.o" "$objects/jjson_chez.o" "$objects/yyjson.o"

echo "built native/$lib and native/libjjson.a"