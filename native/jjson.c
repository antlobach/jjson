/* Portable yyjson support for JJSON.
 *
 * Decode validation stays here so the standalone native test can exercise it
 * without a Chez runtime. The collect-safe decoder in jjson_chez.c consumes
 * the validated yyjson document and writes value records into locked Chez
 * objects. Encoding retains the compact managed-to-native tape.
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


typedef struct {
    const uint8_t *ops;
    size_t ops_len;
    size_t op;
    const int64_t *sints;
    size_t sint_count;
    size_t sint_index;
    const uint64_t *uints;
    size_t uint_count;
    size_t uint_index;
    const double *reals;
    size_t real_count;
    size_t real_index;
    const char *strings;
    size_t strings_len;
    size_t string_index;
    const char *error;
} TapeReader;


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
        unsigned char lead = (unsigned char)bytes[i];
        size_t remaining = length - i;
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

static int read_u8(TapeReader *reader, uint8_t *value) {
    if (reader->op >= reader->ops_len) {
        reader->error = "Truncated operation tape";
        return 0;
    }
    *value = reader->ops[reader->op++];
    return 1;
}

static int read_u32(TapeReader *reader, uint32_t *value) {
    const uint8_t *p;
    if (reader->ops_len - reader->op < 4) {
        reader->error = "Truncated operation tape";
        return 0;
    }
    p = reader->ops + reader->op;
    reader->op += 4;
    *value = ((uint32_t)p[0]) |
             ((uint32_t)p[1] << 8) |
             ((uint32_t)p[2] << 16) |
             ((uint32_t)p[3] << 24);
    return 1;
}

static int read_string(TapeReader *reader, const char **value, uint32_t *length) {
    if (!read_u32(reader, length)) return 0;
    if ((size_t)*length > reader->strings_len - reader->string_index) {
        reader->error = "Truncated string tape";
        return 0;
    }
    *value = reader->strings + reader->string_index;
    reader->string_index += *length;
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

static yyjson_mut_val *decode_value(yyjson_mut_doc *doc, TapeReader *reader,
                                    size_t depth) {
    uint8_t tag;
    uint32_t count;
    uint32_t index;
    yyjson_mut_val *value;
    if (!read_u8(reader, &tag)) return NULL;
    switch (tag) {
        case JJSON_NULL:
            return yyjson_mut_null(doc);
        case JJSON_FALSE:
            return yyjson_mut_false(doc);
        case JJSON_TRUE:
            return yyjson_mut_true(doc);
        case JJSON_SINT:
            if (reader->sint_index >= reader->sint_count) {
                reader->error = "Truncated signed integer tape";
                return NULL;
            }
            return yyjson_mut_sint(doc, reader->sints[reader->sint_index++]);
        case JJSON_UINT:
            if (reader->uint_index >= reader->uint_count) {
                reader->error = "Truncated unsigned integer tape";
                return NULL;
            }
            return yyjson_mut_uint(doc, reader->uints[reader->uint_index++]);
        case JJSON_REAL:
            if (reader->real_index >= reader->real_count) {
                reader->error = "Truncated real number tape";
                return NULL;
            }
            if (!isfinite(reader->reals[reader->real_index])) {
                reader->error = "JSON cannot represent NaN or infinity";
                return NULL;
            }
            return yyjson_mut_real(doc, reader->reals[reader->real_index++]);
        case JJSON_STRING: {
            const char *string;
            uint32_t length;
            if (!read_string(reader, &string, &length)) return NULL;
            return yyjson_mut_strncpy(doc, string, length);
        }
        case JJSON_RAW_NUMBER: {
            const char *digits;
            uint32_t length;
            if (!read_string(reader, &digits, &length)) return NULL;
            if (!valid_number_token(digits, length)) {
                reader->error = "Invalid raw JSON number";
                return NULL;
            }
            return yyjson_mut_rawncpy(doc, digits, length);
        }
        case JJSON_ARRAY:
            if (depth >= JJSON_MAX_DEPTH) {
                reader->error =
                    "JSON nesting exceeds the maximum depth of 1000";
                return NULL;
            }
            if (!read_u32(reader, &count)) return NULL;
            value = yyjson_mut_arr(doc);
            if (!value) {
                reader->error = "Unable to allocate JSON array";
                return NULL;
            }
            for (index = 0; index < count; index++) {
                yyjson_mut_val *item = decode_value(doc, reader, depth + 1);
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
            if (!read_u32(reader, &count)) return NULL;
            value = yyjson_mut_obj(doc);
            if (!value) {
                reader->error = "Unable to allocate JSON object";
                return NULL;
            }
            for (index = 0; index < count; index++) {
                const char *key_string;
                uint32_t key_length;
                yyjson_mut_val *key;
                yyjson_mut_val *item;
                if (!read_string(reader, &key_string, &key_length)) return NULL;
                key = yyjson_mut_strncpy(doc, key_string, key_length);
                item = decode_value(doc, reader, depth + 1);
                if (!key || !item || !yyjson_mut_obj_add(value, key, item)) {
                    if (!reader->error)
                        reader->error = "Unable to add JSON object entry";
                    return NULL;
                }
            }
            return value;
        default:
            reader->error = "Unknown JSON tape tag";
            return NULL;
    }
}

JJSON_EXPORT char *jjson_write_tape(
        const uint8_t *ops, size_t ops_len,
        const int64_t *sints, size_t sint_count,
        const uint64_t *uints, size_t uint_count,
        const double *reals, size_t real_count,
        const char *strings, size_t strings_len,
        int pretty, size_t *output_length, const char **message) {
    TapeReader reader;
    yyjson_mut_doc *doc;
    yyjson_mut_val *root;
    yyjson_write_err error;
    char *output;
    memset(&reader, 0, sizeof(reader));
    reader.ops = ops;
    reader.ops_len = ops_len;
    reader.sints = sints;
    reader.sint_count = sint_count;
    reader.uints = uints;
    reader.uint_count = uint_count;
    reader.reals = reals;
    reader.real_count = real_count;
    reader.strings = strings;
    reader.strings_len = strings_len;
    if (!ops || ops_len == 0) {
        if (message) *message = "JSON tape is empty";
        return NULL;
    }
    doc = yyjson_mut_doc_new(NULL);
    if (!doc) {
        if (message) *message = "Unable to allocate JSON document";
        return NULL;
    }
    root = decode_value(doc, &reader, 0);
    if (!root ||
        reader.op != reader.ops_len ||
        reader.sint_index != reader.sint_count ||
        reader.uint_index != reader.uint_count ||
        reader.real_index != reader.real_count ||
        reader.string_index != reader.strings_len) {
        if (message)
            *message = reader.error ? reader.error : "JSON tape has trailing data";
        yyjson_mut_doc_free(doc);
        return NULL;
    }
    yyjson_mut_doc_set_root(doc, root);
    output = yyjson_mut_write_opts(
        doc, pretty ? YYJSON_WRITE_PRETTY : YYJSON_WRITE_NOFLAG,
        NULL, output_length, &error);
    if (!output && message) *message = error.msg;
    yyjson_mut_doc_free(doc);
    return output;
}