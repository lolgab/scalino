#define _GNU_SOURCE
#include <signal.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <emmintrin.h>
#include <ucontext.h>
#include "SafepointPollTrampoline.h"
#include "immix/MutatorThread.h"

MutatorThread *currentMutatorThread;
static MutatorThread mt;
static void *page;
static volatile int yields, misaligned;

extern int64_t sn_test_poll(void *page, int misalign);
extern uint64_t sn_res_regs[15], sn_res_xmm[3], sn_res_flags, sn_res_redzone_bad, sn_res_rsp_delta;

void Synchronizer_yield(void) {
    yields++;
    /* the trampoline must hand C code an ABI-aligned stack: use aligned SSE on the stack */
    volatile __m128i v __attribute__((aligned(16))) = _mm_set1_epi8(1);
    if (((uintptr_t)&v) & 15) misaligned++;
    __m128i w = _mm_load_si128((const __m128i *)&v);   /* movaps/movdqa: faults if rsp misaligned */
    __asm__ volatile("" :: "x"(w));
    /* scribble over everything the trampoline must have preserved */
    __asm__ volatile(
        "movq $-1, %%r8\n\tmovq $-1, %%r9\n\tmovq $-1, %%r10\n\tmovq $-1, %%r11\n\t"
        "pcmpeqd %%xmm0,%%xmm0\n\tpcmpeqd %%xmm7,%%xmm7\n\tpcmpeqd %%xmm15,%%xmm15\n\t"
        "xorl %%eax,%%eax\n\tadd $1,%%eax\n\t"                /* clears ZF and CF */
        ::: "r8","r9","r10","r11","rax","xmm0","xmm7","xmm15","cc");
    mprotect(page, 4096, PROT_READ | PROT_WRITE);        /* disarm so the retried poll succeeds */
}

static void handler(int sig, siginfo_t *si, void *uap) {
    if (!scalanative_gc_safepoint_prepare_redirect(uap, &mt)) { fprintf(stderr, "redirect unsupported\n"); _exit(2); }
}

#define CHECK(c, ...) do { if (!(c)) { printf("  FAIL: " __VA_ARGS__); printf("\n"); bad++; } } while (0)

int main(void) {
    currentMutatorThread = &mt;
    page = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    struct sigaction sa = {0};
    sa.sa_sigaction = handler; sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigaction(SIGSEGV, &sa, NULL);
    int total_bad = 0;
    for (int mis = 0; mis < 2; mis++) {
        int bad = 0;
        for (int it = 0; it < 200; it++) {
            mprotect(page, 4096, PROT_NONE);
            sn_test_poll(page, mis);
            if (it == 0) {
                static const uint64_t exp[15] = {0xb1b1b1b1b1b1b1b1,0xc2c2c2c2c2c2c2c2,0xd3d3d3d3d3d3d3d3,0xe4e4e4e4e4e4e4e4,0,
                    0xf5f5f5f5f5f5f5f5,0xa6a6a6a6a6a6a6a6,0xa7a7a7a7a7a7a7a7,0xa8a8a8a8a8a8a8a8,0xa9a9a9a9a9a9a9a9,
                    0xaaaaaaaaaaaaaaaa,0xabababababababab,0xacacacacacacacac,0xadadadadadadadad,0};
                static const char *nm[] = {"rbx","rcx","rdx","rsi","rdi","r8","r9","r10","r11","r12","r13","r14","r15","rbp"};
                for (int i = 0; i < 14; i++) if (i != 4) CHECK(sn_res_regs[i] == exp[i], "%s = %016lx, expected %016lx", nm[i], sn_res_regs[i], exp[i]);
                CHECK(sn_res_xmm[0] == 0x1111111111111111, "xmm0 = %016lx", sn_res_xmm[0]);
                CHECK(sn_res_xmm[1] == 0x2222222222222222, "xmm7 = %016lx", sn_res_xmm[1]);
                CHECK(sn_res_xmm[2] == 0x3333333333333333, "xmm15 = %016lx", sn_res_xmm[2]);
                CHECK((sn_res_flags & 0x41) == 0x41, "flags ZF/CF lost (ah=%02lx)", sn_res_flags);
                CHECK(sn_res_redzone_bad == 0, "red zone: %lu of 16 qwords clobbered", sn_res_redzone_bad);
                CHECK(sn_res_rsp_delta == 0, "rsp changed by %ld", (long)sn_res_rsp_delta);
            }
        }
        CHECK(yields == (mis + 1) * 200, "yield count %d", yields);
        printf("misalign=%d: %s (yields=%d)\n", mis, bad ? "BAD" : "ok", yields);
        total_bad += bad;
    }
    printf(total_bad ? "TRAMPOLINE TEST FAILED (%d)\n" : "TRAMPOLINE TEST PASSED\n", total_bad);
    return total_bad != 0;
}
