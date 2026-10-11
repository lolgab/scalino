#define _GNU_SOURCE
#include <signal.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/mman.h>
#include "SafepointPollTrampoline.h"
#include "immix/MutatorThread.h"

MutatorThread *currentMutatorThread;
static MutatorThread mt;
static void *page;
static volatile int yields;

extern int sn_test_poll(void *page, uint64_t *out);

void Synchronizer_yield(void) {
    yields++;
    /* scribble over everything the trampoline must preserve */
    __asm__ volatile(
        "mov x16, #-1\n\tmov x17, #-1\n\tmov x30, #-1\n\t"
        "movi v16.16b, #0xff\n\tmovi v31.16b, #0xff\n\tmovi v0.16b, #0xff\n\tmovi v7.16b, #0xff\n\t"
        "mov x9, #0\n\tcmp x9, #1\n\t" /* clears Z and C */
        ::: "x9", "x16", "x17", "x30", "v0", "v7", "v16", "v31", "cc");
    mprotect(page, 4096, PROT_READ | PROT_WRITE); /* disarm so the retried poll succeeds */
}

static void handler(int sig, siginfo_t *si, void *uap) {
    (void)sig; (void)si;
    if (!scalanative_gc_safepoint_prepare_redirect(uap, &mt)) { fprintf(stderr, "redirect unsupported\n"); _exit(2); }
}

static int bad;
#define CHECK(c, ...) do { if (!(c)) { printf("  FAIL: " __VA_ARGS__); printf("\n"); bad++; } } while (0)

int main(void) {
    currentMutatorThread = &mt;
    page = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = handler; sa.sa_flags = SA_SIGINFO;
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    for (int it = 0; it < 200; it++) {
        uint64_t out[128];
        memset(out, 0, sizeof out);
        mprotect(page, 4096, PROT_NONE);
        sn_test_poll(page, out);
        if (it > 0) continue;
        for (int n = 0; n <= 30; n++) {
            if (n == 28) { CHECK(out[28] == (uint64_t)page, "x28 changed"); continue; }
            if (n == 18) continue; /* platform register */
            CHECK(out[n] == (uint64_t)(0xa000 + n), "x%d = %016llx, expected %016llx", n, (unsigned long long)out[n], (unsigned long long)(0xa000 + n));
        }
        CHECK((out[31] & 0x60000000) == 0x60000000, "NZCV Z/C lost (%016llx)", (unsigned long long)out[31]);
        CHECK(out[32] == 0x1111000000000000ull && out[33] == 0x1111000000000000ull, "v0 = %016llx:%016llx", (unsigned long long)out[33], (unsigned long long)out[32]);
        CHECK(out[34] == 0x7777000000000000ull, "v7 = %016llx", (unsigned long long)out[34]);
        CHECK(out[36] == 0x1616000000000000ull, "v16 = %016llx", (unsigned long long)out[36]);
        CHECK(out[38] == 0x3131000000000000ull, "v31 = %016llx", (unsigned long long)out[38]);
#ifdef __APPLE__
        /* Darwin's kernel skips the 128-byte red zone when it builds a signal frame; Linux/arm64
         * writes the frame at sp, so compiled code cannot rely on a red zone there. */
        int rz = 0;
        for (int i = 112; i < 128; i++) rz += out[i] != 0x12345a5adeadbeefull;
        CHECK(rz == 0, "red zone: %d of 16 qwords clobbered", rz);
#endif
    }
    CHECK(yields == 200, "yield count %d", yields);
    printf(bad ? "AARCH64 TRAMPOLINE TEST FAILED (%d)\n" : "AARCH64 TRAMPOLINE TEST PASSED\n", bad);
    return bad != 0;
}
