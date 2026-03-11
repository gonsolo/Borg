all: vert.borg.h frag.borg.h rasterize.borg.h

vert.borg.h: vert.s
	python borg_backend.py vert.s vert.borg.h
vert.s: vert.spvasm
	python spirv_compiler.py vert.spvasm vert.s
vert.spvasm: vert.spv
	spirv-dis vert.spv -o vert.spvasm
vert.spv: shader.vert
	glslangValidator -V shader.vert -o vert.spv

frag.borg.h: frag.s
	python borg_backend.py frag.s frag.borg.h
frag.s: frag.spvasm
	python spirv_compiler.py frag.spvasm frag.s
frag.spvasm: frag.spv
	spirv-dis frag.spv -o frag.spvasm
frag.spv: shader.frag
	glslangValidator -V shader.frag -o frag.spv

rasterize.borg.h: rasterize.s
	python borg_backend.py rasterize.s rasterize.borg.h

clean:
	rm -f vert.s vert.spvasm vert.spv vert.borg.h frag.s frag.spvasm frag.spv frag.borg.h rasterize.borg.h
.PHONY: clean
