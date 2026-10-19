/* Portable JJSON ABI.
 *
 * Decode validation and write-tape operations use caller-owned native memory.
 * The collect-safe Jolt/Chez decoder is declared separately in jjson_chez.c
 * because its scheme-object ABI is intentionally host-specific.
 */

#ifndef JJSON_H
#define JJSON_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif


/* Generic allocation: malloc(max(size, 1)), never returning NULL for a
 * workable request other than allocator failure. */
void *jjson_alloc(size_t size);

/* Input allocation reserving length + yyjson padding bytes; only the padding
 * is zeroed. Returns NULL on overflow or allocator failure. */
void *jjson_input_alloc(size_t length);

/* Release any pointer obtained from this library; NULL is a no-op. */
void jjson_free(void *p);

/* Validate mutable, yyjson-padded UTF-8 input without constructing managed
 * values. Returns nonzero on success. On failure, returns zero and fills
 * message/position/code. data may be modified in place and is not retained. */
int jjson_validate(char *data, size_t length, const char **message,
                   size_t *position, int *code);

/* Encode value records into JSON. RECORDS holds RECORD_COUNT 16-byte records
 * in preorder: byte 0 is the tag, bytes 8-15 a native-endian u64 payload.
 * Tags: 0 null, 1 false, 2 true, 3 signed integer (payload bits), 4 unsigned
 * integer, 5 double (payload bits), 6 string (payload = character count),
 * 7 array (payload = element count), 8 object (payload = entry count; each
 * entry is a tag-6 key record followed by its value), 9 raw JSON number
 * (payload = character count). String characters are read in record order
 * from CHARS: each element shifted right by CHAR_SHIFT is a Unicode scalar
 * value (shift 0 for plain code points). pretty selects YYJSON_WRITE_PRETTY.
 * Returns a NUL-terminated buffer of *output_length bytes, or NULL on failure
 * with *message set. */
char *jjson_write_records(const uint8_t *records, size_t record_count,
                          const uint32_t *chars, size_t char_count,
                          unsigned char_shift, int pretty,
                          size_t *output_length, const char **message);

#ifdef __cplusplus
}
#endif

#endif /* JJSON_H */