# Freestanding mem* for the Hutt core (RV64I — NO compressed/C extension).
#
# The riscv64 toolchain's newlib is built for rv64imafdc, so its memcpy/memset/
# memmove contain RVC (2-byte) instructions.  Hutt fetches fixed 4-byte words and
# does not decode compressed instructions (Hutt.scala: "No compressed
# instructions"), so calling the libc versions executes garbage and hangs (e.g.
# the first struct copy in record_draw_call).  These hand-written byte-loop
# versions override libc and use only base RV64I — assembled without .option rvc
# so no compressed encodings are emitted.  Each returns dst in a0 (a0 preserved;
# t0 walks).

.section .text

# void *memcpy(void *dst=a0, const void *src=a1, size_t n=a2)
.globl memcpy
memcpy:
    mv   t0, a0
    beqz a2, .Lcpy_done
.Lcpy_loop:
    lb   t1, 0(a1)
    sb   t1, 0(t0)
    addi a1, a1, 1
    addi t0, t0, 1
    addi a2, a2, -1
    bnez a2, .Lcpy_loop
.Lcpy_done:
    ret

# void *memset(void *dst=a0, int c=a1, size_t n=a2)
.globl memset
memset:
    mv   t0, a0
    beqz a2, .Lset_done
.Lset_loop:
    sb   a1, 0(t0)
    addi t0, t0, 1
    addi a2, a2, -1
    bnez a2, .Lset_loop
.Lset_done:
    ret

# void *memmove(void *dst=a0, const void *src=a1, size_t n=a2)
.globl memmove
memmove:
    beqz a2, .Lmv_done
    bgeu a1, a0, .Lmv_fwd        # src >= dst → forward copy is safe
    # overlapping with src < dst → copy backward
    add  t0, a0, a2
    add  a1, a1, a2
.Lmv_back:
    addi a1, a1, -1
    addi t0, t0, -1
    lb   t1, 0(a1)
    sb   t1, 0(t0)
    addi a2, a2, -1
    bnez a2, .Lmv_back
    ret
.Lmv_fwd:
    mv   t0, a0
.Lmv_fwd_loop:
    lb   t1, 0(a1)
    sb   t1, 0(t0)
    addi a1, a1, 1
    addi t0, t0, 1
    addi a2, a2, -1
    bnez a2, .Lmv_fwd_loop
.Lmv_done:
    ret
