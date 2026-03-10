vert.s: vert.spvasm
	python compiler.py vert.spvasm vert.s
vert.borg.h: vert.s
	python compiler.py --borg vert.s vert.borg.h
vert.spvasm: vert.spv
	spirv-dis vert.spv -o vert.spvasm
vert.spv: shader.vert
	glslangValidator -V shader.vert -o vert.spv
clean:
	rm -f vert.s vert.spvasm vert.spv vert.borg.h
.PHONY: clean
