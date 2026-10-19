/* Portable yyjson support for JJSON.
 *
 * Decode validation and the value-record JSON writer stay here so the
 * standalone native test can exercise them without a Chez runtime. The
 * collect-safe codec in jjson_chez.c consumes validated yyjson documents and
 * passes locked Chez buffers to jjson_write_records.
 */

#include "jjson.h"
#include "jjson_internal.h"

#include <inttypes.h>
#include <limits.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef JJSON_READ_FLAGS
#define JJSON_READ_FLAGS YYJSON_READ_INSITU
#endif

#define JJSON_MAX_DEPTH 1000

#if defined(_WIN32)
#define JJSON_EXPORT __declspec(dllexport)
#else
#define JJSON_EXPORT __attribute__((visibility("default")))
#endif

enum {
    JJSON_NULL = 0,
    JJSON_FALSE = 1,
    JJSON_TRUE = 2,
    JJSON_SINT = 3,
    JJSON_UINT = 4,
    JJSON_REAL = 5,
    JJSON_STRING = 6,
    JJSON_ARRAY = 7,
    JJSON_OBJECT = 8,
    JJSON_RAW_NUMBER = 9
};

#define JJSON_RECORD_BYTES 16

typedef struct {
    const uint8_t *records;
    size_t record_count;
    size_t record;
    const uint32_t *chars;
    size_t char_count;
    size_t char_index;
    unsigned char_shift;
    char *pool; /* UTF-8 bytes of every string; 4 bytes per character max */
    size_t pool_used;
    const char *error;
} RecordReader;


static void set_error(const char **message, size_t *position, int *code,
                      const char *value, size_t at, int error_code) {
    if (message) *message = value;
    if (position) *position = at;
    if (code) *code = error_code;
}


/* Strict RFC 3629 acceptance. Rejects stray continuation bytes, truncation,
 * overlong forms, UTF-16 surrogate code points, and values above U+10FFFF. */
static int utf8_valid(const char *bytes, size_t length) {
    size_t i = 0;
    while (i < length) {
        unsigned char lead;
        size_t remaining;
        /* Skip ASCII eight bytes at a time; any high bit falls through. */
        while (length - i >= 8) {
            uint64_t word;
            memcpy(&word, bytes + i, sizeof(word));
            if (word & UINT64_C(0x8080808080808080)) break;
            i += 8;
        }
        if (i >= length) break;
        lead = (unsigned char)bytes[i];
        remaining = length - i;
        if (lead < 0x80) {
            i++;
            continue;
        }
        if (lead < 0xC2) return 0; /* stray continuation, C0/C1 overlong */
        if (lead < 0xE0) {
            if (remaining < 2) return 0;
            if ((((unsigned char)bytes[i + 1]) & 0xC0) != 0x80) return 0;
            i += 2;
            continue;
        }
        if (lead < 0xF0) {
            unsigned char c1;
            if (remaining < 3) return 0;
            c1 = (unsigned char)bytes[i + 1];
            if ((c1 & 0xC0) != 0x80) return 0;
            if ((((unsigned char)bytes[i + 2]) & 0xC0) != 0x80) return 0;
            if (lead == 0xE0 && c1 < 0xA0) return 0;   /* overlong */
            if (lead == 0xED && c1 >= 0xA0) return 0;  /* surrogate */
            i += 3;
            continue;
        }
        if (lead < 0xF5) {
            unsigned char c1;
            if (remaining < 4) return 0;
            c1 = (unsigned char)bytes[i + 1];
            if ((c1 & 0xC0) != 0x80) return 0;
            if ((((unsigned char)bytes[i + 2]) & 0xC0) != 0x80) return 0;
            if ((((unsigned char)bytes[i + 3]) & 0xC0) != 0x80) return 0;
            if (lead == 0xF0 && c1 < 0x90) return 0;   /* overlong */
            if (lead == 0xF4 && c1 >= 0x90) return 0;  /* above U+10FFFF */
            i += 4;
            continue;
        }
        return 0; /* F5..FF: above U+10FFFF or not valid UTF-8 at all */
    }
    return 1;
}

static int validate_value(const yyjson_val *value, size_t depth,
                          const char **message) {
    if (!value) {
        *message = "Unsupported JSON value";
        return 0;
    }
    if (yyjson_is_null(value) || yyjson_is_bool(value) ||
        yyjson_is_sint(value) || yyjson_is_uint(value) ||
        yyjson_is_real(value)) {
        return 1;
    }
    if (yyjson_is_str(value)) {
        if (!utf8_valid(yyjson_get_str(value), yyjson_get_len(value))) {
            *message = "JSON contains invalid UTF-8";
            return 0;
        }
        return 1;
    }
    if (depth >= JJSON_MAX_DEPTH) {
        *message = "JSON nesting exceeds the maximum depth of 1000";
        return 0;
    }
    if (yyjson_is_arr(value)) {
        yyjson_arr_iter iterator;
        yyjson_val *item;
        yyjson_arr_iter_init(value, &iterator);
        while ((item = yyjson_arr_iter_next(&iterator))) {
            if (!validate_value(item, depth + 1, message)) return 0;
        }
        return 1;
    }
    if (yyjson_is_obj(value)) {
        yyjson_obj_iter iterator;
        yyjson_val *key;
        yyjson_obj_iter_init(value, &iterator);
        while ((key = yyjson_obj_iter_next(&iterator))) {
            if (!utf8_valid(yyjson_get_str(key), yyjson_get_len(key))) {
                *message = "JSON contains invalid UTF-8";
                return 0;
            }
            if (!validate_value(yyjson_obj_iter_get_val(key), depth + 1,
                                message)) {
                return 0;
            }
        }
        return 1;
    }
    *message = "Unsupported JSON value";
    return 0;
}

JJSON_EXPORT void *jjson_alloc(size_t size) {
    return malloc(size < 1 ? 1 : size);
}

JJSON_EXPORT void *jjson_input_alloc(size_t length) {
    size_t bytes;
    void *buffer;
    if (length > SIZE_MAX - YYJSON_PADDING_SIZE) return NULL;
    bytes = length + YYJSON_PADDING_SIZE;
    buffer = malloc(bytes);
    if (!buffer) return NULL;
    memset((char *)buffer + length, 0, YYJSON_PADDING_SIZE);
    return buffer;
}

JJSON_EXPORT void jjson_free(void *pointer) {
    free(pointer);
}

yyjson_doc *jjson_internal_read_doc(char *data, size_t length,
                                    const char **message, size_t *position,
                                    int *code) {
    yyjson_read_err error;
    const char *validation_error = NULL;
    yyjson_doc *document;
    if (!data) {
        set_error(message, position, code, "JSON input is null", 0, -1);
        return NULL;
    }
    if (length && !utf8_valid(data, length)) {
        set_error(message, position, code,
                  "JSON input contains invalid UTF-8", 0, -2);
        return NULL;
    }
    document = yyjson_read_opts(data, length, JJSON_READ_FLAGS, NULL, &error);
    if (!document) {
        set_error(message, position, code, error.msg, error.pos,
                  (int)error.code);
        return NULL;
    }
    if (!validate_value(yyjson_doc_get_root(document), 0, &validation_error)) {
        yyjson_doc_free(document);
        set_error(message, position, code, validation_error, 0, -2);
        return NULL;
    }
    return document;
}

JJSON_EXPORT int jjson_validate(char *data, size_t length,
                                const char **message, size_t *position,
                                int *code) {
    yyjson_doc *document = jjson_internal_read_doc(
        data, length, message, position, code);
    if (!document) return 0;
    yyjson_doc_free(document);
    return 1;
}

static int next_record(RecordReader *reader, uint8_t *tag, uint64_t *payload) {
    const uint8_t *record;
    if (reader->record >= reader->record_count) {
        reader->error = "Truncated value records";
        return 0;
    }
    record = reader->records + reader->record * JJSON_RECORD_BYTES;
    *tag = record[0];
    memcpy(payload, record + 8, sizeof(*payload));
    reader->record++;
    return 1;
}

/* Encodes the next LENGTH characters as UTF-8 into the pool. Rejects code
 * points that are surrogates or above U+10FFFF. */
static int take_string(RecordReader *reader, uint64_t length,
                       const char **bytes, size_t *byte_length) {
    const uint32_t *chars;
    unsigned shift = reader->char_shift;
    char *start;
    char *out;
    size_t index = 0;
    if (length > reader->char_count - reader->char_index) {
        reader->error = "String characters exceed the character buffer";
        return 0;
    }
    chars = reader->chars + reader->char_index;
    start = reader->pool + reader->pool_used;
    out = start;
    while (index < length) {
        uint32_t codepoint = chars[index] >> shift;
        index++;
        if (codepoint < 0x80) {
            *out++ = (char)codepoint;
        } else if (codepoint < 0x800) {
            *out++ = (char)(0xC0 | (codepoint >> 6));
            *out++ = (char)(0x80 | (codepoint & 0x3F));
        } else if (codepoint < 0x10000) {
            if (codepoint >= 0xD800 && codepoint <= 0xDFFF) {
                reader->error = "JSON string contains a surrogate code point";
                return 0;
            }
            *out++ = (char)(0xE0 | (codepoint >> 12));
            *out++ = (char)(0x80 | ((codepoint >> 6) & 0x3F));
            *out++ = (char)(0x80 | (codepoint & 0x3F));
        } else if (codepoint <= 0x10FFFF) {
            *out++ = (char)(0xF0 | (codepoint >> 18));
            *out++ = (char)(0x80 | ((codepoint >> 12) & 0x3F));
            *out++ = (char)(0x80 | ((codepoint >> 6) & 0x3F));
            *out++ = (char)(0x80 | (codepoint & 0x3F));
        } else {
            reader->error = "JSON string contains an invalid code point";
            return 0;
        }
    }
    reader->char_index += (size_t)length;
    reader->pool_used += (size_t)(out - start);
    *bytes = start;
    *byte_length = (size_t)(out - start);
    return 1;
}

/* Complete JSON number grammar: -?(0|[1-9][0-9]*)(.[0-9]+)?([eE][+-]?[0-9]+)? */
static int valid_number_token(const char *digits, size_t length) {
    size_t i = 0;
    if (length == 0) return 0;
    if (digits[i] == '-') i++;
    if (i >= length || digits[i] < '0' || digits[i] > '9') return 0;
    if (digits[i] == '0') {
        i++;
        if (i < length && digits[i] >= '0' && digits[i] <= '9') return 0;
    } else {
        while (i < length && digits[i] >= '0' && digits[i] <= '9') i++;
    }
    if (i < length && digits[i] == '.') {
        size_t start;
        i++;
        start = i;
        while (i < length && digits[i] >= '0' && digits[i] <= '9') i++;
        if (i == start) return 0;
    }
    if (i < length && (digits[i] == 'e' || digits[i] == 'E')) {
        size_t start;
        i++;
        if (i < length && (digits[i] == '+' || digits[i] == '-')) i++;
        start = i;
        while (i < length && digits[i] >= '0' && digits[i] <= '9') i++;
        if (i == start) return 0;
    }
    return i == length;
}

static yyjson_mut_val *build_value(yyjson_mut_doc *doc, RecordReader *reader,
                                   size_t depth) {
    uint8_t tag;
    uint64_t payload;
    uint64_t index;
    yyjson_mut_val *value;
    if (!next_record(reader, &tag, &payload)) return NULL;
    switch (tag) {
        case JJSON_NULL:
            return yyjson_mut_null(doc);
        case JJSON_FALSE:
            return yyjson_mut_false(doc);
        case JJSON_TRUE:
            return yyjson_mut_true(doc);
        case JJSON_SINT:
            return yyjson_mut_sint(doc, (int64_t)payload);
        case JJSON_UINT:
            return yyjson_mut_uint(doc, payload);
        case JJSON_REAL: {
            double real;
            memcpy(&real, &payload, sizeof(real));
            if (!isfinite(real)) {
                reader->error = "JSON cannot represent NaN or infinity";
                return NULL;
            }
            return yyjson_mut_real(doc, real);
        }
        case JJSON_STRING: {
            const char *string;
            size_t length;
            if (!take_string(reader, payload, &string, &length)) return NULL;
            return yyjson_mut_strn(doc, string, length);
        }
        case JJSON_RAW_NUMBER: {
            const char *digits;
            size_t length;
            if (!take_string(reader, payload, &digits, &length)) return NULL;
            if (!valid_number_token(digits, length)) {
                reader->error = "Invalid raw JSON number";
                return NULL;
            }
            return yyjson_mut_rawn(doc, digits, length);
        }
        case JJSON_ARRAY:
            if (depth >= JJSON_MAX_DEPTH) {
                reader->error =
                    "JSON nesting exceeds the maximum depth of 1000";
                return NULL;
            }
            /* Every element needs at least one record. */
            if (payload > reader->record_count - reader->record) {
                reader->error = "Array length exceeds the value records";
                return NULL;
            }
            value = yyjson_mut_arr(doc);
            if (!value) {
                reader->error = "Unable to allocate JSON array";
                return NULL;
            }
            for (index = 0; index < payload; index++) {
                yyjson_mut_val *item = build_value(doc, reader, depth + 1);
                if (!item || !yyjson_mut_arr_append(value, item)) {
                    if (!reader->error)
                        reader->error = "Unable to append JSON array item";
                    return NULL;
                }
            }
            return value;
        case JJSON_OBJECT:
            if (depth >= JJSON_MAX_DEPTH) {
                reader->error =
                    "JSON nesting exceeds the maximum depth of 1000";
                return NULL;
            }
            /* Every entry needs a key record and a value record. */
            if (payload > (reader->record_count - reader->record) / 2) {
                reader->error = "Object size exceeds the value records";
                return NULL;
            }
            value = yyjson_mut_obj(doc);
            if (!value) {
                reader->error = "Unable to allocate JSON object";
                return NULL;
            }
            for (index = 0; index < payload; index++) {
                uint8_t key_tag;
                uint64_t key_length;
                const char *key_string;
                size_t key_bytes;
                yyjson_mut_val *key;
                yyjson_mut_val *item;
                if (!next_record(reader, &key_tag, &key_length)) return NULL;
                if (key_tag != JJSON_STRING) {
                    reader->error = "JSON object key must be a string";
                    return NULL;
                }
                if (!take_string(reader, key_length, &key_string, &key_bytes)) {
                    return NULL;
                }
                key = yyjson_mut_strn(doc, key_string, key_bytes);
                item = build_value(doc, reader, depth + 1);
                if (!key || !item || !yyjson_mut_obj_add(value, key, item)) {
                    if (!reader->error)
                        reader->error = "Unable to add JSON object entry";
                    return NULL;
                }
            }
            return value;
        default:
            reader->error = "Unknown value record tag";
            return NULL;
    }
}

JJSON_EXPORT char *jjson_write_records(
        const uint8_t *records, size_t record_count,
        const uint32_t *chars, size_t char_count, unsigned char_shift,
        int pretty, size_t *output_length, const char **message) {
    RecordReader reader;
    yyjson_mut_doc *doc;
    yyjson_mut_val *root;
    yyjson_write_err error;
    char *output;
    memset(&reader, 0, sizeof(reader));
    if (!records || record_count == 0) {
        if (message) *message = "JSON value records are empty";
        return NULL;
    }
    if ((char_count && !chars) || char_shift > 24 ||
        char_count > (SIZE_MAX - 1) / 4) {
        if (message) *message = "Invalid JSON character buffer";
        return NULL;
    }
    reader.records = records;
    reader.record_count = record_count;
    reader.chars = chars;
    reader.char_count = char_count;
    reader.char_shift = char_shift;
    reader.pool = (char *)malloc(char_count * 4 + 1);
    if (!reader.pool) {
        if (message) *message = "Unable to allocate JSON string buffer";
        return NULL;
    }
    doc = yyjson_mut_doc_new(NULL);
    if (!doc) {
        free(reader.pool);
        if (message) *message = "Unable to allocate JSON document";
        return NULL;
    }
    root = build_value(doc, &reader, 0);
    if (!root || reader.record != reader.record_count ||
        reader.char_index != reader.char_count) {
        if (message)
            *message = reader.error ? reader.error
                                    : "JSON value records have trailing data";
        yyjson_mut_doc_free(doc);
        free(reader.pool);
        return NULL;
    }
    yyjson_mut_doc_set_root(doc, root);
    output = yyjson_mut_write_opts(
        doc, pretty ? YYJSON_WRITE_PRETTY : YYJSON_WRITE_NOFLAG,
        NULL, output_length, &error);
    if (!output && message) *message = error.msg;
    yyjson_mut_doc_free(doc);
    free(reader.pool);
    return output;
}