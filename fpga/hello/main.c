#include <csr.h>
#include <uart.h>
#define printf uart_printf
#include <mul.h>

int a = 3;

int main() {
  printf("Hello, Gonzo!\n");
  printf("Hello, world!\n");
  printf("Hello %d\n", a);
  a = mul32x16(12, a);
  printf("Hello %d\n", a);
  printf("Cycles %d, time %d, instret %d\n", read_cycle(), read_time(), read_instret());
  return 0;
}
