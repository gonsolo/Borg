#pragma once

#include <stdint.h>
#include <stdbool.h>

#define PERI_IDX_GPIO 1

#define PERI_BASE_ADDRESS(PERI_IDX) ((PERI_IDX) < 16 ? 0x8000000 + 0x40 * (PERI_IDX) : 0x8000300 + 0x10 * (PERI_IDX))

// Set the output debug select register, each bit selects the debug mode for that output.
// Low is debug mode, high is normal mode.  Set to 0xff to make all outputs available.
inline static void set_debug_sel(uint32_t value)
{
    asm volatile ( "sw %[value], 0xc(tp)" : : [value] "r" (value));
}

// Read the output debug select register
inline static uint32_t get_debug_sel()
{
    uint32_t value;
    asm volatile ( "lw %[value], 0xc(tp)" : [value] "=r" (value));
    return value;
}

// Set register debug.  When enabled, out2-out5 indicate the value being written to the register 
// file by the currently executing instruction
inline static void set_register_debug(bool enable)
{
    asm volatile ( "sw %[value], 0x30(tp)" : : [value] "r" (enable ? 1 : 0));
}

// Convenience methods to disable debug on all outputs
inline static void enable_all_outputs()
{
    set_register_debug(false);
    set_debug_sel(0xff);
}

// Select the peripheral in control of an output.
inline static void set_gpio_func(uint32_t gpio, uint32_t peri_idx)
{
    volatile uint32_t* gpio_func_ptr = (volatile uint32_t*)0x8000060 + gpio;
    *gpio_func_ptr = peri_idx;
}

// Read the peripheral in control of an output.
inline static uint32_t get_gpio_func(uint32_t gpio)
{
    volatile uint32_t* gpio_func_ptr = (volatile uint32_t*)0x8000060 + gpio;
    return *gpio_func_ptr;
}

// Set the level of all outputs from the GPIO peripheral.  This is only
// reflected on output pins for outputs set to function 1 (GPIO).
inline static void set_outputs(uint32_t value)
{
    asm volatile ( "sw %[value], 0x40(tp)" : : [value] "r" (value));
}

// Get the level of all outputs being requested from the GPIO peripheral.
inline static uint32_t get_outputs()
{
    uint32_t value;
    asm volatile ( "lw %[value], 0x40(tp)" : [value] "=r" (value));
    return value;
}

// Set a given output high, as requested by the GPIO peripheral.
inline static void gpio_on(int gpio)
{
    uint32_t val = get_outputs() | (1 << gpio);
    set_outputs(val);
}

// Set a given output low, as requested by the GPIO peripheral.
inline static void gpio_off(int gpio)
{
    uint32_t val = get_outputs() & (~(1 << gpio));
    set_outputs(val);
}

// Get the levels of the input pins.
inline static uint32_t get_inputs()
{
    uint32_t value;
    asm volatile ( "lw %[value], 0x44(tp)" : [value] "=r" (value));
    return value;
}

// Set each output to GPIO or debug mode according to whether the corresponding bit is set.
inline static void set_gpio_sel(uint32_t value) {
    set_debug_sel(value);
    for (uint32_t i = 0; i < 8; ++i) {
        set_gpio_func(i, value & 1);
        value >>= 1;
    }
}
