/* Collect-safe decoder for Jolt's Chez runtime.
 *
 * Decoding is split so that no C code runs while the calling thread holds the
 * Chez runtime active:
 *
 *   1. jjson_decode_string / jjson_decode_bytes (__collect_safe): read the
 *      caller-locked input, validate, and parse with yyjson. Count the value
 *      records and UTF-32 characters the result needs.
 *   2. Scheme allocates one character string and one record bytevector of
 *      exactly those sizes and locks both.
 *   3. jjson_decode_fill (__collect_safe): write one 16-byte record per JSON
 *      value in preorder, and every string's characters, into those locked
 *      objects.
 *   4. Scheme walks the records and builds Jolt values; collection proceeds
 *      normally throughout.
 *
 * Chez permits collect-safe calls to touch Scheme objects only when they are
 * locked, so this file only reads or writes memory of objects the caller has
 * locked (via the scheme.h accessor macros) and never allocates Scheme
 * objects or calls into Scheme.
 *
 * Record layout (native endianness):
 *   byte 0      tag
 *   bytes 4-7   u32 auxiliary value (interned key id)
 *   bytes 8-15  u64 payload: integer bits, double bits, string length in
 *               characters, or element/entry count
 * Strings' characters are stored back to back in record order; the reader
 * keeps a running character offset.
 */

#include "jjson.h"
#include "jjson_internal.h"
#include "scheme.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#define JJSON_EXPORT __declspec(dllexport)
#else
#define JJSON_EXPORT __attribute__((visibility("default")))
#endif

#define JJSON_RECORD_BYTES 16
#define JJSON_INTERN_SLOTS 4096
#define JJSON_INTERN_LIMIT (JJSON_INTERN_SLOTS / 2)

enum {
    REC_NULL = 0,
    REC_FALSE = 1,
    REC_TRUE = 2,
    REC_SINT = 3,
    REC_UINT = 4,
    REC_REAL = 5,
    REC_STRING = 6,
    REC_ARRAY = 7,
    REC_OBJECT = 8,
    REC_KEY_NEW = 9,   /* first occurrence of an interned key: aux id, length */
    REC_KEY_REF = 10,  /* repeated interned key: aux id */
    REC_KEY_PLAIN = 11 /* key outside the intern table: length */
};

typedef struct {
    uint64_t hash;
    const char *bytes;
    size_t length;
    uint32_t id;
    int emitted;
} InternEntry;

typedef struct {
    char *input;
    yyjson_doc *document;
    InternEntry *intern;
    size_t key_count;
    size_t record_count;
    size_t char_count;
    const char *message;
} Decode;

typedef struct {
    uint8_t *records;
    size_t record;
    string_char *chars;
    size_t position;
    InternEntry *intern;
} Writer;

static uint64_t hash_bytes(const char *bytes, size_t length) {
    uint64_t hash = UINT64_C(1469598103934665603);
    size_t index;
    for (index = 0; index < length; index++) {
        hash ^= (unsigned char)bytes[index];
        hash *= UINT64_C(1099511628211);
    }
    return hash ? hash : 1;
}

/* Returns the table entry for KEY, inserting it while capacity remains.
 * NULL means the key is not interned. *inserted reports a new entry. */
static InternEntry *intern_key(Decode *decode, const char *bytes, size_t length,
                               int *inserted) {
    uint64_t hash = hash_bytes(bytes, length);
    size_t mask = JJSON_INTERN_SLOTS - 1;
    size_t slot = (size_t)hash & mask;
    size_t probes;
    *inserted = 0;
    for (probes = 0; probes < JJSON_INTERN_SLOTS; probes++) {
        InternEntry *entry = &decode->intern[slot];
        if (!entry->hash) {
            if (decode->key_count >= JJSON_INTERN_LIMIT) return NULL;
            entry->hash = hash;
            entry->bytes = bytes;
            entry->length = length;
            entry->id = (uint32_t)decode->key_count++;
            *inserted = 1;
            return entry;
        }
        if (entry->hash == hash && entry->length == length &&
            memcmp(entry->bytes, bytes, length) == 0) {
            return entry;
        }
        slot = (slot + 1) & mask;
    }
    return NULL;
}

/* Code points in validated UTF-8: every byte that is not a continuation. */
static size_t utf8_chars(const char *bytes, size_t length) {
    size_t chars = 0;
    size_t index;
    for (index = 0; index < length; index++) {
        chars += (((unsigned char)bytes[index]) & 0xC0) != 0x80;
    }
    return chars;
}

static void count_value(Decode *decode, yyjson_val *value) {
    if (yyjson_is_str(value)) {
        decode->char_count += utf8_chars(yyjson_get_str(value),
                                         yyjson_get_len(value));
    } else if (yyjson_is_arr(value)) {
        yyjson_arr_iter iterator;
        yyjson_val *item;
        yyjson_arr_iter_init(value, &iterator);
        while ((item = yyjson_arr_iter_next(&iterator))) {
            count_value(decode, item);
        }
    } else if (yyjson_is_obj(value)) {
        yyjson_obj_iter iterator;
        yyjson_val *key;
        yyjson_obj_iter_init(value, &iterator);
        while ((key = yyjson_obj_iter_next(&iterator))) {
            const char *bytes = yyjson_get_str(key);
            size_t length = yyjson_get_len(key);
            int inserted;
            InternEntry *entry = intern_key(decode, bytes, length, &inserted);
            if (!entry || inserted) {
                decode->char_count += utf8_chars(bytes, length);
            }
            count_value(decode, yyjson_obj_iter_get_val(key));
        }
    }
}

static void put_record(Writer *writer, uint8_t tag, uint32_t aux,
                       uint64_t payload) {
    uint8_t *record = writer->records + writer->record * JJSON_RECORD_BYTES;
    record[0] = tag;
    memcpy(record + 4, &aux, sizeof(aux));
    memcpy(record + 8, &payload, sizeof(payload));
    writer->record++;
}

#define CHEZ_CHAR(codepoint) ((string_char)(((uptr)(codepoint) << 8) | 0x16))

/* Decodes validated UTF-8 into Chez string cells; returns cells written. */
static size_t utf8_to_cells(string_char *cells, const char *bytes,
                            size_t length) {
    string_char *out = cells;
    size_t input = 0;
    while (input < length) {
        while (input + 8 <= length) {
            uint64_t word;
            memcpy(&word, bytes + input, sizeof(word));
            if (word & UINT64_C(0x8080808080808080)) break;
            out[0] = CHEZ_CHAR((unsigned char)bytes[input]);
            out[1] = CHEZ_CHAR((unsigned char)bytes[input + 1]);
            out[2] = CHEZ_CHAR((unsigned char)bytes[input + 2]);
            out[3] = CHEZ_CHAR((unsigned char)bytes[input + 3]);
            out[4] = CHEZ_CHAR((unsigned char)bytes[input + 4]);
            out[5] = CHEZ_CHAR((unsigned char)bytes[input + 5]);
            out[6] = CHEZ_CHAR((unsigned char)bytes[input + 6]);
            out[7] = CHEZ_CHAR((unsigned char)bytes[input + 7]);
            out += 8;
            input += 8;
        }
        if (input >= length) break;
        {
            unsigned char lead = (unsigned char)bytes[input++];
            uint32_t codepoint;
            if (lead < 0x80) {
                codepoint = lead;
            } else if (lead < 0xE0) {
                codepoint = ((uint32_t)(lead & 0x1F) << 6) |
                            (uint32_t)((unsigned char)bytes[input] & 0x3F);
                input += 1;
            } else if (lead < 0xF0) {
                codepoint = ((uint32_t)(lead & 0x0F) << 12) |
                            ((uint32_t)((unsigned char)bytes[input] & 0x3F) << 6) |
                            (uint32_t)((unsigned char)bytes[input + 1] & 0x3F);
                input += 2;
            } else {
                codepoint = ((uint32_t)(lead & 0x07) << 18) |
                            ((uint32_t)((unsigned char)bytes[input] & 0x3F) << 12) |
                            ((uint32_t)((unsigned char)bytes[input + 1] & 0x3F) << 6) |
                            (uint32_t)((unsigned char)bytes[input + 2] & 0x3F);
                input += 3;
            }
            *out++ = CHEZ_CHAR(codepoint);
        }
    }
    return (size_t)(out - cells);
}

static size_t put_chars(Writer *writer, const char *bytes, size_t length) {
    size_t written = utf8_to_cells(writer->chars + writer->position, bytes,
                                   length);
    writer->position += written;
    return written;
}

static void fill_value(Writer *writer, yyjson_val *value) {
    switch (yyjson_get_type(value)) {
    case YYJSON_TYPE_NULL:
        put_record(writer, REC_NULL, 0, 0);
        return;
    case YYJSON_TYPE_BOOL:
        put_record(writer, yyjson_get_bool(value) ? REC_TRUE : REC_FALSE, 0, 0);
        return;
    case YYJSON_TYPE_NUM:
        if (yyjson_is_sint(value)) {
            put_record(writer, REC_SINT, 0, (uint64_t)yyjson_get_sint(value));
        } else if (yyjson_is_uint(value)) {
            put_record(writer, REC_UINT, 0, yyjson_get_uint(value));
        } else {
            double real = yyjson_get_real(value);
            uint64_t bits;
            memcpy(&bits, &real, sizeof(bits));
            put_record(writer, REC_REAL, 0, bits);
        }
        return;
    case YYJSON_TYPE_STR: {
        size_t record = writer->record;
        put_record(writer, REC_STRING, 0, 0);
        {
            uint64_t chars = put_chars(writer, yyjson_get_str(value),
                                       yyjson_get_len(value));
            memcpy(writer->records + record * JJSON_RECORD_BYTES + 8, &chars,
                   sizeof(chars));
        }
        return;
    }
    case YYJSON_TYPE_ARR: {
        yyjson_arr_iter iterator;
        yyjson_val *item;
        put_record(writer, REC_ARRAY, 0, (uint64_t)yyjson_arr_size(value));
        yyjson_arr_iter_init(value, &iterator);
        while ((item = yyjson_arr_iter_next(&iterator))) {
            fill_value(writer, item);
        }
        return;
    }
    case YYJSON_TYPE_OBJ: {
        yyjson_obj_iter iterator;
        yyjson_val *key;
        put_record(writer, REC_OBJECT, 0, (uint64_t)yyjson_obj_size(value));
        yyjson_obj_iter_init(value, &iterator);
        while ((key = yyjson_obj_iter_next(&iterator))) {
            const char *bytes = yyjson_get_str(key);
            size_t length = yyjson_get_len(key);
            InternEntry *entry = NULL;
            uint64_t hash = hash_bytes(bytes, length);
            size_t mask = JJSON_INTERN_SLOTS - 1;
            size_t slot = (size_t)hash & mask;
            size_t probes;
            for (probes = 0; probes < JJSON_INTERN_SLOTS; probes++) {
                InternEntry *candidate = &writer->intern[slot];
                if (!candidate->hash) break;
                if (candidate->hash == hash && candidate->length == length &&
                    memcmp(candidate->bytes, bytes, length) == 0) {
                    entry = candidate;
                    break;
                }
                slot = (slot + 1) & mask;
            }
            if (entry && entry->emitted) {
                put_record(writer, REC_KEY_REF, entry->id, 0);
            } else {
                size_t record = writer->record;
                uint64_t chars;
                put_record(writer, entry ? REC_KEY_NEW : REC_KEY_PLAIN,
                           entry ? entry->id : 0, 0);
                chars = put_chars(writer, bytes, length);
                memcpy(writer->records + record * JJSON_RECORD_BYTES + 8,
                       &chars, sizeof(chars));
                if (entry) entry->emitted = 1;
            }
            fill_value(writer, yyjson_obj_iter_get_val(key));
        }
        return;
    }
    default:
        /* Unreachable: validation accepted only the types above. */
        put_record(writer, REC_NULL, 0, 0);
        return;
    }
}

static Decode *new_decode(void) {
    return (Decode *)calloc(1, sizeof(Decode));
}

/* Takes ownership of INPUT (LENGTH bytes plus zeroed yyjson padding). */
static Decode *parse_input(Decode *decode, char *input, size_t length) {
    size_t position = 0;
    int code = 0;
    decode->input = input;
    decode->document = jjson_internal_read_doc(input, length, &decode->message,
                                               &position, &code);
    if (!decode->document) {
        if (!decode->message) decode->message = "Unknown JSON read error";
        return decode;
    }
    decode->intern = (InternEntry *)calloc(JJSON_INTERN_SLOTS,
                                           sizeof(InternEntry));
    if (!decode->intern) {
        decode->message = "Unable to allocate JSON key table";
        return decode;
    }
    decode->record_count = yyjson_doc_get_val_count(decode->document);
    count_value(decode, yyjson_doc_get_root(decode->document));
    return decode;
}

/* STRING must be a Chez string locked by the caller. Returns NULL only when
 * the decode state itself cannot be allocated. */
JJSON_EXPORT void *jjson_decode_string(ptr string) {
    Decode *decode = new_decode();
    const string_char *cells;
    size_t length;
    size_t index = 0;
    char *buffer;
    char *out;
    if (!decode) return NULL;
    if (!Sstringp(string)) {
        decode->message = "JSON input must be a string";
        return decode;
    }
    length = (size_t)Sstring_length(string);
    if (length > (SIZE_MAX - YYJSON_PADDING_SIZE) / 4) {
        decode->message = "JSON input is too large";
        return decode;
    }
    /* One pass into a worst-case buffer; untouched pages are never
     * committed, so the 4x reservation costs only address space. */
    buffer = (char *)malloc(length * 4 + YYJSON_PADDING_SIZE);
    if (!buffer) {
        decode->message = "Unable to allocate UTF-8 input buffer";
        return decode;
    }
    cells = (const string_char *)TO_VOIDP((uptr)string + 9);
    out = buffer;
    while (index < length) {
        /* A cell holds codepoint << 8 | tag, so four ASCII characters OR to
         * a value below 0x8000. */
        while (length - index >= 4 &&
               (cells[index] | cells[index + 1] | cells[index + 2] |
                cells[index + 3]) < 0x8000) {
            out[0] = (char)(cells[index] >> 8);
            out[1] = (char)(cells[index + 1] >> 8);
            out[2] = (char)(cells[index + 2] >> 8);
            out[3] = (char)(cells[index + 3] >> 8);
            out += 4;
            index += 4;
        }
        if (index >= length) break;
        {
            uint32_t codepoint = (uint32_t)(cells[index++] >> 8);
            if (codepoint < 0x80) {
                *out++ = (char)codepoint;
            } else if (codepoint < 0x800) {
                *out++ = (char)(0xC0 | (codepoint >> 6));
                *out++ = (char)(0x80 | (codepoint & 0x3F));
            } else if (codepoint < 0x10000) {
                *out++ = (char)(0xE0 | (codepoint >> 12));
                *out++ = (char)(0x80 | ((codepoint >> 6) & 0x3F));
                *out++ = (char)(0x80 | (codepoint & 0x3F));
            } else {
                *out++ = (char)(0xF0 | (codepoint >> 18));
                *out++ = (char)(0x80 | ((codepoint >> 12) & 0x3F));
                *out++ = (char)(0x80 | ((codepoint >> 6) & 0x3F));
                *out++ = (char)(0x80 | (codepoint & 0x3F));
            }
        }
    }
    memset(out, 0, YYJSON_PADDING_SIZE);
    return parse_input(decode, buffer, (size_t)(out - buffer));
}

/* BYTES must be a Chez bytevector locked by the caller. It is copied, never
 * modified. Returns NULL only when the decode state cannot be allocated. */
JJSON_EXPORT void *jjson_decode_bytes(ptr bytes) {
    Decode *decode = new_decode();
    size_t length;
    char *buffer;
    if (!decode) return NULL;
    if (!Sbytevectorp(bytes)) {
        decode->message = "JSON input must be a byte array";
        return decode;
    }
    length = (size_t)Sbytevector_length(bytes);
    buffer = (char *)malloc(length + YYJSON_PADDING_SIZE);
    if (!buffer) {
        decode->message = "Unable to allocate JSON input buffer";
        return decode;
    }
    if (length) memcpy(buffer, Sbytevector_data(bytes), length);
    memset(buffer + length, 0, YYJSON_PADDING_SIZE);
    return parse_input(decode, buffer, length);
}

JJSON_EXPORT const char *jjson_decode_error(void *handle) {
    return ((Decode *)handle)->message;
}

JJSON_EXPORT size_t jjson_decode_records(void *handle) {
    return ((Decode *)handle)->record_count;
}

JJSON_EXPORT size_t jjson_decode_chars(void *handle) {
    return ((Decode *)handle)->char_count;
}

JJSON_EXPORT size_t jjson_decode_keys(void *handle) {
    return ((Decode *)handle)->key_count;
}

/* CHARS (a Chez string) and RECORDS (a Chez bytevector) must be locked by the
 * caller and sized from jjson_decode_chars and jjson_decode_records. Returns
 * nonzero on success. */
JJSON_EXPORT int jjson_decode_fill(void *handle, ptr chars, ptr records) {
    Decode *decode = (Decode *)handle;
    Writer writer;
    if (decode->message || !decode->document) return 0;
    if (!Sstringp(chars) || !Sbytevectorp(records) ||
        (size_t)Sstring_length(chars) != decode->char_count ||
        (size_t)Sbytevector_length(records) !=
            decode->record_count * JJSON_RECORD_BYTES) {
        decode->message = "JSON decode buffers have the wrong size";
        return 0;
    }
    writer.records = (uint8_t *)Sbytevector_data(records);
    writer.record = 0;
    writer.chars = (string_char *)TO_VOIDP((uptr)chars + 9);
    writer.position = 0;
    writer.intern = decode->intern;
    fill_value(&writer, yyjson_doc_get_root(decode->document));
    if (writer.record != decode->record_count ||
        writer.position != decode->char_count) {
        decode->message = "JSON decode record count mismatch";
        return 0;
    }
    return 1;
}

JJSON_EXPORT void jjson_decode_free(void *handle) {
    Decode *decode = (Decode *)handle;
    if (!decode) return;
    if (decode->document) yyjson_doc_free(decode->document);
    free(decode->intern);
    free(decode->input);
    free(decode);
}

/* ---------------------------------------------------------------------------
 * Encoding
 *
 * Scheme walks the Jolt value into 16-byte records (the jjson_write_records
 * layout) plus one character string, locks both, and calls jjson_encode
 * (__collect_safe). yyjson writes the UTF-8 output into native memory; Scheme
 * then allocates a string of jjson_encode_chars characters, locks it, and
 * jjson_encode_fill (__collect_safe) decodes the output into it.
 * ------------------------------------------------------------------------- */

typedef struct {
    char *output;
    size_t length;
    size_t char_count;
    const char *message;
} Encode;

JJSON_EXPORT void *jjson_encode(ptr records, size_t record_count, ptr chars,
                                size_t char_count, int pretty) {
    Encode *encode = (Encode *)calloc(1, sizeof(Encode));
    if (!encode) return NULL;
    if (!Sbytevectorp(records) || !Sstringp(chars) ||
        record_count > (size_t)Sbytevector_length(records) / JJSON_RECORD_BYTES ||
        char_count > (size_t)Sstring_length(chars)) {
        encode->message = "JSON encode buffers have the wrong size";
        return encode;
    }
    encode->output = jjson_write_records(
        (const uint8_t *)Sbytevector_data(records), record_count,
        (const uint32_t *)TO_VOIDP((uptr)chars + 9), char_count, 8, pretty,
        &encode->length, &encode->message);
    if (!encode->output) {
        if (!encode->message) encode->message = "JSON write failed";
        return encode;
    }
    encode->char_count = utf8_chars(encode->output, encode->length);
    return encode;
}

JJSON_EXPORT const char *jjson_encode_error(void *handle) {
    return ((Encode *)handle)->message;
}

JJSON_EXPORT size_t jjson_encode_chars(void *handle) {
    return ((Encode *)handle)->char_count;
}

/* OUTPUT must be a Chez string, locked by the caller, of exactly
 * jjson_encode_chars characters. Returns nonzero on success. */
JJSON_EXPORT int jjson_encode_fill(void *handle, ptr output) {
    Encode *encode = (Encode *)handle;
    if (encode->message || !encode->output) return 0;
    if (!Sstringp(output) ||
        (size_t)Sstring_length(output) != encode->char_count) {
        encode->message = "JSON output buffer has the wrong size";
        return 0;
    }
    utf8_to_cells((string_char *)TO_VOIDP((uptr)output + 9), encode->output,
                  encode->length);
    return 1;
}

JJSON_EXPORT void jjson_encode_free(void *handle) {
    Encode *encode = (Encode *)handle;
    if (!encode) return;
    free(encode->output);
    free(encode);
}
