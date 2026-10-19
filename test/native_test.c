/* Adversarial native test for JJSON validation and write-tape ABIs.
 *
 * Built with the deliberately permissive parser flag
 * -DJJSON_READ_FLAGS='(YYJSON_READ_INSITU|YYJSON_READ_ALLOW_INVALID_UNICODE)'
 * so it proves JJSON's own UTF-8 validator stays strict regardless of what the
 * underlying parser would accept. Every erroneous input must still fail.
 */

#include "jjson.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures = 0;

#define CHECK(condition, label)                                              \
    do {                                                                     \
        if (!(condition)) {                                                  \
            fprintf(stderr, "FAIL: %s\n", (label));                          \
            failures++;                                                      \
        }                                                                    \
    } while (0)

/* Copy JSON into a padded input allocation exactly the way a caller does,
 * validate it, and release the input before returning. */
static int validate_literal(const char *json, size_t length)
{
    const char *message = NULL;
    size_t position = 0;
    int code = 0;
    char *buffer = jjson_input_alloc(length);
    int valid;
    if (!buffer) {
        fprintf(stderr, "FAIL: input allocation failed\n");
        failures++;
        return 0;
    }
    if (length) memcpy(buffer, json, length);
    valid = jjson_validate(buffer, length, &message, &position, &code);
    jjson_free(buffer);
    if (!valid) {
        CHECK(message != NULL, "failed parse reports a message");
        CHECK(position <= length, "failed parse position stays in bounds");
    }
    return valid;
}

static char *build_nested(int depth) {
    size_t length = 2 * (size_t)depth + 1;
    char *buffer = (char *)malloc(length);
    char *cursor;
    if (!buffer) {
        fprintf(stderr, "FAIL: depth builder allocation failed\n");
        failures++;
        return NULL;
    }
    cursor = buffer;
    for (int i = 0; i < depth; i++) *cursor++ = '[';
    *cursor++ = '0';
    for (int i = 0; i < depth; i++) *cursor++ = ']';
    return buffer;
}

/* Build a write tape holding a single raw-number (tag 9) value. */
static char *write_raw_number(const char *digits, int pretty, size_t *output_length,
                              const char **message) {
    const uint8_t ops[5] = {9,
                            (uint8_t)strlen(digits), 0, 0, 0};
    return jjson_write_tape(ops, sizeof(ops), NULL, 0, NULL, 0, NULL, 0,
                            digits, strlen(digits), pretty, output_length, message);
}

static void check_raw_number(const char *digits, int valid) {
    size_t output_length = 0;
    const char *message = NULL;
    char *output = write_raw_number(digits, 0, &output_length, &message);
    char label[256];
    snprintf(label, sizeof(label), "raw number %s must %s", digits,
             valid ? "write" : "fail");
    CHECK(valid ? (output != NULL) : (output == NULL), label);
    if (output) {
        /* The output must reproduce the raw digits verbatim. */
        CHECK(output_length == strlen(digits), "raw number length matches");
        CHECK(memcmp(output, digits, output_length) == 0, "raw number preserved");
        jjson_free(output);
    } else if (valid) {
        fprintf(stderr, "FAIL: valid raw number %s failed: %s\n", digits,
                message ? message : "(no message)");
        failures++;
    } else {
        CHECK(message != NULL, "invalid raw number reports a message");
    }
}

static void check_plain_write(void) {
    /* {"k":42}: object tag, count 1, key length 1, signed integer tag; the
     * key bytes themselves live in the string lane. */
    const uint8_t ops[10] = {8, 1, 0, 0, 0, 1, 0, 0, 0, 3};
    const int64_t sints[1] = {42};
    const char strings[2] = "k";
    size_t output_length = 0;
    const char *message = NULL;
    char *output = jjson_write_tape(ops, sizeof(ops), sints, 1, NULL, 0,
                                    NULL, 0, strings, 1, 0,
                                    &output_length, &message);
    CHECK(output != NULL, "compact write succeeds");
    if (output) {
        CHECK(output_length == strlen("{\"k\":42}"), "compact write length");
        CHECK(memcmp(output, "{\"k\":42}", output_length) == 0, "compact write output");
        jjson_free(output);
    }

    output_length = 0;
    message = NULL;
    output = jjson_write_tape(ops, sizeof(ops), sints, 1, NULL, 0,
                              NULL, 0, strings, 1, 1,
                              &output_length, &message);
    CHECK(output != NULL, "pretty write succeeds");
    if (output) {
        CHECK(strchr(output, '\n') != NULL, "pretty write indents");
        CHECK(strstr(output, "\"k\": 42") != NULL, "pretty write spaced value");
        jjson_free(output);
    }

    /* Unsigned lane: 18446744073709551615 round-trips exactly. */
    output_length = 0;
    message = NULL;
    {
        const uint8_t uops[1] = {4};
        const uint64_t uints[1] = {UINT64_MAX};
        output = jjson_write_tape(uops, sizeof(uops), NULL, 0, uints, 1,
                                  NULL, 0, NULL, 0, 0, &output_length, &message);
        CHECK(output != NULL, "unsigned integer write succeeds");
        if (output) {
            CHECK(output_length == 20 &&
                  memcmp(output, "18446744073709551615", 20) == 0,
                  "maximum unsigned integer preserved");
            jjson_free(output);
        }
    }
}

static void check_tape_corruption(void) {
    size_t output_length = 0;
    const char *message = NULL;
    char *output;

    /* Array claiming two elements with nothing behind it. */
    {
        const uint8_t ops[5] = {7, 2, 0, 0, 0};
        output = jjson_write_tape(ops, sizeof(ops), NULL, 0, NULL, 0,
                                  NULL, 0, NULL, 0, 0, &output_length, &message);
        CHECK(output == NULL, "truncated array tape fails");
        CHECK(message != NULL, "truncated array tape reports a message");
    }
    /* Unknown tag. */
    {
        const uint8_t ops[1] = {15};
        output = jjson_write_tape(ops, sizeof(ops), NULL, 0, NULL, 0,
                                  NULL, 0, NULL, 0, 0, &output_length, &message);
        CHECK(output == NULL, "unknown tape tag fails");
    }
    /* Raw number tag pointing past the string lane. */
    {
        const uint8_t ops[5] = {9, 5, 0, 0, 0};
        output = jjson_write_tape(ops, sizeof(ops), NULL, 0, NULL, 0,
                                  NULL, 0, NULL, 0, 0, &output_length, &message);
        CHECK(output == NULL, "raw number past string lane fails");
    }
    /* Complete value with trailing operations. */
    {
        const uint8_t ops[2] = {2, 0};
        output = jjson_write_tape(ops, sizeof(ops), NULL, 0, NULL, 0,
                                  NULL, 0, NULL, 0, 0, &output_length, &message);
        CHECK(output == NULL, "trailing tape data fails");
    }
    /* Missing operation tape entirely. */
    {
        output = jjson_write_tape(NULL, 0, NULL, 0, NULL, 0,
                                  NULL, 0, NULL, 0, 0, &output_length, &message);
        CHECK(output == NULL, "empty write tape fails");
    }
    /* Non-finite double. */
    {
        const uint8_t ops[1] = {5};
        const double reals[1] = {1.0 / 0.0};
        output = jjson_write_tape(ops, sizeof(ops), NULL, 0, NULL, 0,
                                  reals, 1, NULL, 0, 0, &output_length, &message);
        CHECK(output == NULL, "non-finite double fails");
        CHECK(message != NULL, "non-finite double reports a message");
    }
}


int main(void) {
    const char *message;
    size_t position;
    int code;
    int valid;
    void *scratch;
    char *nested;

    /* Valid parse through the portable validator. */
    {
        const char *doc =
            "{\"a\":[1,-2,3.5,true,false,null,\"h\xc3\xa9llo \xf0\x9f\x9a\x80\"]}";
        CHECK(validate_literal(doc, strlen(doc)), "valid document validates");
    }

    /* Nesting bounds: 1000 containers in, 1001 out. */
    nested = build_nested(1000);
    CHECK(nested != NULL, "depth builder allocation");
    if (nested) {
        CHECK(validate_literal(nested, strlen(nested)), "depth 1000 parses");
        free(nested);
    }
    nested = build_nested(1001);
    if (nested) {
        message = NULL;
        position = 0;
        code = 0;
        char *buffer = jjson_input_alloc(strlen(nested));
        CHECK(buffer != NULL, "depth 1001 input allocation");
        if (buffer) {
            memcpy(buffer, nested, strlen(nested));
            valid = jjson_validate(buffer, strlen(nested),
                                   &message, &position, &code);
            CHECK(!valid, "depth 1001 rejected");
            CHECK(message != NULL &&
                  strstr(message, "maximum depth of 1000") != NULL,
                  "depth failure names the limit");
            jjson_free(buffer);
        }
        free(nested);
    }

    /* Malformed UTF-8 must be rejected even under permissive parser flags. */
    {
        static const char *const bad[] = {
            "{\"v\":\"\xc3\"}",                /* truncated two-byte */
            "{\"v\":\"\xe0\x80\x80\"}",        /* overlong three-byte */
            "{\"v\":\"\xc0\x80\"}",            /* overlong two-byte */
            "{\"v\":\"\xf0\x80\x80\x80\"}",    /* overlong four-byte */
            "{\"v\":\"\xed\xa0\x80\"}",        /* encoded surrogate */
            "{\"v\":\"\xf4\x90\x80\x80\"}"     /* above U+10FFFF */
        };
        for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
            valid = validate_literal(bad[i], strlen(bad[i]));
            CHECK(!valid, "malformed UTF-8 rejected");
        }
    }

    /* Raw-number grammar on the write path. */
    check_raw_number("0", 1);
    check_raw_number("-0", 1);
    check_raw_number("10", 1);
    check_raw_number("-12.5", 1);
    check_raw_number("1e2", 1);
    check_raw_number("0e0", 1);
    check_raw_number("1.2300", 1);
    check_raw_number("18446744073709551615", 1);
    check_raw_number("-123456789012345678901234567890", 1);
    check_raw_number("01", 0);
    check_raw_number("+1", 0);
    check_raw_number("1.", 0);
    check_raw_number(".5", 0);
    check_raw_number("-", 0);
    check_raw_number("", 0);
    check_raw_number("--1", 0);
    check_raw_number("1e", 0);
    check_raw_number("1E+", 0);
    check_raw_number("1e2.5", 0);
    check_raw_number("1e+-1", 0);

    check_plain_write();
    check_tape_corruption();

    /* Allocation guards: zero is legal, unwrappable overflow is not. */
    scratch = jjson_alloc(0);
    CHECK(scratch != NULL, "zero-size allocation survives");
    jjson_free(scratch);
    CHECK(jjson_input_alloc(SIZE_MAX) == NULL, "input allocation rejects overflow");
    CHECK(jjson_alloc(SIZE_MAX) == NULL, "allocation rejects impossible sizes");
    jjson_free(NULL); /* must be a no-op */

    if (failures == 0) {
        printf("ok\n");
        return 0;
    }
    printf("%d native test failure(s)\n", failures);
    return 1;
}