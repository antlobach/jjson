/* Exercise the actual bridge against real, pinned Chez objects. Allocation
 * interposition covers codec + yyjson ownership, including every injected OOM.
 * Compile the codec/yyjson objects with -include test/native_alloc.h. */
#define JJSON_TEST_ALLOC_IMPL
#include "native_alloc.h"
#include "jjson_chez.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

typedef union {
    max_align_t alignment;
    struct { size_t size; } value;
} Allocation;

static size_t live_blocks;
static size_t live_bytes;
static long fail_after = -1;

static int fail_allocation(void) {
    if (fail_after < 0) return 0;
    if (fail_after == 0) return 1;
    fail_after--;
    return 0;
}

void *jjson_test_malloc(size_t size) {
    Allocation *block;
    if (size > SIZE_MAX - sizeof(Allocation) || fail_allocation()) return NULL;
    block = malloc(sizeof(Allocation) + size);
    if (!block) return NULL;
    block->value.size = size;
    live_blocks++;
    live_bytes += size;
    return block + 1;
}

void jjson_test_free(void *pointer) {
    Allocation *block;
    if (!pointer) return;
    block = (Allocation *)pointer - 1;
    assert(live_blocks > 0 && live_bytes >= block->value.size);
    live_blocks--;
    live_bytes -= block->value.size;
    free(block);
}

void *jjson_test_calloc(size_t count, size_t size) {
    void *pointer;
    if (size && count > SIZE_MAX / size) return NULL;
    pointer = jjson_test_malloc(count * size);
    if (pointer) memset(pointer, 0, count * size);
    return pointer;
}

void *jjson_test_realloc(void *pointer, size_t size) {
    Allocation *block;
    size_t previous;
    if (!pointer) return jjson_test_malloc(size);
    if (!size) { jjson_test_free(pointer); return NULL; }
    if (size > SIZE_MAX - sizeof(Allocation) || fail_allocation()) return NULL;
    block = (Allocation *)pointer - 1;
    previous = block->value.size;
    block = realloc(block, sizeof(Allocation) + size);
    if (!block) return NULL;
    block->value.size = size;
    live_bytes = live_bytes - previous + size;
    return block + 1;
}

static ptr pinned_string(const char *text) {
    ptr object = Sstring_utf8(text, (iptr)strlen(text));
    Slock_object(object);
    return object;
}

static ptr pinned_bytes(const char *text) {
    size_t size = strlen(text);
    ptr object = Smake_bytevector((iptr)size, 0);
    Slock_object(object);
    memcpy(Sbytevector_data(object), text, size);
    return object;
}

static void check_decode(ptr input, int bytes, int raw) {
    void *handle;
    ptr chars, records, wrong;
    size_t nrecords, nchars;
    Sdeactivate_thread();
    handle = bytes ? jjson_decode_bytes(input, raw) : jjson_decode_string(input, raw);
    Sactivate_thread();
    if (!handle) return;
    if (jjson_decode_error(handle)) {
        jjson_decode_free(handle);
        return;
    }
    nrecords = jjson_decode_records(handle);
    nchars = jjson_decode_chars(handle);
    chars = Smake_string((iptr)nchars, 0);
    Slock_object(chars);
    records = Smake_bytevector((iptr)(nrecords * 16), 0);
    Slock_object(records);
    /* Force real GC while the input/output objects are pinned. */
    Scall0(Stop_level_value(Sstring_to_symbol("collect")));
    Sdeactivate_thread();
    assert(jjson_decode_fill(handle, chars, records));
    Sactivate_thread();
    assert(Sbytevector_u8_ref(records, 0) == 8); /* root object */
    wrong = Smake_bytevector(1, 0);
    Slock_object(wrong);
    assert(!jjson_decode_fill(handle, chars, wrong));
    Sunlock_object(wrong);
    Sunlock_object(records);
    Sunlock_object(chars);
    jjson_decode_free(handle);
}

static void check_encode(ptr records, ptr chars, int flags) {
    static const char expected[] = "\"\xc3\xa9\xf0\x9f\x98\x80\"";
    void *handle;
    ptr output, wrong;
    Sdeactivate_thread();
    handle = jjson_encode(records, 1, chars, 2, flags);
    Sactivate_thread();
    if (!handle) return;
    if (jjson_encode_error(handle)) {
        jjson_encode_free(handle);
        return;
    }
    assert(jjson_encode_bytes(handle) == sizeof(expected) - 1);
    output = (flags & 2) ? Smake_bytevector((iptr)jjson_encode_bytes(handle), 0)
                         : Smake_string((iptr)jjson_encode_chars(handle), 0);
    Slock_object(output);
    Scall0(Stop_level_value(Sstring_to_symbol("collect")));
    Sdeactivate_thread();
    if (flags & 2) {
        assert(jjson_encode_fill_bytes(handle, output));
        assert(memcmp(Sbytevector_data(output), expected, sizeof(expected) - 1) == 0);
    } else {
        assert(jjson_encode_fill(handle, output));
        assert(Sstring_length(output) == 4);
        assert(Sstring_ref(output, 0) == '"');
        assert(Sstring_ref(output, 1) == 0xE9);
        assert(Sstring_ref(output, 2) == 0x1F600);
        assert(Sstring_ref(output, 3) == '"');
    }
    Sactivate_thread();
    wrong = (flags & 2) ? Smake_bytevector(1, 0) : Smake_string(1, 0);
    Slock_object(wrong);
    assert(!(flags & 2 ? jjson_encode_fill_bytes(handle, wrong)
                       : jjson_encode_fill(handle, wrong)));
    Sunlock_object(wrong);
    Sunlock_object(output);
    jjson_encode_free(handle);
}

int main(int argc, char **argv) {
    static const char source[] = "{\"s\":\"\xc3\xa9\xf0\x9f\x98\x80\",\"n\":[1,1.2300,184467440737095516161234567890]}";
    ptr input, bytes, invalid, records, chars;
    uint64_t length = 2;
    char boot[4096];
    assert(argc == 2);
    Sscheme_init(NULL);
    snprintf(boot, sizeof(boot), "%s/petite.boot", argv[1]);
    Sregister_boot_file(boot);
    snprintf(boot, sizeof(boot), "%s/scheme.boot", argv[1]);
    Sregister_boot_file(boot);
    Sbuild_heap(argv[0], NULL);
    input = pinned_string(source);
    bytes = pinned_bytes(source);
    invalid = pinned_bytes("{\"x\":}");
    records = Smake_bytevector(16, 0);
    Slock_object(records);
    Sbytevector_u8_set(records, 0, 6); /* string record */
    memcpy(Sbytevector_data(records) + 8, &length, 8);
    chars = pinned_string("\xc3\xa9\xf0\x9f\x98\x80");

    for (int iteration = 0; iteration < 200; iteration++) {
        check_decode(input, 0, iteration & 1);
        check_decode(bytes, 1, iteration & 1);
        check_decode(invalid, 1, 0);
        check_encode(records, chars, iteration & 3);
        assert(live_blocks == 0 && live_bytes == 0);
    }
    /* Each budget fails a later allocation. Successful budgets cover the full
     * path; failed budgets exercise its cleanup. No codec allocations survive. */
    for (long budget = 0; budget < 64; budget++) {
        fail_after = budget;
        check_decode(bytes, 1, 1);
        assert(live_blocks == 0 && live_bytes == 0);
        fail_after = budget;
        check_encode(records, chars, 2);
        assert(live_blocks == 0 && live_bytes == 0);
    }
    fail_after = -1;
    Sunlock_object(chars);
    Sunlock_object(records);
    Sunlock_object(invalid);
    Sunlock_object(bytes);
    Sunlock_object(input);
    Sscheme_deinit();
    puts("PASS: real Chez bridge, GC, byte/string output, OOM cleanup; zero live codec allocations");
    return 0;
}
