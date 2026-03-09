#include "borg_math.h"

// --- Hardware addresses ---
#define UART_TX      0x08000080
#define UART_STATUS  0x08000084
#define UART_BAUD    0x08000088

#define BORG_BASE     0x080000C0
#define BORG_REG(n)   (BORG_BASE + (n)*4)     // Registers 0-7 at offsets 0,4,...,28
#define BORG_STATUS   (BORG_BASE + 60)          // Status/control shared at offset 60
#define BORG_IMEM(n)  (BORG_BASE + 32 + (n)*4) // IMEM 0-6 at offsets 32,36,...,56
#define BORG_CONTROL  (BORG_BASE + 60)          // Write=control, read=status

#define REG_WRITE(addr, val) (*(volatile unsigned int *)(addr) = (val))
#define REG_READ(addr) (*(volatile unsigned int *)(addr))

// --- UART output (macro-only, no nested calls on TinyQV) ---
void gonzo_putc(int c) {
  while (REG_READ(UART_STATUS) & 1) ;
  REG_WRITE(UART_TX, c);
}

#define gonzo_puts(s) do { char *_p = (s); while (*_p) gonzo_putc(*_p++); } while(0)

static char hex_chars[] = "0123456789abcdef";
#define print_hex16(v) do { \
    unsigned int _v = (v); \
    gonzo_putc('0'); gonzo_putc('x'); \
    gonzo_putc(hex_chars[(_v >> 12) & 0xF]); \
    gonzo_putc(hex_chars[(_v >> 8) & 0xF]); \
    gonzo_putc(hex_chars[(_v >> 4) & 0xF]); \
    gonzo_putc(hex_chars[_v & 0xF]); \
  } while(0)

// --- Borg shader program: 2D rotation ---
// Register allocation — FMA accum (rs3) must be r0-r3 (2-bit field):
//   r0 = rx result (fmul dest + fmadd accum)
//   r1 = ry result (fmul dest + fmadd accum)
//   r2 = cos(angle)
//   r3 = x
//   r4 = -sin(angle)
//   r5 = sin(angle)
//   r6 = y
//
// IMEM:
//   fmul  r0, r2, r3       // c*x           → r0
//   fmadd r0, r4, r6, r0   // -s*y + c*x    → rx
//   fmul  r1, r5, r3       // s*x           → r1
//   fmadd r1, r2, r6, r1   // c*y + s*x     → ry
//   halt

// FP16 encoded instructions
#define INSTR_FMUL_CX    0x2340   // fmul  r0, r2, r3
#define INSTR_FMADD_RX   0x4680   // fmadd r0, r4, r6, r0
#define INSTR_FMUL_SX    0x23A4   // fmul  r1, r5, r3
#define INSTR_FMADD_RY   0x4E44   // fmadd r1, r2, r6, r1

// --- String constants ---
static char str_banner[]  = "--- Borg Vertex Rotation Test ---\r\n";
static char str_angle[]   = "Angle: ";
static char str_vertex[]  = "Vertex: (";
static char str_comma[]   = ", ";
static char str_rparen[]  = ")";
static char str_sin[]     = "  sin=";
static char str_cos[]     = "  cos=";
static char str_neg[]     = " -sin=";
static char str_result[]  = "Result: (";
static char str_nl[]      = "\r\n";
static char str_done[]    = "\r\nDone!\r\n";

int main() {
  // Wait for UART to stabilize
  for (volatile int i = 0; i < 10000; i++) ;
  REG_WRITE(UART_BAUD, 34);

  gonzo_puts(str_banner);

  // --- Load the shader program into IMEM ---
  REG_WRITE(BORG_CONTROL, 2);  // Reset PC
  REG_WRITE(BORG_IMEM(0), INSTR_FMUL_CX);
  REG_WRITE(BORG_IMEM(1), INSTR_FMADD_RX);
  REG_WRITE(BORG_IMEM(2), INSTR_FMUL_SX);
  REG_WRITE(BORG_IMEM(3), INSTR_FMADD_RY);
  REG_WRITE(BORG_IMEM(4), 0x0000);  // halt

  // --- Test vertices ---
  struct { uint16_t angle, x, y; } tests[] = {
    { 0x0000, 0x3C00, 0x0000 },  // angle=0,   vertex=(1.0, 0.0) → expect (1.0, 0.0)
    { 0x3E48, 0x3C00, 0x0000 },  // angle=π/2, vertex=(1.0, 0.0) → expect (0.0, 1.0)
    { 0x3A48, 0x3C00, 0x3C00 },  // angle=π/4, vertex=(1.0, 1.0) → expect (0.0, 1.414)
    { 0x3C00, 0x4000, 0x3800 },  // angle=1.0, vertex=(2.0, 0.5) → expect ~(0.81, 1.93)
  };
  int n_tests = sizeof(tests) / sizeof(tests[0]);

  for (int t = 0; t < n_tests; t++) {
    uint16_t angle = tests[t].angle;
    uint16_t x     = tests[t].x;
    uint16_t y     = tests[t].y;

    // Compute sin/cos on the host CPU
    uint16_t s    = fp16_sin(angle);
    uint16_t c    = fp16_cos(angle);
    uint16_t ns   = s ^ 0x8000;  // -sin (XOR sign bit)

    // Print input
    gonzo_puts(str_angle);  print_hex16(angle); gonzo_puts(str_nl);
    gonzo_puts(str_vertex); print_hex16(x); gonzo_puts(str_comma); print_hex16(y); gonzo_puts(str_rparen); gonzo_puts(str_nl);
    gonzo_puts(str_sin);    print_hex16(s);
    gonzo_puts(str_cos);    print_hex16(c);
    gonzo_puts(str_neg);    print_hex16(ns);
    gonzo_puts(str_nl);

    // Load registers into Borg (new allocation)
    REG_WRITE(BORG_REG(2), c);    // r2 = cos
    REG_WRITE(BORG_REG(3), x);    // r3 = x
    REG_WRITE(BORG_REG(4), ns);   // r4 = -sin
    REG_WRITE(BORG_REG(5), s);    // r5 = sin
    REG_WRITE(BORG_REG(6), y);    // r6 = y

    // --- Diagnostic: read back registers before execution ---
    gonzo_puts("  W:");
    print_hex16(REG_READ(BORG_REG(2)) & 0xFFFF); gonzo_putc(' ');
    print_hex16(REG_READ(BORG_REG(3)) & 0xFFFF); gonzo_putc(' ');
    print_hex16(REG_READ(BORG_REG(4)) & 0xFFFF); gonzo_putc(' ');
    print_hex16(REG_READ(BORG_REG(5)) & 0xFFFF); gonzo_putc(' ');
    print_hex16(REG_READ(BORG_REG(6)) & 0xFFFF);
    gonzo_puts(str_nl);

    // Reset PC and start execution
    REG_WRITE(BORG_CONTROL, 2);   // Reset PC

    // --- Diagnostic: check status before start ---
    gonzo_puts("  S:");
    print_hex16(REG_READ(BORG_STATUS) & 0xFFFF);

    REG_WRITE(BORG_CONTROL, 1);   // Start

    // Wait for halt
    int timeout = 100000;
    while (!(REG_READ(BORG_STATUS) & 2) && timeout > 0) {
      timeout--;
    }

    // --- Diagnostic: print remaining timeout ---
    gonzo_puts(" T:");
    print_hex16(timeout & 0xFFFF);
    gonzo_puts(str_nl);

    // Read results from r0 (rx) and r1 (ry)
    uint16_t rx = REG_READ(BORG_REG(0)) & 0xFFFF;
    uint16_t ry = REG_READ(BORG_REG(1)) & 0xFFFF;

    // Print output
    gonzo_puts(str_result); print_hex16(rx); gonzo_puts(str_comma); print_hex16(ry); gonzo_puts(str_rparen);
    gonzo_puts(str_nl); gonzo_puts(str_nl);
  }

  gonzo_puts(str_done);

  while(1) {
    gonzo_putc('.');
    for (volatile int i = 0; i < 500000; i++) ;
  }
  return 0;
}
