# The host-side pieces of software/borg: the Python bindings and the SPIR-B parser test.
# borg_core.c, borg_hw.c and borg_spirb.c build into borgvk (mesa) and direct_sim.
.PHONY: all python test-native clean
all: python

python:
	$(MAKE) -C python

test-native: test_spirb.c borg_spirb.c
	gcc -O2 -g -Wall test_spirb.c borg_spirb.c -I. -o test_spirb
	./test_spirb

clean:
	rm -f test_spirb
	$(MAKE) -C python clean
