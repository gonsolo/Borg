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

    def alloc_reg(self, vreg):
        """Allocate a physical Borg register (0-7) for a virtual register."""
        if vreg in self.vreg_to_preg:
            return self.vreg_to_preg[vreg]
        if self.next_preg >= 8:
            raise RuntimeError(f"Out of Borg registers (max 8), trying to alloc for {vreg}")
        preg = self.next_preg
        self.vreg_to_preg[vreg] = preg
        self.next_preg += 1
        return preg

    def encode_fp16_fmul(self, rd, rs1, rs2):
        """Encode FP16 fmul: bits[15:13]=001, rs2[10:8], rs1[7:5], rd[4:2]"""
        return (1 << 13) | (rs2 << 8) | (rs1 << 5) | (rd << 2)

    def encode_fp16_fmadd(self, rd, rs1, rs2, rs3):
        """Encode FP16 fmadd: bits[15:13]=010, rs3[12:11], rs2[10:8], rs1[7:5], rd[4:2]"""
        return (2 << 13) | (rs3 << 11) | (rs2 << 8) | (rs1 << 5) | (rd << 2)

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
            if op == "fmul.s":
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
                # parts = ["#", "@borg", "input"/"output", "name", "vreg"]
                if len(parts) == 5:
                    io_type, name, vreg = parts[2], parts[3], parts[4]
                    if vreg in self.vreg_to_preg:
                        preg = self.vreg_to_preg[vreg]
                        self.borg_defines.append((io_type, name.upper(), preg))
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

    def emit_header(self):
        """Generate a C header with the Borg IMEM program and host driver code."""
        lines = []
        lines.append("// Auto-generated by borg_backend.py")
        lines.append("// Vertex shader lowered to host C + Borg IMEM")
        lines.append("#pragma once")
        lines.append("")

        # Register allocation table
        lines.append("// Borg register allocation:")
        for vreg, preg in sorted(self.vreg_to_preg.items(), key=lambda x: x[1]):
            lines.append(f"//   r{preg} = {vreg}")
        lines.append("")

        # IMEM program
        lines.append(f"#define BORG_PROGRAM_LEN {len(self.borg_instrs)}")
        lines.append("static const uint16_t borg_program[] = {")
        for enc, comment in self.borg_instrs:
            lines.append(f"    0x{enc:04X},  // {comment}")
        lines.append("    0x0000,  // halt")
        lines.append("};")
        lines.append("")

        # Register defines for firmware integration
        if self.borg_defines:
            lines.append("// Register assignments")
            for io_type, name, preg in self.borg_defines:
                lines.append(f"#define BORG_REG_{name}  {preg}")
            lines.append("")

        # Host driver function (guarded for firmware compatibility)
        lines.append("#ifdef BORG_HOST_DRIVER")
        lines.append("static inline void borg_run_vertex_shader(")
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


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python borg_backend.py <input.s> [output.h]")
        sys.exit(1)

    with open(sys.argv[1], 'r') as f:
        asm_text = f.read()
    backend = BorgBackend()
    backend.lower(asm_text)
    header = backend.emit_header()
    if len(sys.argv) > 2:
        with open(sys.argv[2], 'w') as f:
            f.write(header + "\n")
        print(f"Generated {sys.argv[2]}")
    else:
        print(header)
