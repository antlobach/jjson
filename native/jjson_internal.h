#ifndef JJSON_INTERNAL_H
#define JJSON_INTERNAL_H

#include "yyjson.h"

#include <stddef.h>

/* Parse and validate mutable, padded UTF-8 input. The returned document owns
 * its in-situ parse state but never owns DATA. Callers free it with
 * yyjson_doc_free before releasing DATA. */
yyjson_doc *jjson_internal_read_doc(char *data, size_t length,
                                    const char **message, size_t *position,
                                    int *code);

#endif /* JJSON_INTERNAL_H */
