#include "test_data.h"
#include <csr.h>
#include <uart.h>
#define printf uart_printf

// Override libgcc's __mulsi3 which uses the removed 'mul16' hardware
// instruction
unsigned int __mulsi3(unsigned int a, unsigned int b) {
  unsigned int res = 0;
  while (a != 0) {
    if (a & 1)
      res += b;
    a >>= 1;
    b <<= 1;
  }
  return res;
}

// Read cycle is already inline defined by csr.h

// --- BORG HARDWARE DEFINITIONS ---
// Peripheral 23 base address: 0x08000000 (User Window) + 0x5C0 (Offset)
#define BORG_ADDR_BASE 0x080005C0
#define BORG_ADDR_REGS (BORG_ADDR_BASE + 0)
#define BORG_ADDR_STATUS (BORG_ADDR_BASE + 16)
#define BORG_ADDR_IMEM (BORG_ADDR_BASE + 32)
#define BORG_ADDR_CONTROL (BORG_ADDR_BASE + 60)

// Macros to perform the actual memory access
#define REG_WRITE(addr, val) (*(volatile unsigned int *)(addr) = (val))
#define REG_READ(addr) (*(volatile unsigned int *)(addr))

typedef union {
  float f;
  unsigned int i;
} float_bits;

int run_test_case(float a, float b) {
  float_bits fa, fb, fres;
  fa.f = a;
  fb.f = b;

  // 1. Reset PC and stop execution
  uart_printf("  [Debug] Resetting PC...\n");
  REG_WRITE(BORG_ADDR_CONTROL, 2); // Bit 1 = Reset PC
  for (volatile int i = 0; i < 100; i++)
    ; // Small delay

  // 2. Load operands into registers
  uart_printf("  [Debug] Loading Operands...\n");
  REG_WRITE(BORG_ADDR_REGS + 0, fa.i); // r0 = a
  REG_WRITE(BORG_ADDR_REGS + 4, fb.i); // r1 = b

  // 3. Load shader program into IMEM (ADD r2, r0, r1)
  uart_printf("  [Debug] Loading Program...\n");
  // rs1 = 0 (bits 19:15), rs2 = 1 (bits 24:20), rd = 2 (bits 11:7)
  // Float Add opcode (R-type format): opcode=0x53, funct3=0, funct7=0
  // instr = (rs2 << 20) | (rs1 << 15) | (0 << 12) | (rd << 7) | 0x53
  // instr = (1 << 20) | (0 << 15) | (2 << 7) | 0x53 = 0x00100153
  unsigned int instr_fadd = 0x00100153;
  unsigned int instr_halt = 0x00000000; // ALL ZEROS (Implicit Halt)
  REG_WRITE(BORG_ADDR_IMEM + 0, instr_fadd);
  REG_WRITE(BORG_ADDR_IMEM + 4, instr_halt);

  // 4. Start execution
  uart_printf("  [Debug] Starting Execution...\n");
  REG_WRITE(BORG_ADDR_CONTROL, 1); // Bit 0 = Start

  // 5. Wait for Halt with timeout
  uart_printf("  [Debug] Polling for Halt...\n");
  int timeout = 100000;
  while (!(REG_READ(BORG_ADDR_STATUS) & 2) && timeout > 0) {
    timeout--;
  }

  if (timeout <= 0) {
    uart_printf("  [Error] Hardware Hang! Timeout waiting for execution to "
                "halt.\n");
    return 0; // Fail
  }

  // 6. Stop execution
  uart_printf("  [Debug] Halting manually...\n");
  REG_WRITE(BORG_ADDR_CONTROL, 0);

  // 7. Read result
  uart_printf("  [Debug] Reading result...\n");
  fres.i = REG_READ(BORG_ADDR_REGS + 8); // Read r2

  float expected = a + b;
  float diff = fres.f - expected;
  if (diff < 0)
    diff = -diff;

  // 8. Formatting for UART output
  int aw = (int)a, af = (int)(a * 100.0f) % 100;
  int bw = (int)b, bf = (int)(b * 100.0f) % 100;
  int rw = (int)fres.f, rf = (int)(fres.f * 100.0f) % 100;
  int ew = (int)expected, ef = (int)(expected * 100.0f) % 100;

  if (af < 0)
    af = -af;
  if (bf < 0)
    bf = -bf;
  if (rf < 0)
    rf = -rf;
  if (ef < 0)
    ef = -ef;

  uart_printf(
      "Shader: %4d.%02d + %4d.%02d -> Actual: %4d.%02d (Exp: %4d.%02d)\n", aw,
      af, bw, bf, rw, rf, ew, ef);

  return (diff < 0.0001f);
}

int main() {
  printf("--- Starting Programmable Adder Batch ---\n");

  int all_passed = 1;
  for (int i = 0; i < NUM_TESTS; i++) {
    if (!run_test_case(test_pairs[i][0], test_pairs[i][1])) {
      all_passed = 0;
    }
  }

  if (all_passed) {
    printf("--- All Tests Passed ---\n");
    printf("%d, \033[32mSUCCESS\033[0m] TinyQV Borg Test Finished\n",
           read_cycle());
  } else {
    printf("--- TESTS FAILED ---\n");
    printf("%d, \033[31mFAILURE\033[0m] TinyQV Borg Test Finished\n",
           read_cycle());
  }

  return 0;
}
