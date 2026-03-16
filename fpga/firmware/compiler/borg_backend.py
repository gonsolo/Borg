import sys


class BorgBackend:
    """
    Lowers vert.s pseudo-assembly into host C code + Borg IMEM instructions.
    The host handles: li.s, flw, fsw, fsin, fcos, fneg, ret
    Borg handles:     fmul.s, fmadd.s (as 16-bit FP16 encoded instructions)
    """

    def __init__(self):
        # Maps virtual register names to physical Borg register indices
        self.vreg_to_preg = {}
        self.next_preg = 0
        self.borg_instrs = []        # Borg IMEM instructions (encoded)
        self.host_pre = []           # Host code before Borg execution
        self.host_post = []          # Host code after Borg execution
        self.constants = {}          # f_zero -> 0.0, f_one -> 1.0
        self.borg_defines = []       # (io_type, name, preg) from @borg annotations
        self.borg_uniforms = []      # (name, preg) for uniform registers
        self.borg_attributes = []    # (name, preg) for attribute registers
        self.borg_outputs = []       # (name, preg) for output registers
        self.borg_consts = []        # (name, preg, value) for constants used in Borg instructions

    @staticmethod
    def _float_to_fp16(f):
        """Convert float to FP16 bits (for compile-time constant embedding)."""
        if f == 0.0:
            return 0x0000
        sign = 0
        if f < 0:
            sign = 1
            f = -f
        exp = 0
        tmp = f
        while tmp >= 2.0:
            tmp /= 2.0
            exp += 1
        while tmp < 1.0:
            tmp *= 2.0
            exp -= 1
        frac = int((tmp - 1.0) * 1024 + 0.5)
        biased = exp + 15
        if frac >= 1024:
            frac = 0
            biased += 1
        if biased >= 31:
            return (sign << 15) | 0x7C00
        if biased <= 0:
            return sign << 15
        return (sign << 15) | (biased << 10) | frac

    def alloc_reg(self, vreg):
        """Allocate a physical Borg register (0-15) for a virtual register."""
        if vreg in self.vreg_to_preg:
            return self.vreg_to_preg[vreg]
        if self.next_preg >= 16:
            raise RuntimeError(f"Out of Borg registers (max 16), trying to alloc for {vreg}")
        preg = self.next_preg
        self.vreg_to_preg[vreg] = preg
        self.next_preg += 1
        return preg

    def encode_fp16_fadd(self, rd, rs1, rs2):
        """Encode FP16 fadd: [15:14]=00, [11:8]=rs2, [7:4]=rs1, [3:0]=rd"""
        return (0 << 14) | (rs2 << 8) | (rs1 << 4) | rd

    def encode_fp16_fmul(self, rd, rs1, rs2):
        """Encode FP16 fmul: [15:14]=01, [11:8]=rs2, [7:4]=rs1, [3:0]=rd"""
        return (1 << 14) | (rs2 << 8) | (rs1 << 4) | rd

    def encode_fp16_fmadd(self, rd, rs1, rs2, rs3):
        """Encode FP16 fmadd: [15:14]=10, [13:12]=rs3, [11:8]=rs2, [7:4]=rs1, [3:0]=rd"""
        return (2 << 14) | (rs3 << 12) | (rs2 << 8) | (rs1 << 4) | rd

    def lower(self, asm_text):
        """Parse pseudo-assembly and split into host/Borg operations."""
        lines = [l.strip() for l in asm_text.strip().split("\n")]

        # First pass: identify which virtual registers are used in fmul/fmadd
        borg_vregs = set()
        fmadd_accumulators = []  # rs3 in fmadd: only 2-bit field, must be r0-r3
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
                # rs3 (accumulator) has only 2 bits — must be allocated to r0-r3
                if c not in fmadd_accumulators:
                    fmadd_accumulators.append(c)

        # Allocate fmadd accumulators FIRST to guarantee they get r0-r3
        for vreg in fmadd_accumulators:
            if vreg not in self.vreg_to_preg:
                self.alloc_reg(vreg)

        # Allocate remaining Borg physical registers in order of first appearance
        for line in lines:
            if line.startswith("#") or not line:
                continue
            parts = line.split("#")[0].strip()
            tokens = parts.replace(",", " ").split()
            if not tokens:
                continue
            for tok in tokens[1:]:
                if tok in borg_vregs and tok not in self.vreg_to_preg:
                    self.alloc_reg(tok)

        # Second pass: classify and lower
        in_borg_section = False
        for line in lines:
            # Parse @borg annotations before skipping comments
            if line.startswith("# @borg "):
                parts = line.split()
                # parts = ["#", "@borg", "uniform"/"attribute"/"output", "name", "vreg"]
                if len(parts) == 5:
                    io_type, name, vreg = parts[2], parts[3], parts[4]
                    if vreg in self.vreg_to_preg:
                        preg = self.vreg_to_preg[vreg]
                        self.borg_defines.append((io_type, name.upper(), preg))
                        if io_type == "uniform":
                            self.borg_uniforms.append((name.upper(), preg))
                        elif io_type == "attribute":
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
                self.host_pre.append(f"    uint16_t {dst} = {src} ^ 0x8000;  // XOR sign bit")
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
                    self.host_post.append(f"    uint16_t {src}_out = borg_read_reg({preg});")
                    self.host_post.append(f"    store_fp16({mem}, {src}_out);")
                elif src in self.constants:
                    self.host_post.append(f"    // {line_clean}  {comment}")
                    self.host_post.append(f"    store_fp16({mem}, float_to_fp16({self.constants[src]}));")
                else:
                    self.host_post.append(f"    // {line_clean} -- passthrough")
                    self.host_post.append(f"    store_fp16({mem}, {src});")

            elif op == "fadd.s":
                rd, a, b = tokens[1], tokens[2], tokens[3]
                prd = self.vreg_to_preg[rd]
                pa = self.vreg_to_preg[a]
                pb = self.vreg_to_preg[b]
                enc = self.encode_fp16_fadd(prd, pa, pb)
                self.borg_instrs.append((enc, f"fadd r{prd}, r{pa}, r{pb}  // {comment}"))

            elif op == "fmul.s":
                rd, a, b = tokens[1], tokens[2], tokens[3]
                prd = self.vreg_to_preg[rd]
                pa = self.vreg_to_preg[a]
                pb = self.vreg_to_preg[b]
                enc = self.encode_fp16_fmul(prd, pa, pb)
                self.borg_instrs.append((enc, f"fmul r{prd}, r{pa}, r{pb}  // {comment}"))

            elif op == "fmadd.s":
                rd, a, b, c = tokens[1], tokens[2], tokens[3], tokens[4]
                prd = self.vreg_to_preg[rd]
                pa = self.vreg_to_preg[a]
                pb = self.vreg_to_preg[b]
                pc = self.vreg_to_preg[c]
                enc = self.encode_fp16_fmadd(prd, pa, pb, pc)
                self.borg_instrs.append((enc, f"fmadd r{prd}, r{pa}, r{pb}, r{pc}  // {comment}"))

            elif op == "ret":
                pass  # Halt is implicit (IMEM word = 0)

        # Detect constants used in Borg instructions
        for vreg, preg in self.vreg_to_preg.items():
            if vreg in self.constants:
                self.borg_consts.append((vreg, preg, self.constants[vreg]))

    def emit_header(self, shader_name="shader"):
        """Generate a C header with the Borg IMEM program and host driver code."""
        lines = []
        lines.append("// Auto-generated by borg_backend.py")
        lines.append(f"// {shader_name} shader lowered to host C + Borg IMEM")
        lines.append("#pragma once")
        lines.append("")

        # Register allocation table
        lines.append("// Borg register allocation:")
        for vreg, preg in sorted(self.vreg_to_preg.items(), key=lambda x: x[1]):
            lines.append(f"//   r{preg} = {vreg}")
        lines.append("")

        # Use shader name as prefix to avoid symbol conflicts
        prefix = shader_name.upper()
        var_prefix = shader_name

        # IMEM program
        lines.append(f"#define {prefix}_BORG_PROGRAM_LEN {len(self.borg_instrs)}")
        lines.append(f"static const uint32_t {var_prefix}_borg_program[] = {{")
        for enc, comment in self.borg_instrs:
            lines.append(f"    0x{enc:04X},  // {comment}")
        lines.append("    0x0000,  // halt")
        lines.append("};")
        lines.append("")

        # Register defines for firmware integration
        if self.borg_defines:
            lines.append("// Register assignments")
            for io_type, name, preg in self.borg_defines:
                lines.append(f"#define {prefix}_BORG_REG_{name}  {preg}")
            lines.append("")

        # Register metadata arrays for generic shader dispatch
        if self.borg_uniforms:
            lines.append(f"#define {prefix}_NUM_UNIFORMS {len(self.borg_uniforms)}")
            regs = ", ".join(str(preg) for _, preg in self.borg_uniforms)
            lines.append(f"static const int {var_prefix}_uniform_regs[] = {{{regs}}};")
            lines.append("")
        else:
            lines.append(f"#define {prefix}_NUM_UNIFORMS 0")
            lines.append("")

        if self.borg_attributes:
            lines.append(f"#define {prefix}_NUM_ATTRIBUTES {len(self.borg_attributes)}")
            regs = ", ".join(str(preg) for _, preg in self.borg_attributes)
            lines.append(f"static const int {var_prefix}_attribute_regs[] = {{{regs}}};")
            lines.append("")
        else:
            lines.append(f"#define {prefix}_NUM_ATTRIBUTES 0")
            lines.append("")

        if self.borg_outputs:
            lines.append(f"#define {prefix}_NUM_OUTPUTS {len(self.borg_outputs)}")
            regs = ", ".join(str(preg) for _, preg in self.borg_outputs)
            lines.append(f"static const int {var_prefix}_output_regs[] = {{{regs}}};")
            lines.append("")

        # Shader constants (compile-time known values that need register loading)
        if self.borg_consts:
            lines.append(f"#define {prefix}_NUM_CONSTS {len(self.borg_consts)}")
            regs = ", ".join(str(preg) for _, preg, _ in self.borg_consts)
            lines.append(f"static const int {var_prefix}_const_regs[] = {{{regs}}};")
            vals = ", ".join(f"0x{self._float_to_fp16(float(val)):04X}" for _, _, val in self.borg_consts)
            lines.append(f"static const uint16_t {var_prefix}_const_vals[] = {{{vals}}};")
            lines.append("")
        else:
            lines.append(f"#define {prefix}_NUM_CONSTS 0")
            lines.append("")

        # Host driver function (guarded for firmware compatibility)
        func_name = f"borg_run_{shader_name}_shader"
        lines.append("#ifdef BORG_HOST_DRIVER")
        lines.append(f"static inline void {func_name}(")
        lines.append("    uint16_t angle, uint16_t x, uint16_t y,")
        lines.append("    uint16_t *rx_out, uint16_t *ry_out)")
        lines.append("{")

        # Pre-execution: host computes sin/cos, loads registers
        for line in self.host_pre:
            lines.append(line)

        lines.append("")
        lines.append("    // Execute Borg shader")
        lines.append("    borg_start();")
        lines.append("    borg_wait_halt();")
        lines.append("")

        # Post-execution: read results
        for line in self.host_post:
            lines.append(line)

        lines.append("}")
        lines.append("#endif // BORG_HOST_DRIVER")
        return "\n".join(lines)

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
        parts.append(struct.pack('<6B', n_instr, n_uni, n_attr, n_out, n_const, 0))

        # Instructions: N × uint16_le
        for enc, _comment in self.borg_instrs:
            parts.append(struct.pack('<H', enc))

        # Register index arrays: uint8 each
        for _, preg in self.borg_uniforms:
            parts.append(struct.pack('B', preg))
        for _, preg in self.borg_attributes:
            parts.append(struct.pack('B', preg))
        for _, preg in self.borg_outputs:
            parts.append(struct.pack('B', preg))
        for _, preg, _ in self.borg_consts:
            parts.append(struct.pack('B', preg))

        # Constant values: C × uint16_le
        for _, _, val in self.borg_consts:
            parts.append(struct.pack('<H', self._float_to_fp16(float(val))))

        return b''.join(parts)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python borg_backend.py <input.s> [output.borg.h | output.borg]")
        sys.exit(1)

    with open(sys.argv[1], 'r') as f:
        asm_text = f.read()
    import os
    shader_name = os.path.splitext(os.path.basename(sys.argv[1]))[0]  # e.g. "vert" from "vert.s"
    backend = BorgBackend()
    backend.lower(asm_text)

    if len(sys.argv) > 2:
        out_path = sys.argv[2]
        if out_path.endswith('.borg'):
            data = backend.emit_binary()
            with open(out_path, 'wb') as f:
                f.write(data)
            print(f"Generated {out_path} ({len(data)} bytes)")
        else:
            header = backend.emit_header(shader_name)
            with open(out_path, 'w') as f:
                f.write(header + "\n")
            print(f"Generated {out_path}")
    else:
        print(backend.emit_header(shader_name))

