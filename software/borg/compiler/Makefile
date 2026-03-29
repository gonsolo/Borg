all: vert.borg frag.borg rasterize.borg shader_blobs.h

vert.borg: vert.s borg_backend.py ../../../fpga/host/borg_mmio.py
	python borg_backend.py vert.s vert.borg
vert.s: vert.spvasm spirv_compiler.py
	python spirv_compiler.py vert.spvasm vert.s
vert.spvasm: vert.spv
	spirv-dis vert.spv -o vert.spvasm
vert.spv: shader.vert
	glslangValidator -V shader.vert -o vert.spv

frag.borg: frag.s borg_backend.py ../../../fpga/host/borg_mmio.py
	python borg_backend.py frag.s frag.borg
frag.s: frag.spvasm spirv_compiler.py
	python spirv_compiler.py frag.spvasm frag.s
frag.spvasm: frag.spv
	spirv-dis frag.spv -o frag.spvasm
frag.spv: shader.frag
	glslangValidator -V shader.frag -o frag.spv

rasterize.borg: rasterize.s borg_backend.py ../../../fpga/host/borg_mmio.py
	python borg_backend.py rasterize.s rasterize.borg

shader_blobs.h: vert.borg rasterize.borg frag.borg
	@echo "// Auto-generated: embedded SPIR-B shader blobs" > $@
	@echo "#pragma once" >> $@
	@echo "#include <stdint.h>" >> $@
	@echo "" >> $@
	xxd -i vert.borg >> $@
	xxd -i rasterize.borg >> $@
	xxd -i frag.borg >> $@

test: all
	python test_spirb.py

clean:
	rm -f vert.s vert.spvasm vert.spv vert.borg frag.s frag.spvasm frag.spv frag.borg rasterize.borg shader_blobs.h
.PHONY: clean
