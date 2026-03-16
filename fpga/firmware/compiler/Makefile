all: vert.borg.h vert.borg frag.borg.h rasterize.borg.h rasterize.borg

vert.borg.h: vert.s borg_backend.py
	python borg_backend.py vert.s vert.borg.h
vert.borg: vert.s borg_backend.py
	python borg_backend.py vert.s vert.borg
vert.s: vert.spvasm spirv_compiler.py
	python spirv_compiler.py vert.spvasm vert.s
vert.spvasm: vert.spv
	spirv-dis vert.spv -o vert.spvasm
vert.spv: shader.vert
	glslangValidator -V shader.vert -o vert.spv

frag.borg.h: frag.s borg_backend.py
	python borg_backend.py frag.s frag.borg.h
frag.s: frag.spvasm spirv_compiler.py
	python spirv_compiler.py frag.spvasm frag.s
frag.spvasm: frag.spv
	spirv-dis frag.spv -o frag.spvasm
frag.spv: shader.frag
	glslangValidator -V shader.frag -o frag.spv

rasterize.borg.h: rasterize.s borg_backend.py
	python borg_backend.py rasterize.s rasterize.borg.h
rasterize.borg: rasterize.s borg_backend.py
	python borg_backend.py rasterize.s rasterize.borg

test: all
	python test_spirb.py

clean:
	rm -f vert.s vert.spvasm vert.spv vert.borg.h vert.borg frag.s frag.spvasm frag.spv frag.borg.h rasterize.borg.h rasterize.borg
.PHONY: clean
