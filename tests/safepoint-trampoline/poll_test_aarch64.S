// aarch64 variant of poll_test.S: load known values into x0-x30, v0/v7/v16/v31, NZCV and the
// 128-byte red zone, poll an armed page, then dump everything for test_aarch64.c to compare.
// int sn_test_poll(void *page, uint64_t out[128])  -- out receives 1024 bytes:
//   [0..30] x0-x30, [31] nzcv, [32..33] v0, [34..35] v7, [36..37] v16, [38..39] v31, [112..127] red zone
#if defined(__APPLE__)
#  define SYM(x) _##x
#else
#  define SYM(x) x
#endif
.text
.balign 16
.globl SYM(sn_test_poll)
SYM(sn_test_poll):
    sub sp, sp, #112
    stp x29, x30, [sp, #0]
    stp x19, x20, [sp, #16]
    stp x21, x22, [sp, #32]
    stp x23, x24, [sp, #48]
    stp x25, x26, [sp, #64]
    stp x27, x28, [sp, #80]
    str x1, [sp, #96]
    // red zone: 16 qwords below sp
    movz x9, #0xbeef
    movk x9, #0xdead, lsl #16
    movk x9, #0x5a5a, lsl #32
    movk x9, #0x1234, lsl #48
    mov x10, sp
    mov x11, #16
1:  sub x10, x10, #8
    str x9, [x10]
    subs x11, x11, #1
    b.ne 1b
    // vector registers
    movz x9, #0x1111, lsl #48
    dup v0.2d, x9
    movz x9, #0x7777, lsl #48
    dup v7.2d, x9
    movz x9, #0x1616, lsl #48
    dup v16.2d, x9
    movz x9, #0x3131, lsl #48
    dup v31.2d, x9
    // x0..x27, x29, x30 get 0xa000+n (x28 = page, used for the poll)
    mov x28, x0
    .irp n,0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,29,30
    mov x\n, #(0xa000+\n)
    .endr
    // flags: Z and C set
    movz x9, #0x6000, lsl #16
    msr nzcv, x9
    mov x9, #(0xa000+9)
    ldr xzr, [x28]          // the poll
    // dump
    sub sp, sp, #1024
    stp x0, x1, [sp, #0]
    stp x2, x3, [sp, #16]
    stp x4, x5, [sp, #32]
    stp x6, x7, [sp, #48]
    stp x8, x9, [sp, #64]
    stp x10, x11, [sp, #80]
    stp x12, x13, [sp, #96]
    stp x14, x15, [sp, #112]
    stp x16, x17, [sp, #128]
    stp x18, x19, [sp, #144]
    stp x20, x21, [sp, #160]
    stp x22, x23, [sp, #176]
    stp x24, x25, [sp, #192]
    stp x26, x27, [sp, #208]
    stp x28, x29, [sp, #224]
    str x30, [sp, #240]
    mrs x0, nzcv
    str x0, [sp, #248]
    str q0, [sp, #256]
    str q7, [sp, #272]
    str q16, [sp, #288]
    str q31, [sp, #304]
    ldr x9, [sp, #(1024+96)]
    mov x10, sp
    mov x11, #128
2:  ldr x12, [x10], #8
    str x12, [x9], #8
    subs x11, x11, #1
    b.ne 2b
    add sp, sp, #1024
    ldp x29, x30, [sp, #0]
    ldp x19, x20, [sp, #16]
    ldp x21, x22, [sp, #32]
    ldp x23, x24, [sp, #48]
    ldp x25, x26, [sp, #64]
    ldp x27, x28, [sp, #80]
    add sp, sp, #112
    mov x0, #0
    ret
