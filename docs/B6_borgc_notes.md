# borgc design notes

Explanations that used to sit as comments in `mesa/src/borg/compiler/`, moved here verbatim. Each note names the function it was in and the code line it stood above; line numbers refer to mesa commit `5228f48941f`.


## lib.rs


**line 3, in `(file level)`, above ``**

borgc — the Borg GPU shader compiler (Rust), called from the borgvk Vulkan
driver. It lowers Mesa NIR to Borg ISA (FADD/FMUL/FMADD/FNEG/FSTEP/FRCP/TEX
and friends, on the FP32 shader datapath) and emits the .borg blob the
firmware loads. Modeled on
Mesa's NAK (src/nouveau/compiler/nak).

Status: SPIR-V→NIR→borgc is live. This pass classifies every NIR instruction
against the Borg ISA — a coverage report that drives instruction selection.
The arithmetic core (fmul/ffma → FMUL/FMADD) selects directly; the open work
is lowering the UBO/I-O intrinsics to the firmware's uniform model and real
register allocation + .borg emission.


**line 40, in `(file level)`, above `fn packed_layout(flags: Flags) -> (u32, Vec<(usize, u32, u32)>) {`**

One selected Borg instruction in virtual-register form (pre-register-alloc).
`dst`/`srcs` are NIR SSA indices used directly as virtual registers; `swz` is
the scalar component each source reads from its (possibly vec4) def — needed to
pin a uniform/attribute load to the right column/component.
`BorgInstr::dst` for an instruction that writes no register.

The execution-mask ops and STORE have no destination -- their rd field
either is unused or carries something else entirely. Without a sentinel
they would look like definitions of vreg 0 to the register allocator,
which would then keep a register alive for a value nothing produces.
A packed colour attachment (fragment option bit 0x10): the kind (0 UNORM, 1 SNORM, 2 SRGB, 3 the
bits as they are) and, per channel, (component 0..3 = R G B A, first bit, bits) within the texel.
Size code 3 selects a format with uneven channels by number (borgvk_sim.c, packed_info).


**line 82, in `packed_layout`, above `const FRAG_POOL_SLOT: u32 = 40;`**

The vertex-attribute slot the fragment constant pool is fetched from (texture slot 128 + this).


**line 86, in `packed_layout`, above `#[no_mangle]`**

The constant pool of the fragment shader compiled last (empty when it fits the window).


**line 100, in `borgc_last_frag_pool`, above `pub(crate) struct CfMark {`**

A control-flow instruction to splice in before a given NIR block.

NIR's `if` maps onto the mask ops with no branching at all, and every
marker happens to land *before* some block: EXPUSH before the first `then`
block, EXELSE before the first `else` block, EXPOP before the block
following the `if`. That symmetry is why this is a single "before" map
rather than a pair of before/after lists.


**line 120, in `borgc_last_frag_pool`, above `fn decompose_vertex_offset(`**

Walk the NIR control-flow tree and record where mask instructions belong.

The instruction-selection walk below iterates blocks FLAT, in layout order,
which is exactly the order this tree linearizes to -- so the two can be
matched up by block pointer without restructuring selection at all. That
matters: selection is the part with all the operand and swizzle handling,
and threading control flow through it would have meant rewriting it.

Note what the flat walk does WITHOUT this: it emits the `then` and `else`
bodies one after the other with nothing to distinguish them, so both
execute unconditionally. Any shader whose control flow survived NIR's
flattening passes was already being miscompiled; this is what fixes it.

Loops are deliberately not handled -- see the caller, which refuses them
rather than emitting a body that runs exactly once.
Draw front end only (docs/B1_geometry_front_end.md): decompose a
dynamically-indexed load_ubo's byte offset into `(word-aligned base,
per-vertex word stride)`, recognizing `const + (gl_VertexIndex << shift)`
-- what NIR's own optimizer canonicalizes a UBO array indexed by
gl_VertexIndex to (verified against real cube.vert output, BORGC_DUMP_NIR)
-- through `resolve_vm` (so any mov/vecN wrapping already selected earlier
in the walk is transparent) and `prod`/`consts` (populated by that same
generic ALU selection, not a second walk of raw NIR).

A `load_vulkan_descriptor` result is treated as contributing 0 wherever it
appears in the offset, by construction rather than by proving it from the
IR: Borg has one binding per resource, no descriptor arrays, no
VK_DESCRIPTOR_TYPE_*_DYNAMIC (borgvk_descriptor_set.c never reads
pDynamicOffsets), so it never actually varies. (An earlier version of this
made that true at the NIR level, constant-folding the descriptor to a real
zero before this function ever ran -- which spuriously turned OTHER
multi-component load_consts the folding touched into "shader constants"
this compiler pins to GPRs, exhausting the const-register budget on a
shader that uses none. Recognizing the descriptor here instead has no such
side effect.)

Anything else is reported, not guessed at: the caller drops the shader
rather than emit an address for an offset shape this does not recognize.


**line 166, in `decompose_vertex_offset`, above `fn walk(`**

Sum of `d`'s additive terms as (constant, dynamic stride if one leaf was
gl_VertexIndex, optionally shifted). nir_lower_explicit_io's
vec2_index_32bit_offset format adds the descriptor's own dynamic-offset
component into EVERY load_ubo address, not just a dynamically-indexed
one -- range_base=0's own offset in cube.vert (MVP column 0) is exactly
`descriptor.z` alone, no visible "+0" left for nir_opt_algebraic to have
simplified away -- so this has to walk the WHOLE additive tree, not
assume a single flat `const + dynamic` split.


**line 248, in `collect_cf_marks`, above `unsupported.push("loop");`**

A divergent loop needs the mask PLUS a way to ask "is any lane
still active" to decide the backward branch, and that
instruction does not exist yet. Emitting the body unmasked
would run every iteration for every lane.


**line 258, in `collect_cf_marks`, above `#[derive(Clone, Copy, PartialEq)]`**

How a load_ubo def maps onto the firmware's uniform register convention
(cube.c `vktexcube_vs_uniform` layout — see the I/O map below). Carried per
def index so the encoder can pin each operand that reads it.


**line 263, in `collect_cf_marks`, above `Mvp(i32),`**

MVP column at this byte range_base (column = range_base/16). Component `c`
→ uniform u(8 + column*4 + c). Read directly as a funct3 uniform operand.


**line 266, in `collect_cf_marks`, above `Pos,`**

position[gl_VertexIndex] vec4. Component `c` → uniform u(c) (firmware
pre-fetches the current vertex into u0..u2). Pre-loaded into a GPR.


**line 269, in `collect_cf_marks`, above `Attr,`**

Vertex attribute (texcoord). Component `c` → uniform u(6+c): the
sequencer DMA pre-loads the per-vertex descriptor words [x,y,z,r,g,b,u,v]
into uniform[0..7] before running the vertex shader, so u6=U and u7=V.
In practice, vertex shader `store_output(VAR0)` is DCE'd (the sequencer
snoops UV from the DMA stream directly, bypassing the vertex shader), so
Attr values are eliminated before encoding and this path is latent.


**line 276, in `collect_cf_marks`, above `Uniform(u8),`**

A fragment uniform read directly at this physical index via funct3
(inv_area, per-vertex varyings staged by the sequencer at u12..u30).


**line 279, in `collect_cf_marks`, above `Fixed(u8),`**

A value that lives in a fixed physical register, not allocated (the
fragment edge-function attributes e0/e1/e2 in r0/r1/r2).


**line 290, in `borgc_selftest`, above `#[no_mangle]`**

Compile the app's shader (NIR) to a Borg-ISA `.borg` blob and return it to the
caller. The blob (firmware `spirb_parse` format) is written into `out_buf` (up
to `buf_cap` bytes); `*out_len` is set to the blob's true size so the caller can
detect an undersized buffer (`*out_len > buf_cap` ⇒ nothing copied). Any of
`out_buf`/`out_len` may be null to skip blob return (diagnostics-only, as before).
Returns the instruction count (0 on null/empty shader).

# Safety
`nir` must be a valid `nir_shader` pointer from the Mesa runtime (or null).
`out_buf` must point to at least `buf_cap` writable bytes (or be null).


**line 308, in `borgc_compile_nir`, above `let key = if !nir.is_null() && env::var("BORGC_CORPUS_DIR").is_ok() {`**

A shader borgc cannot handle trips an assert; unwinding out of an extern "C"
function aborts the whole process, so turn it into "no blob" (the same outcome
as any other unsupported shader).


**line 365, in `compile_nir_inner`, above `let draw_mode = env::var("BORGC_LEGACY").is_err();`**

Vector-construction / move resolution. mov and vecN don't become Borg ops;
instead they define, per result component, which (scalar def, component) it
really reads. cube.c's matrix multiply threads its accumulator through vec4
builds and swizzle reads (e.g. `%67 = fadd(%66, %65.x)` where %65 = vec4(...)),
so an operand must be resolved through these to its true scalar producer —
otherwise the data flow is severed and accumulators read garbage.
Single in-order selection walk. NIR is in SSA + topological order, so when we
reach an instruction all its sources are already in sel.vec_map/sel.prog. Each
value-producing op either (a) registers a sel.vec_map entry (mov/vecN and the
vector-wide intrinsics, which resolve away component-wise) or (b) emits scalar
Borg ops. Operands are resolved through sel.vec_map to their true scalar producer.
Synthetic results from expansions (ddx/ddy/fmax/tex/interp) use fresh vregs
above any NIR ssa index.
Phi renames, collected during the selection walk and applied after it
(see the comment at the collection site).
Operand classification (populated here for fragment uniforms/attrs, and in the
I/O pass below for the vertex's load_ubo).
Barycentric cx.weights w_i = e_i·inv_area, emitted once on the first load_input.
e0/e1/e2 (edge-function attrs r0/r1/r2) are Ubo::Fixed too, but unlike the
*other* Ubo::Fixed use (true shader constants pinned to r17+, e.g. lightDir),
they vary per pixel — exclude them from the uniformity classification's
"is this a per-triangle-constant root" test below.
Scalar load_const f32 bits (for the sRGB idiom match) and a producer map
(alu def → its op + resolved scalar srcs) for recognising the bcsel tree.
TEX results occupy 4 consecutive regs (rd..rd+3 = R, G, B, A).
FATTR results occupy 3 consecutive regs (rd..rd+2 = per-vertex v0/v1/v2),
same hardware shape as TEX's 4-wide result -- see cx.tex_dsts's resolve-time
(alloc[def] + component) handling below, mirrored for this set.
TEX's control word (docs/B2_texture_unit.md), created on first use. Word 0
is texture 0, sampler 0, a plain sample with the LOD taken from the quad,
no offsets -- all a shader with one sampler2D needs. It is built in the
shader rather than pinned: FSTEP of -r30 is exactly +0 (r30 >= 0 in every
stage), so it costs two instructions and no constant register.
Distinct texture derefs seen, to say so when a shader samples more than
one: every sample uses descriptor 0 until borgc maps bindings to indices.
Shader constants (cube.frag's lightDir, push-constant word indices) live in
GPRs the firmware writes ONCE before the autonomous render, so they must
survive every stage that runs before the fragment on every triangle and
pixel: the vertex shaders (hand + borgc: r0-9, r24-26), the setup shader
(r0-16), the rasterizer ROM (r0-4), and the fragment's own fixed blocks
(r0-2 edges, r20-23 TEX, r24 alpha, r25 kill, r26-29 outputs, r30/31
pixel centre). That leaves r17-19, plus r23 in a shader that never
samples: TEX writes rd..rd+3, so with rd = 20 it overwrites r23. Handing
out r20 -- the next register after r19 -- once put fge's 1.0 where the
texel lands. Uniforms are no alternative: rast(u0-11) + frag
varyings(u12-30) fill 31 of 32. Records (reg, value), value a datapath
float or a raw integer.
Vertex shaders have none of the fragment-only reservations below (edge
attrs r0-2, TEX r20-23, alpha r24, kill r25) -- only r0-4 (gl_Position
output + the perspective-divide epilogue's scratch, both via `forced`/
regalloc's own reserved set, not this list) and r30/31 (VertexIndex,
never in the general pool). r5-16 is a generous, exclusively
vertex-stage pool, wide enough for a draw-mode vertex shader's LOAD
address constants (MVP base, the shared +1 increment, each
vertex-pulled array's base and stride) -- more of them than a legacy
vertex shader has ever needed, since none pinned any before.
Draw mode (DRAW_CFG mode 1, docs/B1_geometry_front_end.md) is the only
geometry path the hardware keeps. BORGC_LEGACY=1 still emits the old
per-triangle-descriptor ABI until that code is deleted here too.


**line 425, in `compile_nir_inner`, above `let regs: &'static [u8] = if is_vertex {`**

r0-4 can never hold a shader-pinned constant, draw mode or not: the
draw front end's baked raster ROM (BorgRasterRom, its own "REGISTER
CLOBBER ABI" comment) destroys r0..r4 on EVERY pixel as part of its
edge/depth/barycentric setup, before the fragment shader's own body
even starts -- confirmed on real hardware (a value pinned there read
back as whatever the ROM last wrote, not what the firmware staged).


**line 439, in `compile_nir_inner`, above `const VARYING_SLOT_VAR0: u32 = 32;`**

Pin a compile-time-constant integer to a GPR, reusing the same
register for every later reference to the same value (draw-mode LOAD
word indices and the literal 1 an address sequence increments by).
A draw-mode VERTEX shader cannot keep constants in GPRs at all: the
draw walker runs the setup ROM (BorgSetupRom: r0-r6 plus temporaries)
in the same register file between one triangle's vertex shader and the
next, so a GPR the firmware staged once per draw survives only the
first triangle. docs/B1_geometry_front_end.md gives the vertex shader
u25-u31 instead -- its DRAW_VS_CONST window, loaded once per draw into
uniforms the setup ROM never touches (it owns u0-u24). Each constant
is a funct3 uniform operand there, which every instruction accepts
once (LOAD's address included), so it costs no extra instructions.
u31 is never written by the DMA (Borg.scala keeps it firmware-owned), so the window ends at u30.
A vertex-shader constant: a window word, shared by value; once the window is full a small
integer is derived from others (a sum) or zero (a difference) with an ALU op.
A fresh vreg standing for the compile-time integer `v`: a window
uniform in a draw-mode vertex shader, a pinned GPR otherwise. Same
value, same slot, for every reference (cx.cp.int_reg caches either).
1.0 for the inverting comparisons (fge/feq), created on first use. In a
fragment shader it is FSTEP(r30): r30 reads the pixel centre (>= 0.5)
during the fragment pass, so the result is exactly 1.0 and costs no
constant register. Vertex shaders see r30 = 0 and fall back to a const.
Word-index constants for push-constant LOADs, pinned to const GPRs on
first use and shared across every load_push_constant reaching the same
word (a vec4 field reads 4 consecutive words; a scalar read elsewhere
in the shader at the same offset reuses the same pin rather than
burning a second scarce const register on an identical value).
Draw-mode fragment's own vector constants (lightDir etc): NOT GPR
pins (see the load_const arm below for why), but words in the
existing uniform memory window (draw_fs_const_offset / u20+, the
same mechanism legacy fragment shaders already read inv_area and
per-vertex varyings from). Reported here, not folded into
cx.cp.uniforms, since that Vec specifically means "GPR, poked once
via MMIO" -- a different delivery channel from "word in memory."
gl_varying_slot: VAR0=texcoord, VAR1=frag_pos (Mesa enum: VAR0 = 32).


**line 477, in `compile_nir_inner`, above `let frag_alpha = env::var("BORGC_FRAG_ALPHA").is_ok() || (is_fragment && flags.alpha_outpu`**

--- Draw front end (docs/B1_geometry_front_end.md), gated behind an env
var so the legacy path -- and every shader compiled against it, incl.
the checked-in shader_blobs.h -- is untouched until borgvk moves over
(docs/B1's own "Coexistence" section). Everything below this point that
is draw_mode-specific is a parallel, independent I/O path: it shares
the generic ALU/tex/ddx selection above and nothing of the legacy
MVP/Pos/Attr/load_input scheme, which stays exactly as it was.
(draw_mode itself is declared further up, before cx.cp.regs, which
also branches on it.)
Moved up from the legacy I/O pass below so the draw-mode store_output
handling (in the main walk, ahead of that pass) can read it too; same
value, same meaning, in both modes.


**line 491, in `compile_nir_inner`, above `if !entry.is_null() {`**

gl_VertexIndex's SSA def, once seen -- the index every vertex-pulling
load_ubo (position[], attr[]) is computed from. Always seen before any
load_ubo that depends on it: NIR/SPIR-V evaluates gl_VertexIndex where
the source first reads it, and a dynamic array index has to read it
before it can compute an offset from it.
A compile-time-constant integer, pinned to a GPR on first use and
reused for every later reference to the SAME value -- word indices for
draw-mode LOADs (MVP columns, vertex-pulling base/stride) and the
literal 1 a multi-word LOAD sequence increments its address by.
Deliberately separate from cx.push_const_reg: that cache is word indices
into the push-constant staging buffer, a different LS_BASE-relative
address space than the vertex/uniform buffer draw-mode LOADs read.
Vertex stage: resolved scalar producer per output location, built
in program order as store_output is seen, so a load_output reading
an earlier store in the SAME shader (`frag_pos = gl_Position.xyz`)
resolves -- SOUT has no matching load, so that is the only way such a
read can ever be answered. Also doubles as the SOUT-index source: a
location's SOUT indices are 4*(location - VARYING_SLOT_VAR0) upward
(VARYING_SLOT_POS's own components are never SOUT, so it does not
compete with this), a fixed mapping the fragment compile computes
identically without any shared state between the two compiles.
load_vulkan_descriptor results seen, for decompose_vertex_offset.
Running (next expected word address, its address vreg) for consecutive
constant-address loads (the MVP columns: 4 separate load_ubo calls, 16
words back to back). Chaining them through ONE running IADD-by-1
sequence, continued across calls when a later one picks up exactly
where the previous left off, is what keeps this under the ~4-word
constant-register budget -- pinning each of the 16 word indices on its
own does not fit (see git history).


**line 521, in `compile_nir_inner`, above `let mut cf_marks: std::collections::HashMap<usize, Vec<CfMark>> =`**

Control-flow markers, collected from the CF tree so this flat block
walk can splice them in at the right seams. Must be here rather than
in the I/O pass below: the marks are instructions and have to
interleave with selection's output, not land after all of it.


**line 537, in `compile_nir_inner`, above `let mut reg_vregs: HashMap<u32, Vec<u32>> = HashMap::new();`**

Phis arrive as registers: borg_nir_passes.c runs nir_convert_from_ssa, so an `if`'s
merge is a decl_reg, a store_reg at the end of each arm and a load_reg after. Under an
execution mask both arms run and the mask decides whose write lands, so every
store_reg is a masked copy (FMOV) into the register's virtual registers.


**line 543, in `compile_nir_inner`, above `if let Some(ms) = cf_marks.get(&(block as *const nir_block as usize)) {`**

Emitted before the block's own instructions, which is what makes
EXPUSH gate the `then` body and EXPOP land after the `if` rather
than inside it.


**line 603, in `compile_nir_inner`, above `let mut mvp_loads = 0u32;`**

I/O map: pin the shader interface to the firmware register convention.
  load_ubo range_base (cube.c vktexcube_vs_uniform layout):
    0/16/32/48 → MVP columns    → uniforms u8..u23 (col-major; u = 8 + rb/4)
    64..639    → position[idx]  → uniforms u0..u2 (firmware pre-fetches the
                                   current vertex; the index math is dead)
    640..      → attr[idx]      → texcoord varying (firmware-handled)
  store_output io_semantics.location (low 7 bits of IO_SEMANTICS):
    VARYING_SLOT_POS(0) → gl_Position → output regs r0..r3 (sequencer-snooped)
    VAR0/VAR1           → texcoord/frag_pos varyings (firmware-handled)


**line 617, in `compile_nir_inner`, above `let mut out_roots: Vec<u32> = Vec::new();`**

(`cx.sel.ubo` is declared above the selection walk so the fragment path can populate it.)
Output roots for DCE: every value a store_output consumes is live.


**line 620, in `compile_nir_inner`, above `let mut pos_out: [Option<u32>; 4] = [None; 4];`**

gl_Position component c (x/y/z/w) → the SSA value that produces it. These
clip coords are pinned to r0..r3 so the perspective-divide epilogue and the
sequencer's clipReg snoop find them where the firmware convention expects.


**line 624, in `compile_nir_inner`, above `let mut frag_out: [Option<u32>; 4] = [None; 4];`**

Fragment colour uFragColor → output regs: RGB to r26/r27/r28, A to r24.
The hardware ABI block is r24=A, r25=Kill, r26..r28=RGB, r29=Z.

Alpha is opt-in and off by default. It is only meaningful to a
BorgConfig.hasBlend build -- without a blend stage the dispatcher
ignores r24 entirely -- and emitting it unconditionally would change
the codegen of every existing fragment shader: the alpha component
becomes a live output root, so the instructions computing it stop
being dead code and the shader grows. Off by default keeps today's
shaders byte-identical; the driver sets this when it binds a pipeline
with blendEnable.


**line 636, in `compile_nir_inner`, above `if !draw_mode && !entry.is_null() {`**

Legacy-only: draw mode built pos_out/frag_out/out_roots inline in the
main walk above (cx.outs.pos/cx.outs.frag/cx.outs.roots), in program
order, so a load_output reading back an earlier store_output resolves
-- SOUT has no matching load, so that is the only way such a read can
ever be answered, which rules out a second, later pass like this one.
gl_position deliberately stays None in draw mode: it is what gates the
perspective-divide epilogue below, and draw mode wants the RAW clip
coordinates in r0-r3 (BorgSetupRom does its own homogeneous divide) --
running that epilogue would corrupt them.


**line 672, in `compile_nir_inner`, above `gl_position = Some(src);`**

gl_Position is a vec4 stored whole (wrmask=xyzw);
resolve each component to its scalar producer and
pin it to r0..r3 (also the DCE roots). Varyings
(texcoord/frag_pos) are firmware-handled — not rooted.


**line 684, in `compile_nir_inner`, above `let ncomp = if frag_alpha { 4 } else { 3 };`**

Fragment colour uFragColor (vec4) → r26/27/28 rgb,
plus r24 alpha when the blend path is in use.


**line 703, in `compile_nir_inner`, above `let srgb = cx.sel.prog.iter().any(|i| i.mnem == "FSRGB");`**

fpow/fge only vanish into the sRGB idiom (FSRGB); any other cx.skipped op is a missing lowering.


**line 711, in `compile_nir_inner`, above `let ctl_bits = (1u32 << 16) | (128 + 16);`**

Point expansion: this vertex is a corner of the point's quad; the corner's clip-space
offset (slot 16: x y, then the point coordinate u v), times gl_PointSize, times w, moves it from the point's centre.


**line 786, in `compile_nir_inner`, above `let n = flags.target_count();`**

Up to four R8 attachments, each rendered in its own pass (ATTIDX): the byte this pass stores is
chosen from the per-attachment values; one the shader never wrote keeps its own (TLD).


**line 791, in `compile_nir_inner`, above `let idx = cx.sel.op0("ATTIDX", vec![]);`**

x + 0 in integer arithmetic copies a window word bit for bit (FMOV would flush a denormal).


**line 869, in `compile_nir_inner`, above `let (kind, layout) = packed_layout(flags);`**

Packed colour (RAW8 .. RAW128): n channels of 8, 16 or 32 bits, kind 0 UNORM, 1 SNORM, 2 SRGB, 3 the
bits as they are; `swap` reads the components as B, G, R. Channel k fills bits k*size of the texel:
word w of the texel is r26 (w = 0), r27 (w = 1); a RAW128 texel renders in two slices of two words.


**line 945, in `compile_nir_inner`, above `macro_rules! i {`**

R16G16_SFLOAT (RAW32; with bit 0x20 R16_SFLOAT, RAW16): r26 = F2H(r) | F2H(g) << 16. F2H rounds to nearest even,
overflows to Inf and flushes results below 2^-14 to zero; H2F widens the low 16 bits exactly (a half denormal reads
as zero). Both are the `Fp16Fp32` functions of the RTL. A blend reads the destination once per shader (TLD, then H2F
per channel) and the whole pack is one F2H per channel. Before F2H/H2F (2026-10-14) the conversions were about 30
integer instructions and 13 constants per channel, which overflowed the 11-word window and the registers of the
RGBA16F blend shaders.


**line 1015, in `compile_nir_inner`, above `let (mut p, mut pc) = cx.outs.frag[3].unwrap();`**

Byte-packed integer colour (R8G8B8A8_UINT/SINT, RAW32): r26 = c0 | c1<<8 | c2<<16 | c3<<24.


**line 1041, in `compile_nir_inner`, above `if is_fragment {`**

A fragment output that no instruction produces -- a constant colour, say, which lives in
the uniform window -- has no register to hand to the output stage: copy it with FMOV
(rs1 may be a uniform).


**line 1053, in `compile_nir_inner`, above `if cx.tex_dsts.contains(&v) || cx.fattr_dsts.contains(&v) {`**

A register is copied bit for bit (IOR): FMOV turns an integer that looks like a NaN into 0x7FC00000.


**line 1085, in `compile_nir_inner`, above `let mut frag_z: Option<u32> = None;`**

Fragment depth output (target boilerplate): cube.frag writes no depth, but
the tile-buffer depth test needs r29 = interpolated z. z is staged per-vertex
at u28-30 ((v2,v1,v0) → v0=u30); emit w0·u30 + w1·u29 + w2·u28 → r29.
Draw mode: r29 is FragCoord.z already, from the raster ROM
(docs/B1_geometry_front_end.md) -- cx.weights stays None (only the legacy
load_input path populates it), so this never fires there, correctly:
the fragment shader not writing r29 IS draw mode's depth output.


**line 1115, in `compile_nir_inner`, above `#[allow(static_mut_refs)]`**

Fragment constants beyond the window come from a pool: a typed fetch of four words at a
time from vertex-attribute slot FRAG_POOL_SLOT, which the driver points at the pool's words
(borgc_last_frag_pool). The window keeps its first nine and holds the fetch's control word
and the 1 the index steps by.


**line 1169, in `compile_nir_inner`, above `let is_pool = |v: &u32, ubo: &HashMap<u32, Ubo>| match ubo.get(v) {`**

A group of four is fetched just before its first use, so a constant holds a register
only while it is needed: the pool is ordered by first use.


**line 1190, in `compile_nir_inner`, above `const GAP: usize = 16;`**

A group is fetched before its first use, and again when it has not been used for a
while (a register held across the whole shader is one the shader cannot spare), and
never between a TEX and a read of its block.


**line 1253, in `compile_nir_inner`, above `if draw_mode {`**

draw_mode only: legacy's register assignment is checked into
shader_blobs.h and must stay byte-identical (docs/B1's own
"Coexistence" section) -- reordering its instructions, even to a
provably equivalent schedule, changes the checked-in blob for no
reason legacy needs.


**line 1262, in `compile_nir_inner`, above `if is_fragment {`**

Uniformity classification (fragment shaders only — vertex/setup shaders
have no per-pixel divergence to hoist away from). Roots: any read of a
per-vertex-staged uniform (Ubo::Uniform — constant across the triangle,
e.g. inv_area, UV/frag_pos/z of v0..v2) or a true shader constant
(Ubo::Fixed, excluding the per-pixel edge-attr use of the same variant).
See opt::classify_uniform for the propagation rule and why DDX/DDY are
unconditional roots on this architecture.


**line 1305, in `compile_nir_inner`, above `if gl_position.is_some() {`**

r4: the perspective-divide epilogue's scratch, reserved only when
that epilogue actually runs (gl_position.is_some(), gated the same
way the epilogue itself is emitted below) -- draw mode has none,
wanting r0..r3 left as the RAW clip coordinates BorgSetupRom does
its own homogeneous divide from, so reserving r4 there would only
shrink its register pool for nothing.


**line 1321, in `compile_nir_inner`, above `extra_reserved.extend_from_slice(&cx.cp.regs[..cx.cp.count]);`**

Only the cx.cp.regs prefix actually pinned (cx.cp.count) --
reserving the whole 12-register pool regardless of use starved the
general ALU pool for a shader that only pins a few (a 4x4 matmul
alone needs more than 30-17 live registers at once, spilling to
r0/0.0 with no real spill support -- see regalloc's own doc).
Without reserving even that prefix, regalloc's general pool could
hand one of them to an ordinary ALU temporary, clobbering a pinned
LOAD-address constant -- latent since no vertex shader pinned any
before draw mode's LOADs.


**line 1332, in `compile_nir_inner`, above `for (c, v) in frag_out.iter().enumerate() {`**

Fragment: colour → r26/27/28; edge-function attrs occupy r0/r1/r2; the
TEX result occupies a fixed 4-reg block r20..r23.


**line 1336, in `compile_nir_inner`, above `forced.insert(*def, if c == 3 { 24 } else { 26 + c as u8 });`**

Alpha is r24, not r29 -- r29 is the interpolated depth
output, so the naive 26+c would collide with it.


**line 1344, in `compile_nir_inner`, above `for &f in &cx.fattr_dsts {`**

FATTR's rd..rd+2 block, pinned to r10 -- matching the hand-written
fragment shaders' own FATTR(rd=10, ...) convention exactly (see
BorgDrawTests.scala's fs), so every load_input call reuses the
same 3-register slot rather than leaving it to the general pool
(which has no idea the hardware write spans 3 registers).


**line 1355, in `compile_nir_inner`, above `extra_reserved.extend_from_slice(&[4, 21, 22, 23, 25, 26, 27, 28, 29]);   // r25 is the ki`**

r4 (unused by a fragment shader, but reserved here too -- matching
every existing fragment compile's register assignment exactly,
rather than only freeing r4 where it is actually unused, which
reassigns everything after the first spot that would have taken
it and changes the checked-in shader_blobs.h), r20-23 TEX (the
full RGBA result block, needed whenever there is a TEX call in
either mode), r26-29 outputs.


**line 1364, in `compile_nir_inner`, above `extra_reserved.extend_from_slice(&[5, 6, 7, 10, 11, 12]);`**

r5-7: the draw front end's perspective-correct barycentrics,
read directly out of fixed registers by every load_input call
(docs/B1_geometry_front_end.md) -- must survive the whole
shader, since load_input calls are interspersed with other
computation rather than read once up front. r10-12 (FATTR's
pinned result block, forced above) are already excluded from
the free pool via `forced`; listed again here for clarity,
not because it changes anything.


**line 1377, in `compile_nir_inner`, above `extra_reserved.extend_from_slice(&[0, 1, 2]);`**

r0-2: edge-function attrs, read only by legacy's own
interpolation-weight computation below -- draw-mode never
touches them, using r5-7 directly instead, so reserving them
there too would shrink its pool for no reason.


**line 1383, in `compile_nir_inner`, above `extra_reserved.extend_from_slice(&cx.cp.regs[..cx.cp.count]);`**

cx.cp.regs[..cx.cp.count]: r17-19(+23), the actually-pinned
prefix of this shader's constant registers (see cx.cp.regs
above) -- needed in both modes. KNOWN GAP, not fixed here: a
register-pressured draw-mode VERTEX shader's own general ALU
pool can also reach r17-19 (nothing there knows they are spoken
for by the paired fragment shader) and clobber them before the
fragment shader ever runs -- confirmed on real hardware
(cube.vert's address-chain LOADs did exactly this, silently
zeroing cube.frag's lightDir). r0-4 cannot be the fix either:
the draw front end's raster ROM destroys them on every pixel
(see cx.cp.regs's own comment). Needs either lowering cube.vert's
register pressure or a firmware-side re-poke of the fragment's
consts after vertex processing, neither of which is a
register-allocation change.


**line 1398, in `compile_nir_inner`, above `if frag_out[3].is_some() {`**

r24 (alpha) only when it is actually an output. Reserving it
unconditionally would shrink the allocator's pool for every
existing shader to no purpose.


**line 1406, in `compile_nir_inner`, above `if draw_mode {`**

An instruction reads one window word at most: a second one is copied into a register first
(x + 0 in integer arithmetic, bit for bit; r30 - r30 is the zero).


**line 1465, in `compile_nir_inner`, above `let mut words: Vec<u32> = Vec::new();`**

Encode the selected+allocated instructions into Borg machine words, pinning
shader inputs to the firmware's uniform convention. The Borg core has ONE
uniform read port: funct3 is a selector (1=rs1, 2=rs2, 3=rs3 reads the
uniform indexed by that register field), so each op reads at most one
uniform. cube.c's transform is position×MVP, so positions are pre-loaded
into high GPRs (firmware idiom `FADD g, u<comp>, r30, f3=1` → g = u[comp])
and the MVP column is read in place as the single uniform operand. `mov`
ops are register copies handled by coalescing (cx.skipped).


**line 1522, in `compile_nir_inner`, above `if matches!(i.mnem, "EXPUSH" | "EXELSE" | "EXPOP") {`**

Execution-mask ops: no destination, and EXPUSH's single source is a
condition rather than an arithmetic operand, so they bypass the
operand folding below entirely.


**line 1536, in `compile_nir_inner`, above `if i.mnem == "SOUT" {`**

SOUT/FATTR (docs/B1_geometry_front_end.md): the 10-bit index is not
a register operand, so it does not fit encode()'s rd/rs1/rs2/rs3
shape -- swz[0] carries it instead (see the draw-mode load_input
and store_output codegen). u8 covers every index this compiler can
produce today (record_shift=8, five varying components); a future
shader with more needs swz widened, not a silent wraparound, hence
the assert rather than an `as u16` truncation.


**line 1561, in `compile_nir_inner`, above `let posw = i`**

pos.w fold: under the 3-component-position ABI pos.w is the constant 1.0,
so a multiply by it is the identity. col3·pos.w (+acc) collapses to a load
of col3 (added to the accumulator) — exactly the hand-written shader's
direct col3 bias, and avoids reading pos.w from u3 (which holds color.r).


**line 1644, in `compile_nir_inner`, above `if is_vertex && gl_position.is_some() {`**

Fixed-function epilogue (vertex only): perspective divide + viewport. The
body left clip coords in r0..r3 (uniforms u8..u23 are viewport-baked by the
firmware's cache_ts_mvp, so screen = clip'/w covers the full viewport map).
r4 = 1/clip_w; screen_x/y and ndc_z = clip'/w → r0/r1/r2, snooped by the
sequencer. Matches seq_vert_shader's tail; HALT terminates.


**line 1656, in `compile_nir_inner`, above `words.push(0x0000_0000);`**

Fragment, or a draw-mode vertex shader (its varyings leave through
SOUT, no fixed-function epilogue): HALT terminates. Without it the
core keeps fetching past the program, which only appears to work in
simulation because memory after the blob reads as zero (= HALT).


**line 1694, in `compile_nir_inner`, above `let outputs: Vec<u8> = if is_vertex && gl_position.is_some() {`**

Serialize the .borg blob (software/borg/borg_spirb.c format). For the
autonomous TBR sequencer only instrs[] matter — they're uploaded straight to
SEQ_VERT_SHADER_ADDR with the conventions baked in; the output_regs list is
descriptive (the snooped screen regs r0..r2). The blob is the artifact the
firmware loads to replace the hand-written seq_vert_shader.


**line 1714, in `compile_nir_inner`, above `let window: Vec<(u8, u32)> = if is_vertex {`**

Draw mode: the constant window the firmware must fill (u25+ for a
vertex shader, u20+ for a fragment shader) and, for a vertex shader,
how many varying components it SOUTs (the record size).


**line 1741, in `compile_nir_inner`, above `if !out_len.is_null() {`**

Return the blob to the C caller (borgvk_pipeline.c stores it per stage and
ships it to the firmware over serial). out_len always reports the true size;
copy only if it fits so the caller can grow its buffer on mismatch.


## select.rs


**line 13, in `load_const`, above `let comps: Vec<(u32, u8)> = (0..n)`**

Vector constant (lightDir): a word in the existing
uniform memory window (u20+, draw_fs_const_offset)
rather than a pinned GPR. NOT a legacy-style GPR
pin: legacy's r17-19(+23) sit inside a register-
pressured draw-mode VERTEX shader's own reachable
general-pool range (the self.cp.regs reservation
comment has the full story -- a hardware-caught
bug, cube.vert's address-chain LOADs silently
zeroing cube.frag's lightDir), and r0-4 cannot be
the fix either (BorgRasterRom's "REGISTER CLOBBER
ABI" destroys them every pixel). A uniform-memory
read shares nothing with the GPR file vertex also
uses, so it cannot collide with vertex at all --
and unlike a real register spill, it costs no
extra instructions: the funct3 tag that marks an
operand as "read from memory" is already free on
every instruction, not a separate LOAD.


**line 45, in `load_const`, above `let comps: Vec<(u32, u8)> = (0..n)`**

Legacy: pin components to constant GPRs (firmware
writes them once via MMIO).


**line 63, in `tex`, above `let srcs = tex.srcs_as_slice();`**

Texture sample → TEX rd, u, v, ctl: R, G, B, A to rd..rd+3.
Only a plain implicit-LOD sample of a 2D texture so far;
anything else is reported rather than sampled wrongly.


**line 87, in `tex`, above `let (vd, vc) = if is_fetch && tex.coord_components == 1 {`**

A fetch of a 1D texture or texel buffer takes the integer texel in u;
v is unused, so u is passed twice.


**line 95, in `tex`, above `let has_w = !is_fetch && tex.coord_components >= 3 && !is_cube && self.is_fragment && self`**

A third coordinate (3D, 2D array) goes to TEXA as w; a cube's is the compiler's face math.


**line 99, in `tex`, above `let ld = srcs.iter().find(|s| s.src_type == nir_tex_src_lod || s.src_type == nir_tex_src_b`**

Explicit LOD: TEXA hands this lane's LOD to the TEX after it (w and the
depth reference are unused for a 2D sample, so r30 stands in for both).


**line 135, in `tex`, above `let ctl_bits = if is_txl { 2u32 << 21 } else { 1u32 << 16 };`**

Control word: operation 1 (fetch) in bits 17:16, texture 0, sampler 0,
read from the constant window (docs/B2_texture_unit.md).


**line 185, in `alu`, above `if self.draw_mode && (matches!(alu.op, nir_op_fadd | nir_op_fmul | nir_op_ffma`**

A scalar float constant read by float arithmetic has no register of
its own (the ISA has no immediates): give it a word in the constant
window, shared by value, the way vector constants get theirs.
Integer constants stay address-walk patterns (see `walk`).
Fragment integer arithmetic reads its constants the same way (the vertex
stage's integer constants are address math, handled by `walk`).


**line 238, in `alu`, above `nir_op_flt | nir_op_fge | nir_op_fneu | nir_op_feq => {`**

Float comparisons, built from FSTEP.

FSTEP(a) is 1.0 for a strictly positive and 0.0
otherwise (BorgLane.computeFstep), so `a < b` is
FSTEP(b - a) and the rest follow by swapping or
inverting. Subtraction is FADD(a, FNEG(b)) -- the
ISA has no subtract.

These exist for the execution mask: without them a
condition like `if (x < y)` is a NIR op borgc
cannot select, so EXPUSH would read a register no
instruction ever wrote. The result is 1.0 or 0.0,
and the mask tests raw bits against zero, so
both encodings land correctly without a bool
conversion.


**line 266, in `alu`, above `nir_op_fge => {`**

a >= b  ->  1 - FSTEP(b - a), as
FADD(1.0, FNEG(step)). The 1.0 comes from a
pinned constant register, same mechanism as
the existing self.cp.uniforms.


**line 303, in `alu`, above `nir_op_fneu => {`**

a != b  ->  FSTEP(a-b) + FSTEP(b-a). At most
one term is 1.0, so the sum is a clean 0/1
and needs no clamp.


**line 411, in `alu`, above `nir_op_ieq | nir_op_ine | nir_op_ilt | nir_op_ige | nir_op_ult | nir_op_uge if self.draw_m`**

linearToSrgb idiom → FSRGB: bcsel(fge(knee,x), x·12.92,
1.055·pow(x,1/2.4)-0.055). Recognised by the linear branch
fmul(x, 12.92); x is the value to sRGB-encode.
Integer comparisons give the 1.0 / 0.0 condition bcsel expects.


**line 466, in `alu`, above `let n = alu.def.num_components as usize;`**

General select: the ISA has none, but the condition
is a 0.0/1.0 float (see the comparisons above), so
else + cond * (then - else) is exact for both arms.
NIR leaves vector selects unscalarised: one per component.


**line 538, in `alu`, above `nir_op_u2f16 | nir_op_u2f32 => {`**

I2F is signed: convert x >> 1 and x & 1 and join them with one FMADD,
which rounds once.


**line 587, in `intrinsic`, above `let deriv = match intr.intrinsic {`**

Vector-wide quad derivatives → per-component scalar DDX/DDY,
registered in self.sel.vec_map so reads of the result resolve correctly.


**line 606, in `intrinsic`, above `let comps: Vec<(u32, u8)> = raw.into_iter().map(|v| (v, 0u8)).collect();`**

(The FP16 datapath needed DDX scaled by 32 here, because
|cross(ddx, ddy)|^2 underflowed below FP16's minimum
normal at 128x128. FP32 has no such floor.)


**line 612, in `intrinsic`, above `let is_pc = intr.intrinsic == nir_intrinsic_load_point_coord;`**

Draw front end: FATTR + the perspective-correct barycentrics
already in r5-r7 at fragment start (docs/B1_geometry_front_end.md),
one component at a time -- FATTR loads a component's three
per-vertex values into rd..rd+2, which the next FATTR overwrites,
so each component's FMUL/FMADD chain must consume its own before
requesting the next.

FATTR's rd..rd+2 is a single hardware-implied 3-register write,
exactly like TEX's rd..rd+3 (self.tex_dsts above) -- it must be
referenced the SAME way: one tracked def (fattr_rd) plus a
component offset resolved at encode time (self.fattr_dsts, mirroring
self.tex_dsts), never as bare `fattr_rd + 1`/`+ 2` integers. Those
never appear as any instruction's dst, so regalloc never assigns
them a register -- they silently resolved to r0 (whatever
instruction happened to be encoding "unresolved operand"),
discarding two of every three vertices' contribution to the
interpolation. Caught by a hardware test: dot(c, c) computed
wildly too large because the barycentric sum silently used
vertex 0's value for all three terms.


**line 633, in `intrinsic`, above `let base_index = if loc == 25 { self.flags.pntc_base() } else { 4 * loc.wrapping_sub(VARYI`**

gl_PointCoord (VARYING_SLOT_PNTC): the vertex stage's extra varyings, whose first index
the driver passes in the option word's top byte.


**line 670, in `intrinsic`, above `if self.weights.is_none() {`**

Interpolated varying. Borg has no fixed-function interpolation:
the shader computes it barycentrically. Emit the self.weights once
(w_i = e_i·inv_area; e0/1/2 = attrs r0/1/2, inv_area = u12), then
per component: w0·v0 + w1·v1 + w2·v2 over the per-vertex uniforms.


**line 690, in `intrinsic`, above `let var = loc.wrapping_sub(VARYING_SLOT_VAR0); // 0 = texcoord, 1 = frag_pos`**

Per-vertex uniform base per varying (sequencer convention,
each group stored (v2,v1,v0)): VAR0 texcoord → u13.., VAR1
frag_pos → u19.., depth → u28...


**line 718, in `intrinsic`, above `let dyn_off = intr.get_src(0).comp_as_uint(0);`**

layout(push_constant) reads. Reached here only after
borg_nir_passes.c's nir_lower_explicit_io turns the
pointer-deref access into this intrinsic -- WITHOUT
that pass this never arrives; it arrives as
load_deref instead, which is unselectable and DCEs
the whole shader to nothing (found empirically, not
assumed, the first time this was tried).

`base` is the field's static byte offset; `src(0)`
is a dynamic offset for array-indexed push constants,
which this does not support yet -- only a
compile-time-constant src(0) is handled, mirroring
how divergent LOOPS are refused rather than silently
mis-lowered. A non-constant offset is warned about
and the intrinsic is left unselected (DCE removes
whatever depended on it, same failure shape as an
unhandled op, not a wrong answer).

LOAD addresses whole 32-bit DRAM words
(LS_BASE + rs1<<2), so each 4-byte-aligned field
maps onto exactly one word -- push-constant data is
naturally word-granular already, no repacking
needed on the hardware side.


**line 764, in `intrinsic`, above `let reg = self.cp.alloc_reg();`**

KNOWN GAP, not fixed here: unlike the
load_const vector-constant case above,
this pins a GPR regardless of
self.draw_mode (the self.cp.regs reservation
comment has the vertex-collision
story) -- this value becomes LOAD's
own address operand, which must be an
actual register, not something the
uniform-memory-window trick can
resolve (that trick works for a value
OPERAND, not for the register that
reads memory in the first place).
Not exercised by cube.vert/cube.frag
(no push constants), so left as-is
rather than risk a change nothing
currently tests.


**line 781, in `intrinsic`, above `self.cp.uniforms.push((reg, word_idx));`**

RAW integer, not a float -- this
becomes rs1 for LOAD, a word INDEX,
not a shader-visible float value.


**line 807, in `intrinsic`, above `else if intr.intrinsic == nir_intrinsic_vulkan_resource_index {`**

load_ubo/store_output handled in the I/O pass below
(legacy) or inline here (draw mode).


**line 818, in `intrinsic`, above `const SSBO_SLOT_WORDS: u32 = 16384;`**

A storage buffer is a window of the draw's buffer region: binding b
starts SSBO_SLOT_WORDS * (1 + b) words above LS_BASE (the uniform
buffer keeps the first slot). The driver uploads it there and copies
back what the pass stored.


**line 900, in `intrinsic`, above `let comps: Vec<(u32, u8)> = [30u8, 31, 29, 8]`**

docs/B1_geometry_front_end.md, fragment ABI: FragCoord.xy = r30/r31
(the pixel centre), .z = r29, .w = r8 (= 1/w). Read in place like the
barycentrics; r8 has to survive the shader, so it is reserved.


**line 916, in `intrinsic`, above `self.sel.ubo.insert(intr.def.index, Ubo::Fixed(30));`**

r30 = VertexIndex at vertex-shader start
(docs/B1_geometry_front_end.md); referenced directly,
like the fragment stage's e0-e2/barycentrics, never
through self.sel.vec_map/regalloc (see resolve_op's Ubo::Fixed
arm -- checked before the alloc fallback for every
source, whether or not that source has a producing
BorgInstr of its own, which load_vertex_id does not).


**line 927, in `intrinsic`, above `const VERT_ATTRIB_GENERIC0: u32 = 15;`**

Vertex input as a typed fetch (docs/B1_geometry_front_end.md, "Vertex
input"): attribute L is texture slot 128 + L, a 4096-wide linear image
of tight elements whose format converts the
attribute. `TEX` fetches element VertexIndex (or InstanceIndex for an
instance-rate binding); missing components read (0, 0, 0, 1).


**line 938, in `intrinsic`, above `let ctl = if loc >= 24 {`**

A line's other endpoint (location + 24): the control word is built from
the endpoint's own, so the pair costs one window word.


**line 961, in `intrinsic`, above `let comp0 = intr.get_const_index(NIR_INTRINSIC_COMPONENT) as u8;`**

The fetch fills a fixed four-register block (see the vertex `forced`
below); every component a shader uses is copied out of it at once, so
the next fetch can reuse the block and the rest of the compiler sees
ordinary scalars. IOR x, x copies the bits, integer formats included.


**line 976, in `intrinsic`, above `let is_input = intr.intrinsic == nir_intrinsic_load_input;`**

A draw-mode vertex shader's `in` attributes are read from
the same per-vertex UBO region the firmware fills for
cube.vert: input location L is `vec4 attr[]` at word
16 + 144*L (36 vertices * 4 words), stride 4 words,
indexed by VertexIndex -- the shape load_ubo already
lowers below (cube.vert's position is L=0 at word 16,
its texcoord L=1 at word 160).


**line 986, in `intrinsic`, above `const VERT_ATTRIB_GENERIC0: u32 = 15;`**

self.vertex_id_def is always Some by now if the shader reads
gl_VertexIndex anywhere, which every load_ubo here does
transitively via the descriptor's own address term
(see decompose_vertex_offset's doc) even when THIS
particular load's own offset has no vid-scaled term
(the MVP columns); u32::MAX is a sentinel no real NIR
index can equal, for the degenerate shader that never
reads gl_VertexIndex at all.


**line 1016, in `intrinsic`, above `let base_words = if base_words >= 0 && !is_input {`**

A uniform buffer other than binding 0 is a window of the draw's buffer
region, SSBO_SLOT_WORDS * (1 + binding) words above LS_BASE.


**line 1030, in `intrinsic`, above `if self.cp.vs_window && base_words >= 16384 {`**

Every constant operand is a fresh vreg from
const_int_operand (a window uniform or a pinned
GPR), never the register number itself: `srcs`
holds SSA/vreg indices, and a small GPR number
could collide with a real NIR SSA index.
The slot size seeds the window so each further buffer's base is a sum.


**line 1049, in `intrinsic`, above `let addr = match (stride_words, self.const_addr_chain) {`**

The running address register this load's words come
from. A constant address (stride_words == 0, the MVP
columns) continues an earlier load_ubo's chain when it
left off exactly here (self.const_addr_chain), instead of
pinning every one of the (here) 16 word indices to its
own constant register -- which does not fit the ~4-word
budget (see self.const_addr_chain's doc).


**line 1058, in `intrinsic`, above `(0, _) if !self.cp.vs_window && base_words >= 65536 => {`**

A pinned GPR can seed the chain directly. A
window uniform cannot: the chain's IADD would
then read two uniforms (it and the +1), and an
instruction has only one uniform operand. It
goes through the strided path below instead,
with stride 0 (VertexIndex * 0 + base).
A base past 16 bits does not fit a staged register: a window constant.


**line 1110, in `intrinsic`, above `if stride_words == 0 && n > 1 {`**

Leave the chain one past the last word THIS load
read, for a later constant-address load_ubo to pick
up (a stray unconsumed IADD if none does, which dce
then drops like any other dead instruction).


**line 1157, in `intrinsic`, above `self.outs.mrt[(loc - 4) as usize] = Some(comps[0]);`**

Several colour attachments: FRAG_RESULT_DATA0.. , one value per attachment.
A constant (a window word) is copied into a register where it is packed.


**line 1192, in `intrinsic`, above `let desc = if self.flags.packed() && !self.flags.multi_target() {`**

The destination pixel as floats (nir_lower_blend's fb fetch): `TLD` returns the
attachment's word, unpacked here by the same description the pack block uses.
(kind, bits per channel, channels, swap): the generic packed description, or R8_UNORM.


**line 1254, in `intrinsic`, above `let nch = self.flags.half_channels();`**

Half floats: R, G in word 0, B, A in word 1. A channel's bits become the
float's: exponent rebased by 112, an infinity or NaN by another 112, and a
denormal is its mantissa times 2^-24.


**in `intrinsic`, above `let _ = vc;`**

see decompose_vertex_offset's doc: draw-mode producers are scalar (c=0) by construction


## isel.rs


**line 3, in `(file level)`, above ``**

Small NIR-facing instruction-selection helpers used by the main selection
walk in lib.rs::borgc_compile_nir.


**line 9, in `(file level)`, above `pub(crate) fn resolve_vm(`**

Resolve a (def, component) through the vec/mov construction map to the true
underlying scalar producer + component. Free function (not a closure) so the
selection walk can resolve and mutate `vec_map` in the same loop.


**line 38, in `borg_isel`, above `nir_op_frcp => Some("FRCP"),`**

Fragment transcendentals — now real Borg HW ops. (ddx/ddy are NIR
intrinsics, handled in the intrinsic selection path, not here.)


**line 56, in `borg_isel`, above `nir_op_i2i16 | nir_op_i2i32 | nir_op_u2u16 | nir_op_u2u32 => Some("mov"),`**

Integer↔int bit-size conversions are register copies: a GPR holds
the full datapath word either way.


## opt.rs


**line 3, in `(file level)`, above ``**

IR-to-IR optimization passes over the selected Vec<BorgInstr> program, run
after instruction selection and before register allocation.


**line 10, in `(file level)`, above `pub(crate) fn dce(prog: &mut Vec<BorgInstr>, out_roots: &[u32]) {`**

Dead-code elimination. Because every load_ubo is pinned to a fixed uniform,
the UBO byte-offset arithmetic (iadd/ishl from gl_VertexIndex, descriptor
index math) feeds only the dropped offsets and is dead. Mark-and-sweep from
the store_output roots back through the def→use chain to fixpoint.


**line 18, in `dce`, above `let side_effecting = |i: &BorgInstr| i.dst == crate::NO_DST;`**

An instruction with no destination is a SIDE EFFECT, not dead code. The
execution-mask ops and STORE write no register, so nothing can ever make
their dst reachable from an output root -- a liveness-only rule deletes
every one of them. That silently removed EXPUSH/EXELSE/EXPOP from a
shader whose `if` had otherwise lowered correctly, leaving both arms
running unconditionally: the exact miscompile the mask exists to fix.
Their sources are live too, or the condition feeding EXPUSH would be
dropped in turn.


**line 56, in `dce`, above `pub(crate) fn schedule_for_pressure(prog: &mut Vec<BorgInstr>, out_roots: &[u32]) {`**

List-schedule `prog` to reduce peak register pressure, without changing
what it computes. Runs after DCE and FMADD fusion, right before
regalloc, and reorders only WITHIN maximal runs between side-effecting
instructions (STORE, EXPUSH/EXELSE/EXPOP, branches -- anything with no
`dst`): those keep their exact original relative order and act as full
barriers nothing crosses, so their own semantics never change. Within a
run, an instruction only ever becomes eligible once every source it
reads is already available (defined by an earlier run, or already
scheduled within this one) -- a true dependency is never violated, only
reordered around. Among eligible instructions, greedily picks whichever
one leaves the FEWEST values live immediately afterward: consuming a
value at its last remaining use frees a register right away, so
finishing a chain that is already live is always preferred over
starting a new, longer-lived one (typically a LOAD).

This exists because a straightforward source expression like
`mat4 * vec4` lowers, in NIR's own instruction order, to "load all 16
matrix words, THEN multiply" -- correct, but needlessly holding all 16
(plus the position vector) live at once when loading a column and
consuming it immediately would do. Caught in exactly that shape:
cube.vert's compiled MVP multiply needed a peak of 21 live registers
with nothing else touching them at that point, tight enough that
reserving even 3 more (for the paired fragment shader's own pinned
constants, a separate bug) caused real spills.


**line 83, in `schedule_for_pressure`, above `let mut runs: Vec<(Vec<BorgInstr>, Option<BorgInstr>)> = Vec::new();`**

Split into maximal side-effect-free runs, each followed by the
side-effecting instruction (if any) that ended it.


**line 89, in `schedule_for_pressure`, above `if instr.dst == crate::NO_DST || instr.mnem == "TEX" || instr.mnem == "FATTR" {`**

A TEX fills a fixed register block: nothing may move across it. Every FATTR fills
the same three registers, so the same holds for it.


**line 116, in `schedule_run`, above `let mut remaining_uses: HashMap<u32, i32> = HashMap::new();`**

remaining_uses[v]: how many not-yet-scheduled reads of v are left --
every source occurrence in this run, plus one more if v is also
needed after it (the trailing side-effecting instruction, a later
run, or a shader output). Reaching 0 means v can be freed.


**line 132, in `schedule_run`, above `let mut pending_deps: Vec<i32> = run`**

pending_deps[i]: how many of instruction i's sources are defined
WITHIN this run and not yet scheduled. Only these gate readiness --
a source from an earlier run is available from the start.


**line 148, in `schedule_run`, above `let mut urgency: Vec<usize> = vec![0; n];`**

urgency[i]: the original index of the earliest instruction that
ultimately needs instr[i]'s result, propagated backward through
chains -- an address-chain LOAD whose value only the very first
matmul term needs is urgent even though its own defining chain sits
right next to three OTHER, unrelated chains whose results nothing
needs until much later. Without this, every pure-load instruction
ties on (produces, frees) alone (delta = +1, nothing to free yet),
and the tie-break falls back to original NIR order -- i.e. no
reordering happens at all, because nothing distinguishes "this
column's load is needed by the very next multiply" from "this
column's load is needed sixteen instructions from now."


**line 170, in `schedule_run`, above `let mut last_dst: Option<u32> = None;`**

Depth-first tie-break: once several equally-urgent chains are all
simultaneously ready (e.g. four independent MVP-column address
chains), keep pulling on whichever one was just advanced instead of
round-robining between them -- finishing one chain fully before
starting the next keeps its own scratch registers alive for less
total time than interleaving all four at once would.


**line 183, in `schedule_run`, above `let produces = i32::from(remaining_uses.get(&instr.dst).copied().unwrap_or(0) > 0);`**

1 if this instruction's own result survives past it, 0 if
this was its last remaining use too (dead on arrival).


**line 220, in `schedule_run`, above `pub(crate) fn fuse_fmadd(`**

Peephole: re-fuse FMUL+FADD into FMADD. nir_lower_alu_to_scalar split the
source ffma into a multiply and an add; fuse FADD(d, m, c) where m=FMUL(a,b)
is used only here back into FMADD(d, a*b + c). Halves the matrix-multiply op
count — both faster and necessary to fit SPIRB_MAX_INSTRS (72).


**line 262, in `fuse_fmadd`, above `let n_uniform = [a, b, c].iter().filter(|&&s| uniform_like(s)).count();`**

An instruction reads at most one uniform operand (funct3
selects the slot); a second one would be left as r0 in the
encoding. x * u20 + u24 therefore stays a FMUL and a FADD.


**line 293, in `fuse_fmadd`, above `pub(crate) fn classify_uniform(prog: &[BorgInstr], roots: &HashSet<u32>) -> HashSet<u32> {`**

Which values are "primitive-uniform" — constant for the whole triangle,
safe to compute once in the setup stage instead of once per fragment.

Borg-specific axiom: DDX/DDY results are ALWAYS uniform. Borg has no
fixed-function interpolator — every fragment varying is computed in the
fragment shader itself as a barycentric-weighted sum of per-vertex uniform
data, i.e. every varying is (by construction) an affine function of screen
position within one triangle. The derivative of an affine function over
that triangle is a constant, regardless of which varying is being
differentiated — so this holds for any Borg fragment shader that uses
dFdx/dFdy, not just one in particular.

From that axiom, uniformity propagates the ordinary way: a value is
uniform iff every one of its operands is uniform — either a `root` (a
per-vertex-staged uniform read or a true shader constant, classified by
the caller from its knowledge of the `Ubo` map) or another already-uniform
result. `prog` must be in forward topological order (true for the
selection walk's own output — DCE only removes entries and FMADD fusion
only merges adjacent producer/consumer pairs in place, so the order
invariant survives both), so one linear pass suffices — no fixed point
needed, unlike the backward DCE walk above.


**in `classify_uniform`, above `_ => i,`**

no consumer within this run (feeds an out_root, or dead): its own position is the best guess


## regalloc.rs


**line 3, in `(file level)`, above ``**

Linear-scan register allocation over the selected virtual-register program.


**line 8, in `(file level)`, above `pub(crate) fn regalloc(`**

Linear-scan register allocation (Poletto & Sarkar) for the values our
instruction selection defines (the ALU results). Each is live from its def
to its last use; we assign physical GPRs r0..r29 (r30/r31 are the special
coordinate registers), reusing a register once its value dies. Reports the
allocation, the peak register pressure, and any spills. Values that only
appear as sources (shader inputs) are not allocated here — they map to
uniforms/attributes when the I/O lowering lands.


**line 30, in `regalloc`, above `if instr.dst != crate::NO_DST {`**

Instructions with no destination (the execution-mask ops, STORE)
must not register one: treating the sentinel as a def would keep a
physical register alive for a value nothing produces.


**line 46, in `regalloc`, above `let mut busy: HashMap<u8, Vec<(usize, usize)>> = HashMap::new();`**

Reserve the pre-colored output regs (gl_Position r0..r3) from the
general pool. r4 (the perspective-divide epilogue's scratch) is the
caller's to add to extra_reserved when that epilogue will actually
run (a legacy vertex shader, always; a draw-mode one, never -- see
lib.rs) -- it used to be unconditional here, back when every vertex
shader ran that epilogue.
`soft` registers (the TEX result block, the colour outputs) are free for temporaries
outside the interval of the pre-coloured value that owns them: a TEX block from the TEX to
its last use, an output from its definition to the end of the shader.


**line 92, in `regalloc`, above `let mut keep = Vec::with_capacity(active.len());`**

Expire intervals that ended before this value starts. Reserved output
registers are never returned to the pool — the epilogue still reads them
after the body, beyond the body-local last-use we computed here.


## encode.rs


**line 3, in `(file level)`, above ``**

Instruction encoding and blob serialization: turns a fully register-allocated
Vec<BorgInstr> word list into the .borg blob bytes the firmware loads.


**line 7, in `(file level)`, above `pub(crate) fn emit_blob(words: &[u32], outputs: &[u8], consts: &[(u8, u32)],`**

Serialize a Borg shader to the .borg blob format parsed by spirb_parse()
(software/borg/borg_spirb.c, docs/spirb.md): a 6-byte header
(num_instrs, num_uniforms, num_attributes, num_outputs, num_consts,
extensions), then num_instrs LE u32 instruction words, then the uint8
uniform/attribute/output/const register lists, then num_consts LE u32
constant values. We emit instructions, the output-register list, and the
const-register list. The consts (e.g. a legacy cube.frag's lightDir in
r17-19) are written once to the GPRs by the firmware (spirb_parse →
BORG_GPU->gpr[const_regs[i]] = const_vals[i]) and persist across the
autonomous render. Uniforms are read inline via funct3, so there is no
host uniform/attribute interface list.

const_vals are LE u32: one datapath float (IEEE binary32 bits) or a raw
integer such as a push-constant word index, written to the GPR unchanged.

`draw` is Some for a draw-mode compile (docs/B1_geometry_front_end.md):
extensions bit 0 is set and the draw extension follows const_vals --
num_varyings (the SOUT component count, which sizes the triangle
records), num_window, the constant window's u-indices, then their LE
u32 values. A legacy compile passes None and its bytes are unchanged.


**line 59, in `emit_blob`, above `pub(crate) fn encode(mnem: &str, rd: u8, rs1: u8, rs2: u8, rs3: u8, funct3: u32) -> Option`**

Encode one Borg instruction to its 32-bit word, through the Op table generated
from hardware/borg/isa/opcodes (`make isa`). `funct3` selects a
uniform operand (0=none, 1=rs1, 2=rs2, 3=rs3); `rs3` is used by the R4-type
ops, FMADD and TEX.


**line 71, in `encode`, above `"TEX" => r4(0x0400_0004),`**

TEX rd, u, v, ctl: R4-type funct2 = 2 (docs/B2_texture_unit.md).
funct2 = 1 was FTEX, retired with the legacy texture unit.


**line 100, in `encode`, above `"ATTIDX" => un(0x9000_0000),`**

Colour attachments (docs/B3_colour_formats.md): the attachment this pass renders, and
this lane's current destination word.


**line 105, in `encode`, above `"LOAD" => un(0x4400_0000),`**

--- Extended ISA (hardware Instructions.scala, same funct7 << 25) ---
LOAD/STORE address LS_BASE + (rs1 << 2): the operand is a word
INDEX, not a byte address (words are the natural unit, and the
scheme predates FP32, when a register held only 16 bits). STORE has no destination.


**line 111, in `encode`, above `"EXPUSH" => un(0x5400_0000) & !(0x1F << 7),`**

Execution mask. None of these has a destination; EXPUSH reads its
condition from rs1.


**line 120, in `encode`, above `pub(crate) fn encode_sout(rs2: u8, index: u16, funct3: u32) -> Option<u32> {`**

Encode SOUT (docs/B1_geometry_front_end.md): store `rs2` as the current
invocation's corner's output component `index`. Separate from [`encode`]
for the same reason as [`encode_branch`] -- `index` is packed into the
otherwise-unused rs1/rd fields as `(index >> 5)`/`(index & 31)`, not a
register operand.


**line 137, in `encode_sout`, above `pub(crate) fn encode_fattr(rd: u8, index: u16) -> Option<u32> {`**

Encode FATTR (docs/B1_geometry_front_end.md): load component `index`'s
three per-vertex values into `rd..rd+2`. `index` is packed into rs2/rs1
the same way [`encode_sout`]'s is.


**line 148, in `encode_fattr`, above `pub(crate) fn encode_branch(mnem: &str, rs1: u8, target: u16) -> Option<u32> {`**

Encode a conditional branch. Separate from [`encode`] because the target is
packed into the otherwise-unused rs2 and rd fields as `(target >> 5)` and
`(target & 31)` -- it is not a register operand, and passing it through the
register-shaped signature above would invite treating it as one.


**line 169, in `encode_branch`, above `#[test]`**

The encodings are a mirror of the hardware's Instructions.scala. A
divergence here is silent: the shader would execute a different opcode
rather than fail to load, so the funct7 bases are pinned explicitly.


**line 174, in `blob_constants_are_raw_little_endian_words`, above `let b = emit_blob(&[0], &[], &[(17, 1.0f32.to_bits()), (23, 9)], None);`**

A constant reaches its GPR unchanged, so the blob must carry the
FP32 bits themselves: 1.0f = 0x3F800000, not an FP16 0x3C00.


**line 186, in `blob_carries_the_draw_extension`, above `let b = emit_blob(&[0], &[], &[], Some((7, &[(25, 4), (26, 160)])));`**

A draw-mode vertex shader: 7 varying components and two window
constants (u25 = 4, u26 = 160), after const_vals.


**line 213, in `sout_and_fattr_split_the_index_over_two_5_bit_fields`, above `let s = encode_sout(9, 515, 2).unwrap();`**

index = 515 = 0b10000_00011: high 5 bits 0b10000 = 16 in
rs1(SOUT)/rs2(FATTR), low 5 bits 0b00011 = 3 in rd(SOUT)/rs1(FATTR)
-- the same split as a BRZ/BRNZ branch offset (see
branch_offset_splits_across_rs2_and_rd).


**line 234, in `tex_is_r4_type_funct2_2_with_the_control_word_in_rs3`, above `let w = encode("TEX", 20, 15, 7, 9, 0).unwrap();`**

Instructions.encodeR4Type(rs3, FUNCT2_TEX = 2, rs2, rs1, rd): the
opcode's FMA bit, funct2 at 26:25, rs3 at 31:27.


**line 253, in `store_and_mask_ops_leave_no_destination`, above `assert_eq!((encode("STORE", 31, 5, 7, 0, 0).unwrap() >> 7) & 0x1F, 0);`**

rd carries no meaning for these; a stray value there would name a
register the hardware does not write but a reader would believe.


## compute.rs


**line 3, in `(file level)`, above ``**

Compute shaders (docs/B0_compiler_contract.md, "Compute shaders").

A separate, deliberately small backend: compute shares nothing with the draw front end's
register conventions, so it does its own selection, then hands the program to borgc's
linear-scan `regalloc` (values are virtual registers until the end).

Conventions implemented here:
  * r30 = LocalInvocationIndex, WorkgroupID in the uniforms u29/u30/u31.
  * Constants have no immediate form: each distinct constant gets a register the driver
    presets over MMIO (returned as (register, value) pairs).
  * A buffer binding (set, binding) lives in a fixed window of GPU memory the driver fills
    and reads back: word index SLOT_BASE_WORDS + (set * 16 + binding) * SLOT_WORDS.  LS_BASE
    stays 0, so LOAD/STORE take full word indices.
  * robustBufferAccess: every buffer word index is masked to the binding's window (word_addr);
    the host zero-fills the window past the buffer and copies back only the buffer's bytes.
  * atomics are per-lane critical sections (EXPUSH(LocalInvocationIndex == i)).


**line 33, in `(file level)`, above `const PHYS: u32 = 0x4000_0000;`**

Operand ids at or above this are physical registers (r30, preset constants); below, virtual.


**line 36, in `(file level)`, above `pub(crate) const WG_BASE_REG: u32 = 12;`**

First of three registers (x, y, z) holding the dispatch's workgroup origin: 0 normally, the
slice start when the driver splits a grid over several simulator runs.


**line 40, in `(file level)`, above `struct CI {`**

One selected instruction. `srcs` are virtual or physical ids; `f3` is the funct3 operand slot.


**line 91, in `fresh`, above `fn creg(&mut self, c: u32) -> u32 {`**

A register the driver presets to `c` before the dispatch (physical, taken from r29 down).


**line 164, in `src`, above `fn word_n(&mut self, a0: V, c: u32) -> V {`**

Address of word `c` of a multi-word access whose first word is `a0`.  A constant address
would take one preset register per word (there are only 18), so word c > 0 is the first
word's register plus the shared constant c, as long as that stays inside the window.


**line 192, in `word_addr`, above `let w = self.add(w, V::C(extra_words));`**

robustBufferAccess: the access stays inside the binding's window. The host zero-fills
the window past the buffer and copies back only the buffer's bytes, so an out-of-range
load reads zero and an out-of-range store is discarded; neither reaches another buffer.


**line 333, in `compile`, above `em.op("IADD", rd, PHYS + 29 + c as u32, PHYS + WG_BASE_REG + c as u32, 1);`**

IADD rd, u(29+c), r(12+c) with funct3 = 1: rs1 is the uniform operand;
r12-r14 hold the grid origin (0 unless the driver split the dispatch).


**line 378, in `compile`, above `let o = em.src(i.get_src(0), 0);`**

Push constants are one more window (slot 32) that the
host fills at dispatch.


**line 429, in `compile`, above `let ws = (*nir).info.workgroup_size;`**

A memory barrier is nothing here: a store is visible at once. A control
barrier is nothing with one invocation per workgroup.


**line 438, in `compile`, above `let store = i.intrinsic == nir_intrinsic_store_shared;`**

Workgroup-shared variables are one more window (slot 33); workgroups run
one after the other, so they can all use the same one.


**line 536, in `compile`, above `#[no_mangle]`**

Compile a compute shader.  `words` receives the program, `regs` (register, value) pairs the
driver must preset, `local` the LocalSize.  Returns 0 on success.

# Safety
`nir` is a valid compute `nir_shader`; the buffers have the stated capacities.


**in `borgc_compile_compute`, above `prog: Vec<CI>,`**

everything else; EXELSE/EXPOP are CI with mnem "RAW" and srcs[0] the word

