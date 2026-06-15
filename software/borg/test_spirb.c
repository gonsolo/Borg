// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Unit tests for spirb_parse().

#include "borg_spirb.h"
#include <stdio.h>
#include <stdint.h>
#include <string.h>

static int tests_run = 0;
static int tests_passed = 0;

#define CHECK(cond, name) do { \
    tests_run++; \
    if (cond) { tests_passed++; printf("  PASS  %s\n", name); } \
    else { printf("  FAIL  %s (line %d)\n", name, __LINE__); } \
} while(0)

// Minimal valid SPIR-B blob: 2 instructions, no uniforms/attrs/outputs/consts.
static void test_valid_blob(void) {
    // Header: num_instrs=2, num_uniforms=0, num_attrs=0, num_outputs=0, num_consts=0, reserved=0
    // Instr0: 0x12345678, Instr1: 0xABCD1234
    uint8_t blob[] = {
        2, 0, 0, 0, 0, 0,
        0x78, 0x56, 0x34, 0x12,
        0x34, 0x12, 0xCD, 0xAB,
    };
    spirb_shader_t s;
    int n = spirb_parse(blob, &s);
    CHECK(n == (int)sizeof(blob), "valid: byte count");
    CHECK(s.num_instrs == 2,      "valid: num_instrs");
    CHECK(s.instrs[0] == 0x12345678U, "valid: instr0");
    CHECK(s.instrs[1] == 0xABCD1234U, "valid: instr1");
}

// NULL blob → returns -1 without crashing.
static void test_null_blob(void) {
    spirb_shader_t s;
    int n = spirb_parse(NULL, &s);
    CHECK(n == -1, "null blob: returns -1");
}

// NULL output struct → returns -1 without crashing.
static void test_null_struct(void) {
    uint8_t blob[] = { 0, 0, 0, 0, 0, 0 };
    int n = spirb_parse(blob, NULL);
    CHECK(n == -1, "null struct: returns -1");
}

// num_instrs exceeds SPIRB_MAX_INSTRS → returns -1.
static void test_too_many_instrs(void) {
    uint8_t blob[6] = { SPIRB_MAX_INSTRS + 1, 0, 0, 0, 0, 0 };
    spirb_shader_t s;
    int n = spirb_parse(blob, &s);
    CHECK(n == -1, "too many instrs: returns -1");
}

// num_uniforms exceeds SPIRB_MAX_REGS → returns -1.
static void test_too_many_uniforms(void) {
    uint8_t blob[6] = { 0, SPIRB_MAX_REGS + 1, 0, 0, 0, 0 };
    spirb_shader_t s;
    int n = spirb_parse(blob, &s);
    CHECK(n == -1, "too many uniforms: returns -1");
}

// num_consts exceeds SPIRB_MAX_REGS → returns -1.
static void test_too_many_consts(void) {
    uint8_t blob[6] = { 0, 0, 0, 0, SPIRB_MAX_REGS + 1, 0 };
    spirb_shader_t s;
    int n = spirb_parse(blob, &s);
    CHECK(n == -1, "too many consts: returns -1");
}

// Blob with 1 const: check const_reg and const_val are read correctly.
static void test_one_const(void) {
    // Header: instrs=0, uniforms=0, attrs=0, outputs=0, consts=1, reserved=0
    // const_reg=0x07, const_val=0x3C00 (FP16 1.0)
    uint8_t blob[] = { 0, 0, 0, 0, 1, 0, 0x07, 0x00, 0x3C };
    spirb_shader_t s;
    int n = spirb_parse(blob, &s);
    CHECK(n == (int)sizeof(blob), "one const: byte count");
    CHECK(s.num_consts == 1,          "one const: num_consts");
    CHECK(s.const_regs[0] == 0x07,   "one const: const_reg");
    CHECK(s.const_vals[0] == 0x3C00, "one const: const_val FP16 1.0");
}

// Empty blob (zero everything) → returns 6 (header size only).
static void test_empty_blob(void) {
    uint8_t blob[6] = { 0, 0, 0, 0, 0, 0 };
    spirb_shader_t s;
    int n = spirb_parse(blob, &s);
    CHECK(n == 6, "empty blob: returns header size 6");
    CHECK(s.num_instrs == 0, "empty blob: num_instrs==0");
}

int main(void) {
    printf("test_spirb\n");
    test_valid_blob();
    test_null_blob();
    test_null_struct();
    test_too_many_instrs();
    test_too_many_uniforms();
    test_too_many_consts();
    test_one_const();
    test_empty_blob();
    printf("%d/%d passed\n", tests_passed, tests_run);
    return tests_passed == tests_run ? 0 : 1;
}
