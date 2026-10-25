# borgc checks that need only borgc-cli, no simulator and no hardware.

BORGC_CLI ?=
GLSLANG  ?= glslangValidator
TMP      := $(CURDIR)/.regen

# Push-constant lowering check (Step 50 item 13) — no simulator, no hardware.
#
# Asserts that borgc turns `layout(offset = 32)` in testdata/pushconst.frag
# into RAW WORD INDICES 8..11 pinned in const registers, because that single
# fact is what the whole feature rests on: the hardware forms
# LS_BASE + (rs1 << 2), so an index that got float-converted, or computed in
# bytes instead of words, addresses the wrong DRAM entirely. Cheap enough to
# run on every compiler change, unlike the simulator tests.
#
# Deliberately checks the ISA dump rather than just a clean exit: the shader
# compiling proves nothing here, since a wrong-but-well-formed index compiles
# exactly as happily as a right one.
test-pushconst:
	@test -n "$(BORGC_CLI)" || \
	  (echo "ERROR: set BORGC_CLI=<mesa-build>/src/borg/compiler/borgc-cli" && exit 1)
	@mkdir -p $(TMP)
	@$(GLSLANG) -V testdata/pushconst.vert -o $(TMP)/pc_vert.spv > /dev/null
	@$(GLSLANG) -V testdata/pushconst.frag -o $(TMP)/pc_frag.spv > /dev/null
	@$(BORGC_CLI) -s vert -o $(TMP)/pc_vert.borg $(TMP)/pc_vert.spv > /dev/null 2>&1
	@BORGC_DUMP_ISA=1 $(BORGC_CLI) -s frag -o $(TMP)/pc_frag.borg $(TMP)/pc_frag.spv \
	   > $(TMP)/frag.log 2>&1
	@grep -q 'r17=0x0008 r18=0x0009 r19=0x000a r23=0x000b' $(TMP)/frag.log || \
	  (echo "FAIL: offset=32 did not lower to word indices 8..11"; \
	   grep 'const regs' $(TMP)/frag.log; rm -rf $(TMP); exit 1)
	@echo "push-constant lowering OK: offset 32 -> word indices 8..11 (raw, not float)"
	@rm -rf $(TMP)

# Wire bursts for the push-constant end-to-end test (Step 50 item 13, phase 2).
#
# Produces two replayable frames that are byte-identical except for the pushed
# colour, which is the whole experiment: phase 3 renders both and requires the
# output to follow the pushed value. A single burst could not distinguish a
# shader that reads the push constant from one that returns a baked colour.
#
# Generated rather than checked in: the bursts embed borgc's compiled blobs,
# so a stale .bin would silently test an old compiler against a new firmware.
# Regenerate whenever either side moves.
#
#   make pushconst-bursts BORGC_CLI=<mesa-build>/src/borg/compiler/borgc-cli \
#                         BURST_GEN=<mesa-build>/src/borg/vulkan/borgvk_burst_gen \
#                         OUT=<dir>
OUT ?= $(CURDIR)/.bursts
BURST_GEN ?=

pushconst-bursts:
	@test -n "$(BORGC_CLI)" || \
	  (echo "ERROR: set BORGC_CLI=<mesa-build>/src/borg/compiler/borgc-cli" && exit 1)
	@test -n "$(BURST_GEN)" || \
	  (echo "ERROR: set BURST_GEN=<mesa-build>/src/borg/vulkan/borgvk_burst_gen" && exit 1)
	@mkdir -p $(OUT)
	@$(GLSLANG) -V testdata/pushconst.vert -o $(OUT)/pc_vert.spv > /dev/null
	@$(GLSLANG) -V testdata/pushconst.frag -o $(OUT)/pc_frag.spv > /dev/null
	@$(BORGC_CLI) -s vert -o $(OUT)/pc_vert.borg $(OUT)/pc_vert.spv > /dev/null 2>&1
	@$(BORGC_CLI) -s frag -o $(OUT)/pc_frag.borg $(OUT)/pc_frag.spv > /dev/null 2>&1
	@$(BURST_GEN) $(OUT)/pc_vert.borg $(OUT)/pc_frag.borg \
	   $(OUT)/burst_red.bin  1.0 0.0 0.0 1.0 > /dev/null 2>&1
	@$(BURST_GEN) $(OUT)/pc_vert.borg $(OUT)/pc_frag.borg \
	   $(OUT)/burst_blue.bin 0.0 0.0 1.0 1.0 > /dev/null 2>&1
	@echo "push-constant bursts in $(OUT): burst_red.bin burst_blue.bin"

clean:
	rm -rf $(TMP) $(OUT)

.PHONY: clean test-pushconst pushconst-bursts
