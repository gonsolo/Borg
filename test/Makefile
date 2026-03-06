# ./test/Makefile

# Standard-Ziel
all:
	$(MAKE) -C soc all GATES=$(GATES)
	@$(MAKE) move_artifacts

# Forwarding für alle anderen Ziele (clean, core, etc.)
%:
	$(MAKE) -C soc $@ GATES=$(GATES)
	@$(MAKE) move_artifacts

# Hilfsziel zum Verschieben der Ergebnisse in den Test-Ordner
move_artifacts:
	@if [ -f soc/results.xml ]; then mv soc/results.xml .; fi
	@if [ -f soc/tb.vcd ]; then mv soc/tb.vcd .; fi
	@if [ -f soc/tb.fst ]; then mv soc/tb.fst .; fi

.PHONY: all move_artifacts
