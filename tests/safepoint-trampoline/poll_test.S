/* int64 sn_test_poll(void *trapPage, int misalign): runs a poll against the armed trap page with
 * known register/flags/red-zone contents and records what it sees afterwards in `sn_res`. */
.text
.globl sn_test_poll
sn_test_poll:
    pushq %rbp
    pushq %rbx
    pushq %r12
    pushq %r13
    pushq %r14
    pushq %r15
    movq %rsp, %rax
    movq %rax, saved_rsp(%rip)
    /* optional misalignment of rsp */
    testl %esi, %esi
    jz 1f
    subq $8, %rsp
1:
    movq %rsp, saved_rsp2(%rip)
    /* fill red zone [rsp-128, rsp) with a pattern */
    xorl %ecx, %ecx
2:  movq $0x5a5a000000000000, %rax
    orq %rcx, %rax
    movq %rax, -128(%rsp,%rcx,8)
    incq %rcx
    cmpq $16, %rcx
    jne 2b
    /* xmm patterns */
    movq $0x1111111111111111, %rax
    movq %rax, %xmm0
    movq $0x2222222222222222, %rax
    movq %rax, %xmm7
    movq $0x3333333333333333, %rax
    movq %rax, %xmm15
    /* GPR patterns */
    movq $0xb1b1b1b1b1b1b1b1, %rbx
    movq $0xc2c2c2c2c2c2c2c2, %rcx
    movq $0xd3d3d3d3d3d3d3d3, %rdx
    movq $0xe4e4e4e4e4e4e4e4, %rsi
    movq $0xf5f5f5f5f5f5f5f5, %r8
    movq $0xa6a6a6a6a6a6a6a6, %r9
    movq $0xa7a7a7a7a7a7a7a7, %r10
    movq $0xa8a8a8a8a8a8a8a8, %r11
    movq $0xa9a9a9a9a9a9a9a9, %r12
    movq $0xaaaaaaaaaaaaaaaa, %r13
    movq $0xabababababababab, %r14
    movq $0xacacacacacacacac, %r15
    movq $0xadadadadadadadad, %rbp
    /* flags: ZF=1, CF=1 (cmp equal then stc) , SF=0 */
    cmpq %rbx, %rbx
    stc
    /* the poll: plain load from the armed trap page (page ptr stays in rdi) */
    movl (%rdi), %eax
    /* ---- after resume ---- */
    lahf
    movb %ah, sn_res_flags(%rip)
    movq %rbx, sn_res_regs+0(%rip)
    movq %rcx, sn_res_regs+8(%rip)
    movq %rdx, sn_res_regs+16(%rip)
    movq %rsi, sn_res_regs+24(%rip)
    movq %rdi, sn_res_regs+32(%rip)
    movq %r8,  sn_res_regs+40(%rip)
    movq %r9,  sn_res_regs+48(%rip)
    movq %r10, sn_res_regs+56(%rip)
    movq %r11, sn_res_regs+64(%rip)
    movq %r12, sn_res_regs+72(%rip)
    movq %r13, sn_res_regs+80(%rip)
    movq %r14, sn_res_regs+88(%rip)
    movq %r15, sn_res_regs+96(%rip)
    movq %rbp, sn_res_regs+104(%rip)
    movq %xmm0, %rax
    movq %rax, sn_res_xmm+0(%rip)
    movq %xmm7, %rax
    movq %rax, sn_res_xmm+8(%rip)
    movq %xmm15, %rax
    movq %rax, sn_res_xmm+16(%rip)
    /* red zone: count mismatches */
    xorl %ecx, %ecx
    xorl %edx, %edx
3:  movq $0x5a5a000000000000, %rax
    orq %rcx, %rax
    cmpq %rax, -128(%rsp,%rcx,8)
    je 4f
    incq %rdx
4:  incq %rcx
    cmpq $16, %rcx
    jne 3b
    movq %rdx, sn_res_redzone_bad(%rip)
    /* rsp must be unchanged */
    movq %rsp, %rax
    movq saved_rsp2(%rip), %rcx
    subq %rax, %rcx
    movq %rcx, sn_res_rsp_delta(%rip)
    movq saved_rsp(%rip), %rsp
    popq %r15
    popq %r14
    popq %r13
    popq %r12
    popq %rbx
    popq %rbp
    ret
.data
saved_rsp: .quad 0
saved_rsp2: .quad 0
.globl sn_res_regs, sn_res_xmm, sn_res_flags, sn_res_redzone_bad, sn_res_rsp_delta
sn_res_regs: .space 120
sn_res_xmm: .space 24
sn_res_flags: .quad 0
sn_res_redzone_bad: .quad 0
sn_res_rsp_delta: .quad 0
.section .note.GNU-stack,"",@progbits
