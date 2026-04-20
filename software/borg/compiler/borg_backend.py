# SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later

"""Borg backend: lowers pseudo-assembly to SPIR-B binary blobs.

Takes the pseudo-assembly output of spirv_compiler.py and produces
a SPIR-B binary blob (.borg) for runtime shader loading via spirb_parse().

The backend performs register allocation (virtual → physical Borg registers),
instruction encoding (32-bit RISC-V R-type / R4-type format), and
host/device code splitting.

Step 10.6.4.2: Uniforms are allocated to the hardware uniform buffer (u0-u31)
instead of GPRs (r0-r29).  The encoder emits funct3 bits to select which
operand reads from the uniform buffer:
  funct3=0: all operands from GPR (backward compatible)
  funct3=1: rs1 from uniform buffer
  funct3=2: rs2 from uniform buffer
  funct3=3: rs3 from uniform buffer (fmadd only)
"""

import sys
import os

# Add fpga/host to path to import the auto-generated borg_mmio.py
# (which contains the single source of truth for instruction encoding)
host_dir = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..", "out", "hardware", "borg", "rdl")
)
if host_dir not in sys.path:
    sys.path.insert(0, host_dir)

fpga_host_dir = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..", "fpga", "host")
)
if fpga_host_dir not in sys.path:
    sys.path.insert(0, fpga_host_dir)

from borg_mmio import encode_rv32_fadd, encode_rv32_fmul, encode_rv32_fmadd
sys.path.append(os.path.join(fpga_host_dir, '../../simulation/verilator/build/common_build'))
sys.path.append(os.path.join(fpga_host_dir, '../../simulation/arcilator/build/common_build'))
from borg_utils_c import fp16_from_float as float_to_fp16


class BorgBackend:
    """
    Lowers vert.s pseudo-assembly into host C code + Borg IMEM instructions.
    The host handles: li.s, flw, fsw, fsin, fcos, fneg, ret
    Borg handles:     fmul.s, fmadd.s (as 32-bit RISC-V encoded instructions)
    """

    def __init__(self, uniform_base=0):
        # Maps virtual register names to physical Borg register indices (GPR)
        self.vreg_to_preg = {}
        self.uniform_base = uniform_base
        # Maps uniform virtual registers to uniform buffer indices (u0-u31)
        self.vreg_to_uniform = {}
        # Set of virtual register names that are uniforms
        self.uniform_vreg_set = set()
        self.next_preg = 0
        self.borg_instrs = []  # Borg IMEM instructions (encoded)
        self.host_pre = []  # Host code before Borg execution
        self.host_post = []  # Host code after Borg execution
        self.constants = {}  # f_zero -> 0.0, f_one -> 1.0
        self.borg_defines = []  # (io_type, name, preg) from @borg annotations
        self.borg_uniforms = []  # (name, uniform_idx) for uniform buffer entries
        self.borg_attributes = []  # (name, preg) for attribute registers
        self.borg_outputs = []  # (name, preg) for output registers
        self.borg_consts = (
            []
        )  # (name, preg, value) for constants used in Borg instructions

    def alloc_reg(self, vreg):
        """Allocate a physical Borg register (0-29) for a virtual register."""
        if vreg in self.vreg_to_preg:
            return self.vreg_to_preg[vreg]
        if self.next_preg >= 30:
            raise RuntimeError(
                f"FATAL: Out of Borg registers (Max 30, since r30/r31 are reserved). Trying to alloc for {vreg}"
            )
        preg = self.next_preg
        self.vreg_to_preg[vreg] = preg
        self.next_preg += 1
        return preg

    def _is_uniform(self, vreg):
        """Check if a virtual register is a uniform (reads from uniform buffer)."""
        return vreg in self.uniform_vreg_set

    def _resolve_operand(self, vreg):
        """Return (reg_index, is_uniform) for a virtual register.

        For uniforms: returns (uniform_buffer_index, True)
        For GPRs:     returns (physical_register_index, False)
        """
        if vreg in self.vreg_to_uniform:
            return (self.vreg_to_uniform[vreg], True)
        if vreg in self.vreg_to_preg:
            return (self.vreg_to_preg[vreg], False)
        raise RuntimeError(f"Virtual register {vreg} not allocated")

    # encode_rv32_* methods removed, using single source of truth from borg_mmio

    def lower(self, asm_text):
        """Parse pseudo-assembly and split into host/Borg operations.

        Pass manager -- runs the following passes in order:
          1. identify_vregs         -- collect Borg virtual registers
          2. parse_annotations      -- extract @borg uniform/attribute/output declarations
          3. compute_live_intervals -- compute [first_def, last_use] for each vreg
          4. linear_scan_alloc      -- Poletto & Sarkar (1999): assign physical regs,
                                       reusing registers across non-overlapping lifetimes
                                       Uniforms go to uniform buffer, others to GPRs.
          5. emit_instructions      -- encode and emit host/Borg instructions
        """
        lines = [l.strip() for l in asm_text.strip().split("\n")]

        borg_vregs = self._pass_identify_vregs(lines)
        io_vregs, output_vregs, uniform_vregs = self._pass_parse_annotations(lines)
        live_intervals = self._pass_compute_live_intervals(lines, borg_vregs)
        self._pass_linear_scan_alloc(
            live_intervals, io_vregs, output_vregs, uniform_vregs
        )
        self._pass_emit_instructions(lines)

    def _pass_identify_vregs(self, lines):
        # First pass: identify which virtual registers are used in Borg instructions
        borg_vregs = set()
        for line in lines:
            if line.startswith("#") or not line:
                continue
            parts = line.split("#")[0].strip()
            tokens = parts.replace(",", " ").split()
            if not tokens:
                continue
            op = tokens[0]
            if op == "fadd.s":
                rd, a, b = tokens[1], tokens[2], tokens[3]
                borg_vregs.update([rd, a, b])
            elif op == "fmul.s":
                rd, a, b = tokens[1], tokens[2], tokens[3]
                borg_vregs.update([rd, a, b])
            elif op == "fmadd.s":
                rd, a, b, c = tokens[1], tokens[2], tokens[3], tokens[4]
                borg_vregs.update([rd, a, b, c])
                # rs3 (accumulator) has full 5-bit field (BF_RS3 = BitField(31,27))
                # No need to restrict to r0-r3

        # Pre-map hardware-fixed registers (coordLut pixel centers)
        for vreg in list(borg_vregs):
            if vreg == "f_r30":
                self.vreg_to_preg["f_r30"] = 30
            elif vreg == "f_r31":
                self.vreg_to_preg["f_r31"] = 31

        return borg_vregs

    def _pass_parse_annotations(self, lines):
        # Pre-scan @borg annotations to identify I/O virtual registers
        io_vregs = []
        output_vregs = set()
        uniform_vregs = set()
        for line in lines:
            if line.startswith("# @borg "):
                parts = line.split()
                if len(parts) == 5:
                    if parts[2] == "bind":
                        vreg = parts[3]
                        preg = int(parts[4])
                        self.vreg_to_preg[vreg] = preg
                        if vreg not in io_vregs:
                            io_vregs.append(vreg)
                        output_vregs.add(vreg)
                    else:
                        vreg = parts[4]
                        if vreg not in io_vregs:
                            io_vregs.append(vreg)
                        if parts[2] == "output":
                            output_vregs.add(vreg)
                        elif parts[2] == "uniform":
                            uniform_vregs.add(vreg)
                            self.uniform_vreg_set.add(vreg)
        return io_vregs, output_vregs, uniform_vregs

    def _pass_compute_live_intervals(self, lines, borg_vregs):
        """Compute [first_appearance, last_use] live intervals for each vreg.

        For straight-line shader code (no branches, no loops) this is exact.
        Each interval is the minimal range [start, end] where the vreg must
        occupy a physical register.
        """
        intervals = {}  # vreg -> [start_line_idx, end_line_idx]
        for i, line in enumerate(lines):
            if line.startswith("#") or not line:
                continue
            tokens = line.split("#")[0].strip().replace(",", " ").split()
            if not tokens:
                continue
            for tok in tokens[1:]:
                if tok in borg_vregs:
                    if tok not in intervals:
                        intervals[tok] = [i, i]
                    else:
                        intervals[tok][1] = i
            # Also mark the def (rd) as starting here
            if len(tokens) > 1 and tokens[1] in borg_vregs:
                vr = tokens[1]
                if vr not in intervals:
                    intervals[vr] = [i, i]
        return intervals

    def _pass_linear_scan_alloc(
        self, live_intervals, io_vregs, output_vregs, uniform_vregs
    ):
        """Linear Scan Register Allocation -- Poletto & Sarkar (1999).

        Step 10.6.4.2: Dual-pool allocation.

        Uniforms are assigned to the hardware uniform buffer (u0-u31) and
        do NOT consume GPR slots.  The register index stored in the instruction
        encoding is the uniform buffer index, and the funct3 field selects
        which operand reads from the buffer.

        Non-uniforms (temporaries, attributes, outputs, constants) use the
        GPR pool (r0-r29) with standard linear-scan allocation.

        Algorithm:
          1. Sort live intervals by start point.
          2. Walk forward: for each new interval, first expire any active
             intervals whose end < current start, freeing their registers.
          3. Assign the lowest available physical register.
        Constraints:
          - r30, r31 are pre-mapped (coordLut); never enter the pool.
          - Outputs live to end-of-shader: firmware reads them after halt.
          - All non-uniform I/O vregs start at line 0.
        """
        max_line = max((v[1] for v in live_intervals.values()), default=0)

        # --- Allocate uniforms to uniform buffer (u0-u31) ---
        next_uniform_idx = self.uniform_base
        for vreg in io_vregs:
            if vreg in uniform_vregs:
                if next_uniform_idx >= 32:
                    raise RuntimeError(f"Out of uniform buffer slots (max 32) for {vreg}")
                self.vreg_to_uniform[vreg] = next_uniform_idx
                print(f"UNIFORM {vreg} -> u{next_uniform_idx}")
                next_uniform_idx += 1
                # Remove from live_intervals since uniforms don't need GPRs
                if vreg in live_intervals:
                    del live_intervals[vreg]

        # Extend outputs to end-of-shader (firmware reads after halt)
        for vreg in output_vregs:
            if vreg in live_intervals:
                live_intervals[vreg][1] = max_line
            else:
                live_intervals[vreg] = [0, max_line]

        # Non-uniform I/O vregs are pre-loaded by firmware before line 0.
        # They MUST start at 0 so they conceptually overlap at the beginning
        # and do not get assigned to the same physical register.
        for vreg in io_vregs:
            if vreg not in uniform_vregs:  # skip uniforms (already handled)
                if vreg in live_intervals:
                    live_intervals[vreg][0] = 0
                else:
                    live_intervals[vreg] = [0, max_line]

        # Sort by start point (Poletto & Sarkar sec. 4)
        sorted_vregs = sorted(live_intervals, key=lambda v: live_intervals[v][0])

        # Single register pool: r0-r29 (rs3 has full 5-bit field, no restriction)
        reg_pool = list(range(0, 30))

        # Remove pre-mapped registers from pool so they are not doubly assigned
        for preg in self.vreg_to_preg.values():
            if preg in reg_pool:
                reg_pool.remove(preg)

        # active = [(end_line, vreg)] kept sorted by end point
        active = []

        def expire_old(current_start):
            """Return registers of intervals that ended before current_start."""
            expired = [a for a in active if a[0] < current_start]
            for end, vreg in expired:
                active.remove((end, vreg))
                preg = self.vreg_to_preg[vreg]
                reg_pool.append(preg)
                reg_pool.sort()

        for vreg in sorted_vregs:
            if vreg in self.vreg_to_preg:
                # Pre-mapped (r30/r31 or bind): still add to active for tracking
                start, end = live_intervals[vreg]
                active.append((end, vreg))
                active.sort(key=lambda a: a[0])
                continue

            start, end = live_intervals[vreg]
            expire_old(start)

            if not reg_pool:
                raise RuntimeError(f"Out of Borg registers (max 30) for {vreg}")

            preg = reg_pool.pop(0)
            self.vreg_to_preg[vreg] = preg
            print(f"MAPPED {vreg} to r{preg}  [{start},{end}]")
            active.append((end, vreg))
            active.sort(key=lambda a: a[0])

    def _compute_funct3(self, operands_with_roles):
        """Determine funct3 for an instruction based on which operands are uniforms.

        operands_with_roles: list of (vreg, role) where role is 'rs1', 'rs2', or 'rs3'
        Returns: funct3 value (0, 1, 2, or 3)
        Raises: RuntimeError if more than one operand is a uniform.
        """
        uniform_roles = [(vreg, role) for vreg, role in operands_with_roles
                         if self._is_uniform(vreg)]

        if len(uniform_roles) == 0:
            return 0
        if len(uniform_roles) > 1:
            names = ", ".join(f"{vreg} ({role})" for vreg, role in uniform_roles)
            raise RuntimeError(
                f"ERROR: Multiple uniform operands in single instruction: {names}. "
                f"Hardware supports only one uniform operand per instruction "
                f"(single funct3 field selects which operand reads from uniform buffer)."
            )
        _, role = uniform_roles[0]
        return {"rs1": 1, "rs2": 2, "rs3": 3}[role]

    def _get_reg_index(self, vreg):
        """Get the register index for encoding.

        For uniforms: returns the uniform buffer index.
        For GPRs: returns the physical register index.
        """
        if vreg in self.vreg_to_uniform:
            return self.vreg_to_uniform[vreg]
        return self.vreg_to_preg[vreg]

    def _pass_emit_instructions(self, lines):
        # Second pass: classify and lower
        in_borg_section = False
        for line in lines:
            # Parse @borg annotations before skipping comments
            if line.startswith("# @borg "):
                parts = line.split()
                # parts = ["#", "@borg", "uniform"/"attribute"/"output", "name", "vreg"]
                if len(parts) == 5:
                    io_type, name, vreg = parts[2], parts[3], parts[4]
                    if io_type == "uniform":
                        if vreg in self.vreg_to_uniform:
                            uidx = self.vreg_to_uniform[vreg]
                            print(f"MAPPED {io_type} {name} to u{uidx}")
                            self.borg_defines.append((io_type, name.upper(), uidx))
                            self.borg_uniforms.append((name.upper(), uidx))
                    elif io_type in ("attribute", "output", "bind"):
                        if vreg in self.vreg_to_preg:
                            preg = self.vreg_to_preg[vreg]
                            print(f"MAPPED {io_type} {name} to r{preg}")
                            self.borg_defines.append((io_type, name.upper(), preg))
                            if io_type == "attribute":
                                self.borg_attributes.append((name.upper(), preg))
                            elif io_type == "output":
                                self.borg_outputs.append((name.upper(), preg))
                continue
            if line.startswith("#") or not line:
                continue
            comment = ""
            if "#" in line:
                parts_split = line.split("#", 1)
                line_clean = parts_split[0].strip()
                comment = parts_split[1].strip()
            else:
                line_clean = line.strip()

            tokens = line_clean.replace(",", " ").split()
            if not tokens:
                continue
            op = tokens[0]

            if op == "li.s":
                name, val = tokens[1], tokens[2]
                self.constants[name] = val

            elif op == "fsin.s":
                dst, src = tokens[1], tokens[2]
                self.host_pre.append(f"    // {line_clean}")
                self.host_pre.append(f"    uint16_t {dst} = fp16_sin({src});")
                if dst in self.vreg_to_preg:
                    preg = self.vreg_to_preg[dst]
                    self.host_pre.append(f"    borg_write_reg({preg}, {dst});")

            elif op == "fcos.s":
                dst, src = tokens[1], tokens[2]
                self.host_pre.append(f"    // {line_clean}")
                self.host_pre.append(f"    uint16_t {dst} = fp16_cos({src});")
                if dst in self.vreg_to_preg:
                    preg = self.vreg_to_preg[dst]
                    self.host_pre.append(f"    borg_write_reg({preg}, {dst});")

            elif op == "fneg.s":
                dst, src = tokens[1], tokens[2]
                self.host_pre.append(f"    // {line_clean}")
                self.host_pre.append(
                    f"    uint16_t {dst} = {src} ^ 0x8000;  // XOR sign bit"
                )
                if dst in self.vreg_to_preg:
                    preg = self.vreg_to_preg[dst]
                    self.host_pre.append(f"    borg_write_reg({preg}, {dst});")

            elif op.startswith("flw"):
                dst = tokens[1]
                mem = tokens[2]  # e.g. "0(a1)"
                self.host_pre.append(f"    // {line_clean}  {comment}")
                self.host_pre.append(f"    uint16_t {dst} = load_fp16({mem});")
                if dst in self.vreg_to_preg:
                    preg = self.vreg_to_preg[dst]
                    self.host_pre.append(f"    borg_write_reg({preg}, {dst});")

            elif op.startswith("fsw"):
                src = tokens[1]
                mem = tokens[2]
                if src in self.vreg_to_preg:
                    preg = self.vreg_to_preg[src]
                    self.host_post.append(f"    // {line_clean}  {comment}")
                    self.host_post.append(
                        f"    uint16_t {src}_out = borg_read_reg({preg});"
                    )
                    self.host_post.append(f"    store_fp16({mem}, {src}_out);")
                elif src in self.constants:
                    self.host_post.append(f"    // {line_clean}  {comment}")
                    self.host_post.append(
                        f"    store_fp16({mem}, float_to_fp16({self.constants[src]}));"
                    )
                else:
                    self.host_post.append(f"    // {line_clean} -- passthrough")
                    self.host_post.append(f"    store_fp16({mem}, {src});")

            elif op == "fadd.s":
                rd, a, b = tokens[1], tokens[2], tokens[3]
                prd = self.vreg_to_preg[rd]  # rd is always GPR
                pa = self._get_reg_index(a)
                pb = self._get_reg_index(b)
                funct3 = self._compute_funct3([(a, "rs1"), (b, "rs2")])
                enc = encode_rv32_fadd(pa, pb, prd, funct3=funct3)
                f3_str = f" funct3={funct3}" if funct3 else ""
                a_prefix = "u" if self._is_uniform(a) else "r"
                b_prefix = "u" if self._is_uniform(b) else "r"
                self.borg_instrs.append(
                    (enc, f"fadd r{prd}, {a_prefix}{pa}, {b_prefix}{pb}{f3_str}  // {comment}")
                )

            elif op == "fmul.s":
                rd, a, b = tokens[1], tokens[2], tokens[3]
                prd = self.vreg_to_preg[rd]  # rd is always GPR
                pa = self._get_reg_index(a)
                pb = self._get_reg_index(b)
                funct3 = self._compute_funct3([(a, "rs1"), (b, "rs2")])
                enc = encode_rv32_fmul(pa, pb, prd, funct3=funct3)
                f3_str = f" funct3={funct3}" if funct3 else ""
                a_prefix = "u" if self._is_uniform(a) else "r"
                b_prefix = "u" if self._is_uniform(b) else "r"
                self.borg_instrs.append(
                    (enc, f"fmul r{prd}, {a_prefix}{pa}, {b_prefix}{pb}{f3_str}  // {comment}")
                )

            elif op == "fmadd.s":
                rd, a, b, c = tokens[1], tokens[2], tokens[3], tokens[4]
                prd = self.vreg_to_preg[rd]  # rd is always GPR
                pa = self._get_reg_index(a)
                pb = self._get_reg_index(b)
                pc = self._get_reg_index(c)
                funct3 = self._compute_funct3([(a, "rs1"), (b, "rs2"), (c, "rs3")])
                enc = encode_rv32_fmadd(pa, pb, pc, prd, funct3=funct3)
                f3_str = f" funct3={funct3}" if funct3 else ""
                a_prefix = "u" if self._is_uniform(a) else "r"
                b_prefix = "u" if self._is_uniform(b) else "r"
                c_prefix = "u" if self._is_uniform(c) else "r"
                self.borg_instrs.append(
                    (enc, f"fmadd r{prd}, {a_prefix}{pa}, {b_prefix}{pb}, {c_prefix}{pc}{f3_str}  // {comment}")
                )

            elif op == "ret":
                pass  # Halt is implicit (IMEM word = 0)

        # Detect constants used in Borg instructions (GPR-allocated only)
        for vreg, preg in self.vreg_to_preg.items():
            if vreg in self.constants:
                self.borg_consts.append((vreg, preg, self.constants[vreg]))

    def emit_binary(self):
        """Serialize the shader to SPIR-B binary format (see docs/spirb.md)."""
        import struct

        parts = []

        # Header: 6 bytes
        n_instr = len(self.borg_instrs)
        n_uni = len(self.borg_uniforms)
        n_attr = len(self.borg_attributes)
        n_out = len(self.borg_outputs)
        n_const = len(self.borg_consts)
        parts.append(struct.pack("<6B", n_instr, n_uni, n_attr, n_out, n_const, 0))

        # Instructions: N × uint32_le
        for enc, _comment in self.borg_instrs:
            parts.append(struct.pack("<I", enc))

        # Register index arrays: uint8 each
        # Uniforms now carry uniform buffer indices (u0-u31)
        for _, uidx in self.borg_uniforms:
            parts.append(struct.pack("B", uidx))
        for _, preg in self.borg_attributes:
            parts.append(struct.pack("B", preg))
        for _, preg in self.borg_outputs:
            parts.append(struct.pack("B", preg))
        for _, preg, _ in self.borg_consts:
            parts.append(struct.pack("B", preg))

        # Constant values: C × uint16_le
        for _, _, val in self.borg_consts:
            parts.append(struct.pack("<H", float_to_fp16(float(val))))

        return b"".join(parts)


if __name__ == "__main__":
    # Parse optional --uniform-base N flag
    args = sys.argv[1:]
    uniform_base = 0
    if "--uniform-base" in args:
        idx = args.index("--uniform-base")
        uniform_base = int(args[idx + 1])
        args = args[:idx] + args[idx + 2:]

    if len(args) != 2:
        print("Usage: python borg_backend.py <input.s> <output.borg> [--uniform-base N]")
        sys.exit(1)

    with open(args[0], "r") as f:
        asm_text = f.read()
    backend = BorgBackend(uniform_base=uniform_base)
    backend.lower(asm_text)

    out_path = args[1]
    data = backend.emit_binary()
    with open(out_path, "wb") as f:
        f.write(data)

    # Calculate peak registers used (highest allocatable register + 1, excluding hw-fixed r30/r31)
    allocatable_pregs = [p for p in backend.vreg_to_preg.values() if p < 30]
    peak_regs = max(allocatable_pregs) + 1 if allocatable_pregs else 0
    n_uniforms = len(backend.vreg_to_uniform)
    print(f"INFO: Peak GPR usage: {peak_regs}/30, Uniform buffer: {n_uniforms}/32")
    print(f"Generated {out_path} ({len(data)} bytes)")
