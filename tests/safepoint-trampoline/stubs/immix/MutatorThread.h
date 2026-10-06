#pragma once
#include <stdint.h>
typedef struct MutatorThread { uintptr_t safepointResumePc; } MutatorThread;
extern MutatorThread *currentMutatorThread;
