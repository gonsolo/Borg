# SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later

import re
import sys

class TinySpirvCompiler:
    def __init__(self):
        self.reg_map = {}
        self.next_reg = 0
        self.asm = []
        self.id_to_name = {}
        # Hardware Mapping: a0=PushConstants, a1=inPos, a2=inColor, a3=fragColor, a4=gl_Position
        self.name_to_base = {
            "pc": "a0", 
            "inPos": "a1", 
            "inColor": "a2", 
            "fragColor": "a3", 
            "": "a4", 
            "_": "a4"
        }
        self.ptr_map = {}
        self.constants = {}
        self.composites = {}
        self.local_vars = {} # Tracks SSA pointers like %s, %c, %rot

    def get_reg(self, spirv_id):
        # 1. Constants
        if "float_0" in spirv_id: return "f_zero"
        if "float_1" in spirv_id: return "f_one"
        
        # 2. Register lookup / creation
        if spirv_id not in self.reg_map:
            self.reg_map[spirv_id] = f"f{self.next_reg}"
            self.next_reg += 1
        return self.reg_map[spirv_id]

    def resolve_flat(self, spirv_id):
        """Recursively flattens nested composites (like mat2) into base registers."""
        if spirv_id not in self.composites:
            return [self.get_reg(spirv_id)]
        
        res = []
        stack = [spirv_id]
        visited = set()
        while stack:
            curr = stack.pop()
            if curr in visited: continue
            if curr in self.composites:
                visited.add(curr)
                # Reverse to maintain original vector order (x, y, z, w)
                for child in reversed(self.composites[curr]):
                    stack.append(child)
            else:
                res.append(self.get_reg(curr))
        return res

    def emit(self, ins, comment=""):
        self.asm.append(f"    {ins:25} # {comment}" if comment else f"    {ins}")

    def compile_file(self, filename):
        try:
            with open(filename, 'r', encoding='utf-8') as f:
                raw = [l.split(';')[0].strip() for l in f.readlines()]
        except Exception as e: return f"Error: {e}"

        # --- PASS 1: Metadata & Structure ---
        for line in raw:
            t = re.findall(r'%\w+|Op\w+|"(?:[^"\\]|\\.)*"|\w+', line)
            if len(t) < 2: continue
            res_id = t[0] if t[0].startswith('%') else None
            opcode = t[1] if res_id else t[0]
            args = t[2:] if res_id else t[1:]

            if res_id: self.get_reg(res_id) # Assign ID early

            if opcode == "OpName": 
                self.id_to_name[t[1]] = t[2].strip('"')
            elif opcode == "OpConstant": 
                self.constants[res_id] = t[-1]
            elif opcode == "OpVariable":
                name = self.id_to_name.get(res_id, "unknown")
                if name in self.name_to_base:
                    self.ptr_map[res_id] = (self.name_to_base[name], 0)
                elif res_id == "%_": # Explicit gl_Position catch
                    self.ptr_map[res_id] = ("a4", 0)
            elif opcode == "OpCompositeConstruct":
                self.composites[res_id] = [a for a in args[1:] if a.startswith('%')]

        # --- PASS 2: Code Generation ---
        self.asm.append(f"# Compiled from {filename}\n# a0=pc, a1=inPos, a2=inColor, a3=fragColor, a4=gl_Pos")
        self.emit("li.s f_zero, 0.0")
        self.emit("li.s f_one, 1.0")

        for line in raw:
            t = re.findall(r'%\w+|Op\w+|"(?:[^"\\]|\\.)*"|\w+', line)
            if not t or "Op" not in line: continue
            res_id = t[0] if t[0].startswith('%') else None
            opcode = t[1] if res_id else t[0]
            args = t[2:] if res_id else t[1:]

            if opcode == "OpAccessChain":
                base_reg = self.ptr_map.get(args[1], ("a0", 0))[0]
                offset = int(self.constants.get(args[2], 0)) * 4
                self.ptr_map[res_id] = (base_reg, offset)

            elif opcode == "OpLoad":
                ptr_id = args[1]
                if ptr_id in self.local_vars:
                    # Resolve SSA alias! (e.g., %28 = load %s)
                    src_val = self.local_vars[ptr_id]
                    self.reg_map[res_id] = self.get_reg(src_val)
                    if src_val in self.composites:
                        self.composites[res_id] = self.composites[src_val]
                else:
                    dest = self.get_reg(res_id)
                    ptr = self.ptr_map.get(ptr_id)
                    if ptr:
                        self.emit(f"flw {dest}, {ptr[1]}({ptr[0]})", f"Load {self.id_to_name.get(ptr_id,'val')}")
                        if "inPos" in self.id_to_name.get(ptr_id, ""):
                            # Safely map vector components without circular references
                            x_id, y_id = res_id + "_x", res_id + "_y"
                            self.reg_map[x_id] = dest
                            y_reg = self.get_reg(y_id) # Safe creation!
                            self.emit(f"flw {y_reg}, {ptr[1]+4}({ptr[0]})", "Load inPos.y")
                            self.composites[res_id] = [x_id, y_id]

            elif opcode == "OpExtInst":
                op = args[2].lower()
                self.emit(f"f{op}.s {self.get_reg(res_id)}, {self.get_reg(args[3])}")

            elif opcode == "OpFNegate":
                self.emit(f"fneg.s {self.get_reg(res_id)}, {self.get_reg(args[1])}")

            elif opcode == "OpCompositeExtract":
                flat = self.resolve_flat(args[1])
                idx = int(args[2])
                if idx < len(flat):
                    self.reg_map[res_id] = flat[idx]

            elif opcode == "OpMatrixTimesVector":
                m, v = self.resolve_flat(args[1]), self.resolve_flat(args[2])
                if len(m) == 4 and len(v) >= 2:
                    self.emit(f"fmul.s f30, {m[0]}, {v[0]}", "c*x")
                    self.emit(f"fmadd.s f30, {m[2]}, {v[1]}, f30", "rx = -s*y + c*x")
                    self.emit(f"fmul.s f31, {m[1]}, {v[0]}", "s*x")
                    self.emit(f"fmadd.s f31, {m[3]}, {v[1]}, f31", "ry = c*y + s*x")
                    
                    # Store to logical coordinates
                    self.composites[res_id] = [res_id+"_x", res_id+"_y", "float_0", "float_1"]
                    self.reg_map[res_id+"_x"], self.reg_map[res_id+"_y"] = "f30", "f31"

            elif opcode == "OpStore":
                ptr_id, val_id = args[0], args[1]

                # Check if this pointer is in our memory map (global out/in)
                if ptr_id in self.ptr_map:
                    base_reg, offset = self.ptr_map[ptr_id]

                    # We want to store if it targets a4 (gl_Pos) or a3 (fragColor)
                    if base_reg in ["a3", "a4"]:
                        flat_vals = self.resolve_flat(val_id)
                        for i, r in enumerate(flat_vals):
                            self.emit(f"fsw {r}, {offset + (i*4)}({base_reg})", f"Store {r}")
                else:
                    # Virtual store for local variables (%s, %c, etc.)
                    self.local_vars[ptr_id] = val_id

            elif opcode == "OpReturn": self.emit("ret")

        return "\n".join(self.asm)


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

        # Allocate Borg physical registers for borg_vregs
        # Use a deterministic order based on first appearance
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
        lines.append("// Auto-generated by compiler.py --borg")
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

        # Host driver function
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
        return "\n".join(lines)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python compiler.py <input.spvasm> [output.s]")
        print("       python compiler.py --borg <input.s> [output.h]")
        sys.exit(1)

    if sys.argv[1] == "--borg":
        if len(sys.argv) < 3:
            print("Usage: python compiler.py --borg <input.s> [output.h]")
            sys.exit(1)
        with open(sys.argv[2], 'r') as f:
            asm_text = f.read()
        backend = BorgBackend()
        backend.lower(asm_text)
        header = backend.emit_header()
        if len(sys.argv) > 3:
            with open(sys.argv[3], 'w') as f:
                f.write(header + "\n")
            print(f"Generated {sys.argv[3]}")
        else:
            print(header)
    else:
        input_file = sys.argv[1]
        compiler = TinySpirvCompiler()
        compiled_asm = compiler.compile_file(input_file)

        if len(sys.argv) > 2:
            output_file = sys.argv[2]
            with open(output_file, 'w') as f:
                f.write(compiled_asm + "\n")
            print(f"Successfully compiled {input_file} -> {output_file}")
        else:
            print(compiled_asm)

