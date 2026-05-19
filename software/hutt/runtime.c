// Much of this borrowed from the Pico SDK
// Copyright (c) 2020 Raspberry Pi (Trading) Ltd.

#include <sys/time.h>
#include <stdio.h>
#include <sys/stat.h>
#include "uart.h"

extern char __HeapLimit; /* Set by linker.  */
extern char __HeapStart; /* Set by linker.  */
char* __heap_end = &__HeapStart;

extern uint32_t __bss_start__;
extern uint32_t __bss_end__;
extern uint32_t __data_start__;
extern uint32_t __data_end__;
extern uint32_t __data_lma;
extern uint32_t __peri_data_start__;
extern uint32_t __peri_data_end__;
extern uint32_t __peri_lma;

//int __errno;

void __attribute__((section(".early_text"))) __runtime_init(void) {
    for (uint32_t* ptr = &__bss_start__; ptr < &__bss_end__; ) *ptr++ = 0;
    
    uint32_t* load_ptr = &__peri_lma;
    for (uint32_t* ptr = &__peri_data_start__; ptr < &__peri_data_end__; ) *ptr++ = *load_ptr++;

    load_ptr = &__data_lma;
    for (uint32_t* ptr = &__data_start__; ptr < &__data_end__; ) *ptr++ = *load_ptr++;
}

void *_sbrk(int incr) {
    char *prev_heap_end;

    prev_heap_end = __heap_end;
    char *next_heap_end = __heap_end + incr;

    //uart_printf("SBRK: %p -> %p\r\n", __heap_end, next_heap_end);

    if (next_heap_end > (&__HeapLimit)) {
        return (char *) -1;
    }

    __heap_end = next_heap_end;
    return (void *) prev_heap_end;
}

int _gettimeofday (struct timeval *__restrict tv, void *__restrict tz) {
    return 0;
}

pid_t _getpid(void) {
    return 0;
}

int _kill(__unused pid_t pid, __unused int sig) {
    return -1;
}

void __attribute__((noreturn)) _exit(__unused int status) {
    while (1) {
        //__breakpoint();
    }
}

#define STDIO_HANDLE_STDIN  0
#define STDIO_HANDLE_STDOUT 1
#define STDIO_HANDLE_STDERR 2

int _read(int handle, char *buffer, int length) {
    if (handle == STDIO_HANDLE_STDIN) {
        for (int i = 0; i < length; ++i) {
            while (!uart_is_char_available())
                ;
            *buffer++ = uart_getc();
        }
        return length;
    }
    return -1;
}

int _write(int handle, char *buffer, int length) {
    if (handle == STDIO_HANDLE_STDOUT) {
        uart_put_buffer(buffer, length);
        return length;
    }
    else if (handle == STDIO_HANDLE_STDERR) {
        debug_uart_put_buffer(buffer, length);
        return length;
    }
    return -1;
}

int _open(__unused const char *fn, __unused int oflag, ...) {
    return -1;
}

int _close(__unused int fd) {
    return -1;
}

off_t _lseek(__unused int fd, __unused off_t pos, __unused int whence) {
    return -1;
}

int __attribute__((weak)) _fstat(__unused int fd, __unused struct stat *buf) {
    return -1;
}

int __attribute__((weak)) _isatty(int fd) {
    return fd == STDIO_HANDLE_STDIN || fd == STDIO_HANDLE_STDOUT || fd == STDIO_HANDLE_STDERR;
}
