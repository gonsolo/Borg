#include "test_data.h"

#define UART_TX      0x08000080
#define UART_STATUS  0x08000084
#define UART_BAUD    0x08000088

#define BORG_ADDR_BASE    0x080005C0
#define BORG_ADDR_REGS    (BORG_ADDR_BASE + 0)
#define BORG_ADDR_STATUS  (BORG_ADDR_BASE + 16)
#define BORG_ADDR_CONTROL (BORG_ADDR_BASE + 60)
#define BORG_ADDR_IMEM    (BORG_ADDR_BASE + 32)

#define REG_WRITE(addr, val) (*(volatile unsigned int *)(addr) = (val))
#define REG_READ(addr) (*(volatile unsigned int *)(addr))

void gonzo_putc(int c) {
  while (REG_READ(UART_STATUS) & 1) ;
  REG_WRITE(UART_TX, c);
}

// All helpers as macros — nested function calls corrupt state on TinyQV.
// Only gonzo_putc() works as a real function.
#define gonzo_puts(s) do { char *_p = (s); while (*_p) gonzo_putc(*_p++); } while(0)

#define print_nib(n) do { \
  unsigned int _n = (n) & 0xF; \
  gonzo_putc(_n < 10 ? '0' + _n : 'A' + _n - 10); \
} while(0)

#define print_hex16(v) do { \
  unsigned int _v = (v); \
  print_nib(_v >> 12); print_nib(_v >> 8); \
  print_nib(_v >> 4);  print_nib(_v); \
} while(0)

static char str_banner[] = "--- Borg FP16 Addition Test ---\r\n";
static char str_plus[]   = " + ";
static char str_eq[]     = " = ";
static char str_exp[]    = " exp ";
static char str_pass[]   = " [PASS]\r\n";
static char str_fail[]   = " [FAIL]\r\n";
static char str_ok[]     = "\r\nAll Passed!\r\n";
static char str_bad[]    = "\r\nFAILED\r\n";
static char str_lparen[] = " (";
static char str_rparen[] = ")";

int main() {
  for (volatile int i = 0; i < 10000; i++) ;
  REG_WRITE(UART_BAUD, 34);

  gonzo_puts(str_banner);

  int passed = 0;
  for (int t = 0; t < NUM_TESTS; t++) {
    unsigned int a = test_pairs_i[t][0];
    unsigned int b = test_pairs_i[t][1];
    unsigned int exp = test_pairs_i[t][2];

    REG_WRITE(BORG_ADDR_REGS + 0, a);
    REG_WRITE(BORG_ADDR_REGS + 4, b);
    REG_WRITE(BORG_ADDR_IMEM + 0, 0x0100);
    REG_WRITE(BORG_ADDR_IMEM + 4, 0x0000);
    REG_WRITE(BORG_ADDR_CONTROL, 2);
    REG_WRITE(BORG_ADDR_CONTROL, 1);

    int timeout = 100000;
    while (!(REG_READ(BORG_ADDR_STATUS) & 2) && timeout > 0) {
      timeout--;
    }

    unsigned int res = REG_READ(BORG_ADDR_REGS + 8) & 0xFFFF;

    // Print: 666.5 + 666.5 = 1333.0 (6135+6135=6535 exp 6535) [PASS]
    gonzo_puts(test_desc[t][0]);
    gonzo_puts(str_plus);
    gonzo_puts(test_desc[t][1]);
    gonzo_puts(str_eq);
    gonzo_puts(test_desc[t][2]);
    gonzo_puts(str_lparen);
    print_hex16(a);
    gonzo_putc('+');
    print_hex16(b);
    gonzo_putc('=');
    print_hex16(res);
    gonzo_puts(str_exp);
    print_hex16(exp);
    gonzo_puts(str_rparen);

    if (res == (exp & 0xFFFF)) {
      gonzo_puts(str_pass);
      passed++;
    } else {
      gonzo_puts(str_fail);
    }
  }

  if (passed == NUM_TESTS) {
    gonzo_puts(str_ok);
  } else {
    gonzo_puts(str_bad);
  }

  while(1) {
    gonzo_putc('.');
    for (volatile int i = 0; i < 500000; i++) ;
  }
  return 0;
}
