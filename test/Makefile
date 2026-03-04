# Redirect Makefile for TinyTapeout compatibility
# Forwards all targets to the soc/ directory

# Default target
all:
	$(MAKE) -C soc all

# Forward any other target (e.g., clean, core, borg.test)
%:
	$(MAKE) -C soc $@

.PHONY: all
