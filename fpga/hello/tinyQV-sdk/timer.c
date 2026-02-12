#include <stdlib.h>
#include "timer.h"
#include "mul.h"

inline static void set_mtime(uint32_t value)
{
    asm volatile ( "sw %[value], -0x100(x0)" : : [value] "r" (value));
}

inline static void set_mtimecmp(uint32_t value)
{
    asm volatile ( "sw %[value], -0xfc(x0)" : : [value] "r" (value));
}

inline static uint32_t get_mtimecmp()
{
    uint32_t value;
    asm volatile ( "lw %[value], -0xfc(x0)" : [value] "=r" (value));
    return value;
}

inline static void enable_timer_interrupt()
{
    asm volatile ("csrs mie, %[value]" : : [value] "r" (0x80));
}

inline static void disable_timer_interrupt()
{
    asm volatile ("csrc mie, %[value]" : : [value] "r" (0x80));
}


static alarm_callback_t alarm_callback;
static void* alarm_handle;

void set_alarm(int ms_delay, alarm_callback_t callback, void* handle)
{
    uint32_t alarm_time = get_mtime() + mul32x16(ms_delay, 1000);
    disable_timer_interrupt();
    alarm_callback = callback;
    alarm_handle = handle;
    set_mtimecmp(alarm_time);
    enable_timer_interrupt();
}

void tqv_timer_interrupt(void)
{
    disable_timer_interrupt();
    if (alarm_callback) {
        alarm_callback_t the_alarm = alarm_callback;

        // Clear the callback before calling back, so the callback can set a new callback.
        alarm_callback = NULL;    
        
        the_alarm(alarm_handle);
    }
}
