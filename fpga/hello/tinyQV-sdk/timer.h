#pragma once

#include <stdint.h>

inline static uint32_t get_mtime()
{
    uint32_t value;
    asm( "lw %[value], -0x100(x0)" : [value] "=r" (value) : : "memory");
    return value;
}

// Add a callback to be made after a certain amount of time.
// If there is an existing alarm set, then it is replaced.
typedef void(*alarm_callback_t)(void*);
void set_alarm(int ms_delay, alarm_callback_t callback, void* handle);
