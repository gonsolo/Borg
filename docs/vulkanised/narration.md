# Narration draft

A first draft per slide, to be put into the speaker's own words. About 130 words per minute;
the minutes per part follow `storyboard.md`. `[...]` marks what is on screen.

## 0. Cold open (0.5 min)

[vkcube on the ULX3S over HDMI, no words, 30 s]

## Title

Hello, I am Andreas Wendleder. This talk is about Borg, an open-source GPU, and about what it
took to run Vulkan on it: from the unmodified vkcube you just saw, down to the gates.

## 1. What Borg is (2 min)

[An open GPU you can read end to end]

Borg is a GPU small enough for one person to read from end to end. The GPU itself is about
twelve thousand lines of Chisel. It has an FP32 shader core that runs two-by-two quads, a
tile-based rasterizer, a texture unit, multisampling and compute. Around it sits Hutt, a small
RISC-V CPU. The driver is borgvk, a native Mesa Vulkan driver written in Rust, with its own
shader compiler, borgc. The same source builds for three targets: a ULX3S board with a Lattice
ECP5, a Nexys Video with an Artix-7, and a GF180 chip. All of it is open source.

[One driver, three back ends]

The application, here the Khronos cube or the conformance tests, talks to Mesa's Vulkan runtime
and its window system code. Below that is borgvk. It compiles shaders with borgc and records a
command stream. That stream goes to one of three places: to a simulator of the RTL, to Linux on
the board, or to Linux in QEMU. On the board and in QEMU, a DRM driver in Rust takes it. The
driver code is the same in every case. The simulator is where the conformance tests run.

## 2. What Vulkan 1.0 asks of hardware (6 min)

[Requirements that became hardware]

Vulkan 1.0 is a small API by today's standards, but it still asks a lot of a small GPU. This
table is the short version: on the left what the specification requires, on the right what Borg
does about it. I will go through the rows that changed the hardware most.

[A graphics queue implies compute]

The first surprise for a graphics-first design: every queue family that does graphics must also
do compute. So Borg runs workgroups on the same shader core, with barriers, atomics and shared
memory. This matters far beyond compute applications. The conformance suite runs its shader
precision tests as compute dispatches. Thousands of shader cases go through this path, so
compute had to work early, not late.

[Derivatives need quads]

Fragment shaders can ask how a value changes from one pixel to the next: dFdx and dFdy. The
texture unit needs the same thing to pick a mip level. The answer is the quad. The fragment core
runs four neighbouring pixels on four lanes, and a derivative is a difference between two lanes.
A core with one lane would be smaller, but it can only return zero, and the tests notice.

[Descriptors become hardware tables]

In Vulkan, images and samplers are described by descriptors. In Borg, the texture unit reads
those descriptions directly from tables in memory, and the driver fills them. The unit handles
fifty-one formats, mipmaps, every address mode and border colour, gather, depth compare and
offsets. Render targets are stored in the same tiled layout as textures, so rendering to a
texture and sampling it later needs no copy.

[The draw front end]

A draw is one register write. The hardware assembles primitives itself: lists, strips, fans,
index buffers, primitive restart and instancing. It runs the vertex shader once per triangle and
moves varyings out and back with two instructions. Triangle setup works in homogeneous
coordinates, so there is no clipper: vertices behind the eye come out exact.

Two more rows in short. Colour formats beyond eight-bit RGBA are raw attachments: the shader packs
the format, and blending reads the old value back. And four-times multisampling renders each tile
in several passes, which costs time, not area.

## 3. A driver and compiler for 32 registers (5 min)

[borgvk and borgc]

The driver uses Mesa's runtime for objects, synchronization and the window system. borgvk is
about twelve thousand lines of Rust. It started in C and moved to Rust step by step, with the
driver working after every commit. The compiler, borgc, takes NIR after Mesa's standard
lowering. One instruction selector serves graphics and compute. Each lane has thirty-two
registers, and the instruction set is moving to a RISC-V subset plus GPU instructions.

One rule runs through the compiler: refuse instead of guess. If borgc cannot compile a shader
correctly, it says so, and the pipeline fails. That is an honest failure in the test results. A
shader that is compiled wrongly can pass a test by luck, and then the bug hides.

[One source of truth for the ISA]

The instruction set lives in one file, in the format of RISC-V's riscv-opcodes. From it come the
hardware decoder, the C header and the compiler's encoder. Adding an instruction is one line, and
the three can never disagree. Every compiler change is checked against a corpus of shaders
recorded from the conformance suite.

[Per-draw work out of the fragment shader]

One example of what a compiler for a small GPU does. Some of what a fragment shader computes
depends only on push constants. That value is the same for every pixel of the draw. Mesa's
nir_opt_preamble finds that part. Other GPUs run such a preamble once per draw on a separate
unit. Borg has no such unit, so the vertex shader runs it once per triangle and passes the
results on as flat varyings. In this shader the fragment side drops from three hundred and twenty
instructions per pixel to one hundred and thirty-three. The vertex side grows, but it runs once
per triangle, not once per pixel.

## 4. Running the CTS against the RTL (6 min)

[The direct simulator]

Now the part I find most useful for anyone building a new implementation: running the
conformance suite against the hardware description itself. The direct simulator is the GPU's
RTL, without the CPU, compiled to native code with CIRCT's arcilator. The driver sends it the
same command stream as the real hardware, so the conformance suite sees an ordinary Vulkan
device. One instance runs about one hundred and twenty thousand cycles per second. Many run in
parallel, and on CI the suite is split into shards.

[Live: CTS cases on the simulator]

Here is a small run on my workstation. Each line is one test case: the driver records it, the
simulated GPU renders it, and the suite compares the image or the buffer with its reference.

[Throughput decides what you can test]

At this speed, throughput decides what you can test at all. Two examples. The compute precision
tests run one invocation per workgroup, so three of the four quad lanes sat idle. Packing four
workgroups into one quad took the atan2 vec4 case from over forty minutes to about two. And
large render passes are split into strips across several simulators. Together with the
compiler change from before, a pipeline barrier case went from a timeout after five and a half
hours to a pass in under two.

[Bugs found before silicon]

Running the suite against the RTL finds bugs before there is any silicon. A few examples.
textureGather at the corners of a cube map returned one sample instead of the average. Integer
bits copied through a float move were flushed when they looked like denormals. Branch targets in
fragment programs landed one word early. The compiler built a constant after the instructions
that read it. And one in the test setup itself: a simulator read gave up too early, so slow cases
passed with a half-drawn image. That is why I distrust a pass that comes too fast.

[Where it stands]

This is where Borg stands today, by test group, from a full run. [Numbers from the fresh run.]

## 5. Lessons and what is next (2 min)

[Lessons for a new implementation]

If you are starting a new Vulkan implementation, on new hardware or a simulator, here is what I
would do again. Bring up the api group first, and compute early, because so many shader tests run
as compute. Make the simulator fast before chasing failures; a faster loop pays for itself. Let
the compiler refuse what it cannot do. Keep one source of truth for the instruction set. Distrust
a pass that comes too fast. And record what the driver sends, so a refactoring can be checked in
minutes instead of a full test run.

[Next]

Next for Borg: the full Vulkan 1.0 must-pass list, the Nexys Video with Linux and DDR3, and a
tapeout.

## 6. Links (0.5 min)

[Thank you]

The code is on GitHub at gonsolo slash Borg, and there is a book that explains the design. Thank
you for watching, and see you at the poster in Kortrijk.
