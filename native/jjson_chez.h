/* Internal Chez ABI. Every Scheme object argument must remain locked for the
 * complete call. Functions never allocate Scheme objects or call into Scheme.
 * Every non-NULL decode/encode handle must be freed, including failed handles. */
#ifndef JJSON_CHEZ_H
#define JJSON_CHEZ_H

#include "scheme.h"
#include <stddef.h>

void *jjson_decode_string(ptr string, int raw_numbers);
void *jjson_decode_bytes(ptr bytes, int raw_numbers);
const char *jjson_decode_error(void *handle);
size_t jjson_decode_error_position(void *handle);
int jjson_decode_error_code(void *handle);
size_t jjson_decode_records(void *handle);
size_t jjson_decode_chars(void *handle);
size_t jjson_decode_keys(void *handle);
int jjson_decode_fill(void *handle, ptr chars, ptr records);
void jjson_decode_free(void *handle);

/* flags: bit 0 pretty output, bit 1 byte output (skip character counting). */
void *jjson_encode(ptr records, size_t record_count, ptr chars,
                   size_t char_count, int flags);
const char *jjson_encode_error(void *handle);
size_t jjson_encode_chars(void *handle);
size_t jjson_encode_bytes(void *handle);
int jjson_encode_fill(void *handle, ptr output);
int jjson_encode_fill_bytes(void *handle, ptr output);
void jjson_encode_free(void *handle);

#endif
