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

/* Encode a tape into JSON. Wire tags: 0 null, 1 false, 2 true, 3 signed
 * integer, 4 unsigned integer, 5 double, 6 string, 7 array, 8 object,
 * 9 raw JSON number. pretty selects YYJSON_WRITE_PRETTY. Returns a
 * NUL-terminated buffer of *output_length bytes, or NULL on failure with
 * *message set. */
char *jjson_write_tape(const uint8_t *ops, size_t ops_len,
                       const int64_t *sints, size_t sint_count,
                       const uint64_t *uints, size_t uint_count,
                       const double *reals, size_t real_count,
                       const char *strings, size_t strings_len,
                       int pretty, size_t *output_length, const char **message);

#ifdef __cplusplus
}
#endif

#endif /* JJSON_H */