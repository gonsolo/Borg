/* Task #15 follow-up: cheap steady-state syscall-latency sanity check.
 * Measures getpid() (cheapest possible syscall entry/exit) and
 * mmap()+touch+munmap() (closer to a GEM-buffer-mapping-shaped cost) on an
 * ALREADY-RUNNING process -- i.e. NOT execve()'s address-space-teardown
 * work, which is the thing we already know is slow. Uses the `rdtime`
 * pseudo-instruction directly (reads the `time` CSR, backed by CLINT mtime
 * at 25MHz) instead of clock_gettime(), to avoid depending on VDSO/libc
 * clock plumbing working correctly on this minimal target.
 */
#include <stdio.h>
#include <sys/mman.h>
#include <unistd.h>

static inline unsigned long rdtime(void) {
    unsigned long t;
    __asm__ volatile("rdtime %0" : "=r"(t));
    return t;
}

int main(void) {
    const int N = 20;
    unsigned long t0, t1;

    printf("BENCH: starting, %d iterations each test\n", N);
    fflush(stdout);

    t0 = rdtime();
    for (int i = 0; i < N; i++) {
        getpid();
    }
    t1 = rdtime();
    printf("BENCH: getpid x%d took %lu cycles (%lu cycles/call, %lu ns/call)\n",
           N, t1 - t0, (t1 - t0) / N, ((t1 - t0) / N) * 40);
    fflush(stdout);

    t0 = rdtime();
    for (int i = 0; i < N; i++) {
        void *p = mmap(NULL, 4096, PROT_READ | PROT_WRITE,
                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (p == MAP_FAILED) {
            printf("BENCH: mmap failed at iter %d\n", i);
            fflush(stdout);
            break;
        }
        *(volatile char *)p = 1;
        munmap(p, 4096);
    }
    t1 = rdtime();
    printf("BENCH: mmap+touch+munmap x%d took %lu cycles (%lu cycles/call, %lu ns/call)\n",
           N, t1 - t0, (t1 - t0) / N, ((t1 - t0) / N) * 40);
    fflush(stdout);

    printf("BENCH: done\n");
    fflush(stdout);
    return 0;
}
