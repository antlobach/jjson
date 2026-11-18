/* Test-only allocator interposition, applied to the codec and yyjson objects,
 * never Chez. Counts live allocations and injects deterministic OOM failures. */
#ifndef JJSON_TEST_ALLOC_H
#define JJSON_TEST_ALLOC_H
#include <stddef.h>
#include <stdlib.h>

void *jjson_test_malloc(size_t size);
void *jjson_test_calloc(size_t count, size_t size);
void *jjson_test_realloc(void *pointer, size_t size);
void jjson_test_free(void *pointer);

#ifndef JJSON_TEST_ALLOC_IMPL
#define malloc jjson_test_malloc
#define calloc jjson_test_calloc
#define realloc jjson_test_realloc
#define free jjson_test_free
#endif
#endif
