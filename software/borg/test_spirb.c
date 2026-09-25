// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// Unit tests for spirb_parse().

#include "borg_spirb.h"
#include "borg_isa.h"
#include "compiler/shader_blobs.h"
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
    // const_reg=0x07, const_val=0x3F800000 (FP32 1.0, u32 little-endian)
    uint8_t blob[] = { 0, 0, 0, 0, 1, 0, 0x07, 0x00, 0x00, 0x80, 0x3F };
    spirb_shader_t s;
    int n = spirb_parse(blob, &s);
    CHECK(n == (int)sizeof(blob), "one const: byte count");
    CHECK(s.num_consts == 1,          "one const: num_consts");
    CHECK(s.const_regs[0] == 0x07,   "one const: const_reg");
    CHECK(s.const_vals[0] == 0x3F800000u, "one const: const_val FP32 1.0");
}

// Empty blob (zero everything) → returns 6 (header size only).
static void test_empty_blob(void) {
    uint8_t blob[6] = { 0, 0, 0, 0, 0, 0 };
    spirb_shader_t s;
    int n = spirb_parse(blob, &s);
    CHECK(n == 6, "empty blob: returns header size 6");
    CHECK(s.num_instrs == 0, "empty blob: num_instrs==0");
}

// ---------------------------------------------------------------------------
// The baked shader blobs still decode as current-ISA instructions.
//
// compiler/shader_blobs.h is a checked-in binary artifact: vert_borg and
// frag_borg are borgc output (`make -C compiler regen`), rasterize_borg is
// hand-written ISA. Checked in, they can go stale: the blobs encode opcodes,
// and if one is retired or moves they would silently execute DIFFERENT
// instructions -- no load failure, no error, just a wrong image, in the
// standalone path that has no driver to override them.
//
// This is the guard. It cannot check the blobs still compute the right thing,
// but it does catch the whole class of "the ISA moved out from under the
// blob" -- which is how the FTEX retirement showed up.
//
// The valid opcode set is built by invoking each borg_isa.h macro with zero
// operands, so it tracks that header automatically rather than being a second
// hand-maintained list that could drift from it.
// ---------------------------------------------------------------------------

static int isa_base_is_known(uint32_t word) {
    // R4-type ops (FMADD, TEX, TEXA) are discriminated by opcode bit 2 and a
    // funct2 at 26:25, not funct7 -- their rs3 field occupies the bits a
    // funct7 comparison would look at, so they are tested first. funct2 1 was
    // FTEX and is retired.
    if ((word >> 2) & 1u) {
      uint32_t f2 = (word >> 25) & 3u;
      return f2 == ((BORG_INSTR_FMADD(0, 0, 0, 0, 0) >> 25) & 3u) ||
             f2 == ((BORG_INSTR_TEX(0, 0, 0, 0, 0) >> 25) & 3u) ||
             f2 == ((BORG_INSTR_TEXA(0, 0, 0, 0) >> 25) & 3u);
    }

    static const uint32_t bases[] = {
        BORG_INSTR_FADD(0, 0, 0, 0),  BORG_INSTR_FMUL(0, 0, 0, 0),
        BORG_INSTR_FNEG(0, 0, 0),     BORG_INSTR_FSTEP(0, 0, 0),
        BORG_INSTR_FRCP(0, 0, 0),
        BORG_INSTR_IADD(0, 0, 0, 0),  BORG_INSTR_ISHL(0, 0, 0, 0),
        BORG_INSTR_ISHR(0, 0, 0, 0),  BORG_INSTR_IMUL(0, 0, 0, 0),
        BORG_INSTR_I2F(0, 0, 0),      BORG_INSTR_F2I(0, 0, 0),
        BORG_INSTR_FRSQ(0, 0, 0),     BORG_INSTR_FSRGB(0, 0, 0),
        BORG_INSTR_DDX(0, 0, 0),      BORG_INSTR_DDY(0, 0, 0),
        BORG_INSTR_LOAD(0, 0, 0),     BORG_INSTR_STORE(0, 0, 0),
        BORG_INSTR_BRZ(0, 0, 0),      BORG_INSTR_BRNZ(0, 0, 0),
        BORG_INSTR_EXPUSH(0, 0),      BORG_INSTR_EXELSE(0),
        BORG_INSTR_EXPOP(0),
    };
    uint32_t f7 = word & 0xFE000000u;
    if (f7 == 0 && word == BORG_INSTR_HALT) return 1;   // halt
    for (unsigned i = 0; i < sizeof(bases) / sizeof(bases[0]); i++)
        if ((bases[i] & 0xFE000000u) == f7) return 1;
    return 0;
}

static void check_blob_opcodes(const char *name, const unsigned char *blob,
                               unsigned len) {
    spirb_shader_t s;
    int n = spirb_parse((uint8_t *)blob, &s);
    char msg[160];
    // Report the numbers on failure: "parses" alone would not say whether the
    // parser overran the array or the array outgrew the parser, and those have
    // opposite fixes.
    snprintf(msg, sizeof msg, "%s: parse consumed %d of %u declared bytes",
             name, n, len);
    CHECK(n > 0 && (unsigned)n <= (int)len, msg);
    if (n <= 0) return;

    int bad = -1;
    for (unsigned i = 0; i < s.num_instrs; i++)
        if (!isa_base_is_known(s.instrs[i])) { bad = (int)i; break; }

    if (bad >= 0)
        snprintf(msg, sizeof msg, "%s: instr %d = 0x%08X is not a current opcode",
                 name, bad, s.instrs[bad]);
    else
        snprintf(msg, sizeof msg, "%s: all %u instrs decode as current ISA",
                 name, s.num_instrs);
    CHECK(bad < 0, msg);
}

static void test_baked_blobs_match_current_isa(void) {
    check_blob_opcodes("vert_borg", vert_borg, vert_borg_len);
    check_blob_opcodes("rasterize_borg", rasterize_borg, rasterize_borg_len);
    check_blob_opcodes("frag_borg", frag_borg, frag_borg_len);
}

int main(void) {
    printf("test_spirb\n");
    test_baked_blobs_match_current_isa();
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
