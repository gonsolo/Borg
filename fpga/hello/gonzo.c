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

// All strings in .data (RAM) to avoid flash data reads
static char str_banner[] = "--- Borg FP16 Test ---\r\n";
static char str_plus[]   = " + ";
static char str_eq[]     = " = ";
static char str_pass[]   = " [PASS]\r\n";
static char str_fail[]   = " [FAIL]\r\n";
static char str_ok[]     = "\r\nAll Passed!\r\n";
static char str_bad[]    = "\r\nFAILED\r\n";

// All helpers as macros - function calls produce broken codegen on this target
#define gonzo_puts(s) do { char *_p = (s); while (*_p) gonzo_putc(*_p++); } while(0)

#define print_nib(n) do { unsigned int _n = (n) & 0xF; gonzo_putc(_n < 10 ? '0' + _n : 'A' + _n - 10); } while(0)

#define print_hex16(v) do { unsigned int _v = (v); print_nib(_v >> 12); print_nib(_v >> 8); print_nib(_v >> 4); print_nib(_v); } while(0)

int main() {
  // Debug: mark entry into main
  gonzo_putc('1');

  for (volatile int i = 0; i < 10000; i++) ;

  // Debug: delay done
  gonzo_putc('2');

  REG_WRITE(UART_BAUD, 34);

  // Debug: baud set
  gonzo_putc('3');

  gonzo_puts(str_banner);

  // Debug: banner done
  gonzo_putc('4');

  int passed = 0;
  for (int t = 0; t < NUM_TESTS; t++) {
    // Debug: test start
    gonzo_putc('A' + t);

    unsigned int a = test_pairs_i[t][0];
    unsigned int b = test_pairs_i[t][1];
    unsigned int exp = test_pairs_i[t][2];

    // Debug: loaded data
    gonzo_putc('a');

    REG_WRITE(BORG_ADDR_REGS + 0, a);
    REG_WRITE(BORG_ADDR_REGS + 4, b);

    // Debug: regs written
    gonzo_putc('b');

    REG_WRITE(BORG_ADDR_IMEM + 0, 0x0100);
    REG_WRITE(BORG_ADDR_IMEM + 4, 0x0000);

    // Debug: imem written
    gonzo_putc('c');

    REG_WRITE(BORG_ADDR_CONTROL, 2);
    REG_WRITE(BORG_ADDR_CONTROL, 1);

    // Debug: started
    gonzo_putc('d');

    int timeout = 100000;
    while (!(REG_READ(BORG_ADDR_STATUS) & 2) && timeout > 0) {
      timeout--;
    }

    // Debug: halt or timeout
    gonzo_putc('e');

    unsigned int res = REG_READ(BORG_ADDR_REGS + 8) & 0xFFFF;

    // Debug: result read
    gonzo_putc('f');

    gonzo_putc('g');
    print_hex16(a);
    gonzo_putc('h');
    gonzo_puts(str_plus);
    gonzo_putc('i');
    print_hex16(b);
    gonzo_putc('j');
    gonzo_puts(str_eq);
    gonzo_putc('k');
    print_hex16(res);
    gonzo_putc('l');

    if (res == (exp & 0xFFFF)) {
      gonzo_puts(str_pass);
      passed++;
    } else {
      gonzo_puts(str_fail);
    }
  }

  // Debug: all tests done
  gonzo_putc('Z');

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
