#!/usr/bin/env bash
set -euo pipefail

# Builds the platform shared library (native/libjjson.dylib / .so) and static
# archive (native/libjjson.a). The collect-safe decoder reads and writes
# locked Chez objects through the Jolt-matched scheme.h; set CHEZ_INCLUDE when
# it is not in the persistent Jolt installation's default x86-64 macOS location.

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

objects="$(mktemp -d)"
trap 'rm -rf "$objects"' EXIT

chez_include="${CHEZ_INCLUDE:-$HOME/.local/lib/csv10.4.1/ta6osx}"
if [ ! -f "$chez_include/scheme.h" ]; then
  printf 'scheme.h not found under CHEZ_INCLUDE=%s\n' "$chez_include" >&2
  exit 1
fi

cc -std=c11 -O3 -DNDEBUG -fPIC -fvisibility=hidden \
   -Wall -Wextra -Werror -Wno-unused-parameter \
   -I native -c native/jjson.c -o "$objects/jjson.o"
cc -std=c11 -O3 -DNDEBUG -fPIC -fvisibility=hidden \
   -Wall -Wextra -Werror -Wno-unused-parameter \
   -I native -c native/yyjson.c -o "$objects/yyjson.o"
cc -std=c11 -O3 -DNDEBUG -fPIC -fvisibility=hidden \
   -Wall -Wextra -Werror -Wno-unused-parameter \
   -I native -I "$chez_include" -c native/jjson_chez.c -o "$objects/jjson_chez.o"

# Only the declared jjson_* API is exported dynamically; yyjson's own surface
# stays local to the library (the static archive still carries the objects
# JJSON needs, so :static linking resolves everything itself).
if [ "$(uname -s)" = "Darwin" ]; then
  printf '_jjson_*\n' > "$objects/exports.txt"
  cc -dynamiclib -undefined dynamic_lookup -Wl,-all_load \
     "$objects/jjson.o" "$objects/jjson_chez.o" "$objects/yyjson.o" \
     -Wl,-exported_symbols_list,"$objects/exports.txt" \
     -o "native/$lib"
else
  printf '{\n  global: jjson_*;\n  local: *;\n};\n' > "$objects/version.map"
  cc -shared -Wl,-whole-archive \
     "$objects/jjson.o" "$objects/jjson_chez.o" "$objects/yyjson.o" \
     -Wl,--version-script="$objects/version.map" \
     -o "native/$lib"
fi

ar rcs native/libjjson.a \
   "$objects/jjson.o" "$objects/jjson_chez.o" "$objects/yyjson.o"

echo "built native/$lib and native/libjjson.a"