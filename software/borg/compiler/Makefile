# shader_blobs.h is a checked-in static artifact containing three SPIR-B blobs:
#   vert_borg      — vertex shader (MVP transform + UV pass-through)
#   rasterize_borg — rasterizer (fixed-function, hand-written Borg ISA; source: rasterize.s)
#   frag_borg      — fragment shader (texel × vertex-color Gouraud)
#
# The vert and frag blobs were originally generated from GLSL (shader.vert /
# shader.frag) via glslangValidator → spirv-dis → spirv_compiler.py →
# borg_backend.py. That pipeline has been removed; borgc (the Mesa NIR→Borg
# compiler) is now the canonical compiler for vert/frag shaders and runs at
# borgvk submit time rather than at firmware build time.
#
# The rasterizer (rasterize.s) is still hand-written Borg ISA and kept for
# reference, but is not rebuilt automatically here.

all:
	@test -f shader_blobs.h || \
	  (echo "ERROR: shader_blobs.h missing — it is a checked-in static file" && exit 1)

clean:
.PHONY: all clean
