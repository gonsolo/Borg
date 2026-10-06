#!/bin/sh
# borgc must refuse a shader with an op it cannot lower (sin) and still compile the cube shaders.
set -e
CLI=${BORGC_CLI:-mesa/build-borg/src/borg/compiler/borgc-cli}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
printf '#version 450\nlayout(location=0) in float x;\nlayout(location=0) out vec4 o;\nvoid main(){ o = vec4(sin(x)); }\n' > "$T/bad.frag"
glslangValidator -V "$T/bad.frag" -o "$T/bad.spv" >/dev/null
if "$CLI" -s frag -o "$T/bad.borg" "$T/bad.spv" 2>"$T/err"; then echo "FAIL: sin compiled"; exit 1; fi
grep -q "unhandled NIR ops.*fsin" "$T/err" || { echo "FAIL: no loud error"; exit 1; }
for s in vert frag; do
  glslangValidator -V Vulkan-Tools/cube/cube.$s -o "$T/c.$s.spv" >/dev/null
  "$CLI" -s $s -o "$T/c.$s.borg" "$T/c.$s.spv" 2>/dev/null || { echo "FAIL: cube.$s"; exit 1; }
done
echo "borgc loud-failure test: ok"
