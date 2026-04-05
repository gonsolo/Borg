# SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later

"""SPIR-V disassembly → Borg pseudo-assembly compiler.

Translates SPIR-V text (spirv-dis output) into a custom pseudo-assembly
that mirrors RISC-V F-extension mnemonics (fmul.s, fmadd.s, fadd.s, etc.).
The pseudo-assembly is then consumed by borg_backend.py which lowers it
to Borg IMEM instructions + host C code.

Pipeline:  shader.frag → glslc → spirv-dis → spirv_compiler.py → borg_backend.py → .borg blob
"""

import re
import sys


class TinySpirvCompiler:
    """Two-pass compiler from SPIR-V text to Borg pseudo-assembly.

    Pass 1 (metadata): Collects names, constants, struct types, storage
    classes, decorations, and variable mappings from OpName, OpDecorate,
    OpConstant, OpVariable, etc.

    Pass 2 (codegen): Walks executable opcodes (OpLoad, OpStore, OpFMul,
    OpFAdd, OpExtInst/FMA, OpMatrixTimesVector, etc.) and emits pseudo-asm
    instructions with @borg annotations for I/O register binding.

    Supports both vertex and fragment shaders, auto-detected from
    OpEntryPoint.
    """
    def __init__(self):
        """Initialize compiler state for a fresh compilation."""
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
        self.borg_io = []    # List of (type, name, reg) for @borg annotations
        self.vreg_roles = {} # spirv_id -> role name for semantic tracking
        self.shader_type = "vertex"  # "vertex" or "fragment"
        self.decorations = {}        # id -> {"Location": N, "Binding": N, ...}
        self.member_names = {}       # (struct_id, member_idx) -> name
        self.storage_classes = {}    # id -> "Input" / "Output" / "Uniform"
        self.struct_types = {}       # type_id -> [member_type_ids]
        self.var_types = {}          # var_id -> type_id

    def get_reg(self, spirv_id):
        """Map a SPIR-V SSA ID to a virtual register name.

        Well-known constants (float_0, float_1) get fixed names.
        All other IDs are assigned sequential f0, f1, f2, ... names.
        """
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
        """Append a pseudo-assembly instruction to the output."""
        self.asm.append(f"    {ins:25} # {comment}" if comment else f"    {ins}")

    def compile_file(self, filename):
        """Compile a SPIR-V text file to Borg pseudo-assembly.

        Args:
            filename: Path to a .spvasm file (output of spirv-dis).

        Returns:
            Multi-line string of pseudo-assembly with @borg annotations,
            or an error message string on failure.
        """
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

            if opcode == "OpEntryPoint":
                model = args[0] if args else ""
                if model == "Fragment":
                    self.shader_type = "fragment"
                elif model == "Vertex":
                    self.shader_type = "vertex"

            elif opcode == "OpName": 
                self.id_to_name[t[1]] = t[2].strip('"')
            elif opcode == "OpMemberName":
                struct_id = t[1]
                member_idx = int(t[2])
                name = t[3].strip('"')
                self.member_names[(struct_id, member_idx)] = name
            elif opcode == "OpDecorate":
                target_id = t[1]
                decoration = t[2]
                if target_id not in self.decorations:
                    self.decorations[target_id] = {}
                if decoration == "Location" and len(t) > 3:
                    self.decorations[target_id]["Location"] = int(t[3])
                elif decoration == "Binding" and len(t) > 3:
                    self.decorations[target_id]["Binding"] = int(t[3])
            elif opcode == "OpConstant":
                # Extract value from raw line (regex \w+ misses negative signs)
                val_match = re.search(r'OpConstant\s+\S+\s+(-?[\d.]+)', line)
                self.constants[res_id] = val_match.group(1) if val_match else t[-1]
            elif opcode == "OpTypeStruct":
                # Record struct member types
                self.struct_types[res_id] = [a for a in args if a.startswith('%')]
            elif opcode == "OpVariable":
                # Determine storage class from the raw line
                storage = None
                for kw in ["Input", "Output", "Uniform", "Function"]:
                    if kw in line.split(";")[0]:
                        storage = kw
                        break
                if storage:
                    self.storage_classes[res_id] = storage
                # Track type
                if args:
                    self.var_types[res_id] = args[0]

                name = self.id_to_name.get(res_id, "unknown")
                if self.shader_type == "vertex":
                    # Original vertex shader mapping
                    if name in self.name_to_base:
                        self.ptr_map[res_id] = (self.name_to_base[name], 0)
                    elif res_id == "%_":
                        self.ptr_map[res_id] = ("a4", 0)
                elif self.shader_type == "fragment":
                    if storage == "Uniform":
                        self.ptr_map[res_id] = ("uniform_block", 0)
            elif opcode in ("OpCompositeConstruct", "OpConstantComposite"):
                self.composites[res_id] = [a for a in args[1:] if a.startswith('%')]

        # --- PASS 2: Code Generation ---
        if self.shader_type == "vertex":
            self.asm.append(f"# Compiled from {filename}\n# a0=pc, a1=inPos, a2=inColor, a3=fragColor, a4=gl_Pos")
        else:
            self.asm.append(f"# Compiled from {filename}\n# Fragment shader: barycentric interpolation")
        self.emit("li.s f_zero, 0.0")
        self.emit("li.s f_one, 1.0")
        # Emit li.s for all other float constants (e.g. -2.0 from translations)
        for cid, cval in self.constants.items():
            # Skip integer constants and well-known float constants
            if cid.startswith("%float") and cid not in ("%float_0", "%float_1"):
                reg = self.get_reg(cid)
                self.emit(f"li.s {reg}, {cval}")

        for line in raw:
            t = re.findall(r'%\w+|Op\w+|"(?:[^"\\]|\\.)*"|\w+', line)
            if not t or "Op" not in line: continue
            res_id = t[0] if t[0].startswith('%') else None
            opcode = t[1] if res_id else t[0]
            args = t[2:] if res_id else t[1:]

            if opcode == "OpAccessChain":
                base_ptr = args[1]
                if base_ptr in self.ptr_map:
                    base_info = self.ptr_map[base_ptr]
                    if base_info[0] == "uniform_block":
                        # Access into uniform struct: use member index as identifier
                        member_idx = int(self.constants.get(args[2], 0))
                        member_name = self.member_names.get((
                            self.id_to_name.get(base_ptr, base_ptr), member_idx),
                            f"member_{member_idx}")
                        # Try to find in struct's member_names with the struct type name
                        for (sid, midx), mname in self.member_names.items():
                            if midx == member_idx:
                                member_name = mname
                                break
                        self.ptr_map[res_id] = ("uniform_member", member_name, member_idx)
                    else:
                        base_reg = base_info[0]
                        offset = int(self.constants.get(args[2], 0)) * 4
                        self.ptr_map[res_id] = (base_reg, offset)
                else:
                    if base_ptr in self.local_vars:
                        src_val = self.local_vars[base_ptr]
                        idx_val = self.constants.get(args[2], args[2])
                        idx = int(idx_val) if str(idx_val).lstrip('-').isdigit() else 0
                        self.ptr_map[res_id] = ("local_alias", src_val, idx)
                        continue
                    
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
                    if src_val in self.vreg_roles:
                        self.vreg_roles[res_id] = self.vreg_roles[src_val]
                elif ptr_id in self.ptr_map:
                    ptr_info = self.ptr_map[ptr_id]
                    if ptr_info[0] == "local_alias":
                        src_val, idx = ptr_info[1], ptr_info[2]
                        flat = self.resolve_flat(src_val)
                        if idx < len(flat):
                            self.reg_map[res_id] = flat[idx]
                        continue
                    
                    if ptr_info[0] == "uniform_member":
                        # Load from uniform struct member (deduplicate)
                        member_name = ptr_info[1]
                        member_idx = ptr_info[2]
                        cache_key = f"_uniform_{member_name}"
                        if cache_key in self.reg_map:
                            self.reg_map[res_id] = self.reg_map[cache_key]
                        else:
                            reg = self.get_reg(res_id)
                            self.emit(f"flw {reg}, 0(uniform_{member_name})", f"Load uniform {member_name}")
                            self.borg_io.append(("uniform", member_name, reg, member_idx))
                            self.reg_map[cache_key] = reg
                    else:
                        base_reg, offset = ptr_info
                        if base_reg == "a1":
                            idx = offset // 4
                            axis = ["x", "y", "z", "w"][idx]
                            cache_key = f"_inPos_{axis}"
                            if cache_key not in self.reg_map:
                                dest = self.get_reg(res_id)
                                self.reg_map[cache_key] = dest
                                self.borg_io.append(("attribute", axis, dest))
                            self.reg_map[res_id] = self.reg_map[cache_key]
                            # Generate composites implicitly via caching if the whole block is loaded
                            if "inPos" in self.id_to_name.get(ptr_id, ""):
                                x_id, y_id, z_id = res_id + "_x", res_id + "_y", res_id + "_z"
                                self.reg_map[x_id] = self.reg_map.setdefault("_inPos_x", self.get_reg(x_id))
                                self.reg_map[y_id] = self.reg_map.setdefault("_inPos_y", self.get_reg(y_id))
                                self.reg_map[z_id] = self.reg_map.setdefault("_inPos_z", self.get_reg(z_id))
                                self.composites[res_id] = [x_id, y_id, z_id]
                                # Only append borg_io if it was just created
                                if ("attribute", "x", self.reg_map[x_id]) not in self.borg_io:
                                    self.borg_io.append(("attribute", "x", self.reg_map[x_id]))
                                if ("attribute", "y", self.reg_map[y_id]) not in self.borg_io:
                                    self.borg_io.append(("attribute", "y", self.reg_map[y_id]))
                                if ("attribute", "z", self.reg_map[z_id]) not in self.borg_io:
                                    self.borg_io.append(("attribute", "z", self.reg_map[z_id]))
                            continue
                        
                        dest = self.get_reg(res_id)
                        struct_type = t[2] if len(t) > 2 else ""
                        if struct_type == "%mat4v4float":
                            # Unroll the exact 16 memory constructs manually
                            comp_ids = []
                            for m in range(16):
                                comp_id = f"{res_id}_m{m}"
                                r = self.get_reg(comp_id)
                                self.reg_map[comp_id] = r
                                comp_ids.append(comp_id)
                                self.borg_io.append(("uniform", f"mvp_{m}", r, m))
                            self.composites[res_id] = comp_ids
                            self.reg_map[res_id] = self.reg_map[comp_ids[0]]
                            continue

                        self.emit(f"flw {dest}, {offset}({base_reg})", f"Load {self.id_to_name.get(ptr_id,'val')}")
                elif self.shader_type == "fragment" and self.storage_classes.get(ptr_id) == "Input":
                    # Fragment shader input variable (not in ptr_map)
                    reg = self.get_reg(res_id)
                    name = self.id_to_name.get(ptr_id, f"in{len(self.borg_io)}")
                    self.emit(f"flw {reg}, 0(attr_{name})", f"Load {name}")
                    self.borg_io.append(("attribute", name, reg))
                    # Register-level ABI: pin fragment inputs to match rasterizer
                    # output slots.  rasterize.s outputs: e0→r0, e1→r1, e2→r2.
                    # The driver loads all uniforms once per triangle, so both
                    # shaders read from the same register file.  Pinning the
                    # fragment attributes avoids a CPU-mediated register copy
                    # between the RAST and FRAG phases of auto-chaining.
                    FRAG_INPUT_ABI = {"e0": 0, "e1": 1, "e2": 2}
                    if name in FRAG_INPUT_ABI:
                        self.borg_io.append(("bind", name, reg, FRAG_INPUT_ABI[name]))
                else:
                    reg = self.get_reg(res_id)

            elif opcode == "OpExtInst":
                op = args[2].lower()
                reg = self.get_reg(res_id)
                if op == "fma":
                    # fma(a, b, c) = a * b + c → fmadd.s rd, rs1, rs2, rs3
                    # Accumulate in-place: result reuses addend register (c).
                    # This keeps the fmadd rs3 and rd as the same physical register,
                    # reducing register pressure and satisfying the 2-bit rs3 constraint.
                    ra = self.get_reg(args[3])
                    rb = self.get_reg(args[4])
                    rc = self.get_reg(args[5])
                    self.reg_map[res_id] = rc  # alias result to addend
                    self.emit(f"fmadd.s {rc}, {ra}, {rb}, {rc}", f"{rc} = {ra} * {rb} + {rc}")
                else:
                    self.emit(f"f{op}.s {reg}, {self.get_reg(args[3])}")
                    if op == "sin":
                        self.vreg_roles[res_id] = "sin"
                        self.borg_io.append(("uniform", "sin", reg, 999))
                    elif op == "cos":
                        self.vreg_roles[res_id] = "cos"
                        self.borg_io.append(("uniform", "cos", reg, 999))

            elif opcode == "OpFNegate":
                reg = self.get_reg(res_id)
                self.emit(f"fneg.s {reg}, {self.get_reg(args[1])}")
                if self.vreg_roles.get(args[1]) == "sin":
                    self.borg_io.append(("uniform", "nsin", reg, 999))

            elif opcode == "OpFMul":
                reg = self.get_reg(res_id)
                ra = self.get_reg(args[1])
                rb = self.get_reg(args[2])
                self.emit(f"fmul.s {reg}, {ra}, {rb}", f"{reg} = {ra} * {rb}")

            elif opcode == "OpFAdd":
                # Check if operands are composites (vec2/vec3/vec4)
                flat_a = self.resolve_flat(args[1])
                flat_b = self.resolve_flat(args[2])
                if len(flat_a) > 1 and len(flat_b) == len(flat_a):
                    # Component-wise vec add — write back to source regs to stay MMIO-accessible
                    # Skip no-op adds (adding f_zero / 0.0)
                    comp_ids = []
                    for i in range(len(flat_a)):
                        comp_id = f"{res_id}_c{i}"
                        if flat_b[i] == "f_zero":
                            # Adding 0.0 is a no-op — just alias the source register
                            self.reg_map[comp_id] = flat_a[i]
                        else:
                            self.reg_map[comp_id] = flat_a[i]  # reuse source register
                            self.emit(f"fadd.s {flat_a[i]}, {flat_a[i]}, {flat_b[i]}", f"translate [{i}]")
                        comp_ids.append(comp_id)
                    self.composites[res_id] = comp_ids
                else:
                    # Scalar add
                    reg = self.get_reg(res_id)
                    ra = flat_a[0]
                    rb = flat_b[0]
                    self.emit(f"fadd.s {reg}, {ra}, {rb}", f"{reg} = {ra} + {rb}")

            elif opcode == "OpCompositeExtract":
                flat = self.resolve_flat(args[1])
                idx = int(args[2])
                if idx < len(flat):
                    self.reg_map[res_id] = flat[idx]

            elif opcode == "OpVectorShuffle":
                vec1 = self.resolve_flat(args[1])
                vec2 = self.resolve_flat(args[2])
                combined = vec1 + vec2
                comp_ids = []
                for i, comp in enumerate(args[3:]):
                    if comp != "4294967295":
                        idx = int(comp)
                        val = combined[idx] if idx < len(combined) else "f_zero"
                        comp_id = f"{res_id}_{i}"
                        comp_ids.append(comp_id)
                        self.reg_map[comp_id] = val
                    else:
                        comp_ids.append("f_zero")
                self.composites[res_id] = comp_ids

            elif opcode == "OpMatrixTimesVector":
                m, v = self.resolve_flat(args[1]), self.resolve_flat(args[2])
                if len(m) == 4 and len(v) >= 2:
                    self.emit(f"fmul.s f30, {m[0]}, {v[0]}", "c*x")
                    self.emit(f"fmadd.s f30, {m[2]}, {v[1]}, f30", "rx = -s*y + c*x")
                    self.emit(f"fmul.s f31, {m[1]}, {v[0]}", "s*x")
                    self.emit(f"fmadd.s f31, {m[3]}, {v[1]}, f31", "ry = c*y + s*x")
                    
                    # Store as vec2 result (vec4 wrapping happens in OpCompositeConstruct)
                    self.composites[res_id] = [res_id+"_x", res_id+"_y"]
                    self.reg_map[res_id+"_x"], self.reg_map[res_id+"_y"] = "f30", "f31"

                elif len(m) == 16 and len(v) >= 4:
                    res_ids = []
                    for row in range(4):
                        dst = f"{res_id}_{row}"
                        res_ids.append(dst)
                        self.reg_map[dst] = self.get_reg(dst)
                        dst_r = self.reg_map[dst]

                        self.emit(f"fmul.s {dst_r}, {m[row]}, {v[0]}", f"M{row}0*v[0]")
                        self.emit(f"fmadd.s {dst_r}, {m[row + 4]}, {v[1]}, {dst_r}", f"M{row}1*v[1]")
                        self.emit(f"fmadd.s {dst_r}, {m[row + 8]}, {v[2]}, {dst_r}", f"M{row}2*v[2]")
                        self.emit(f"fmadd.s {dst_r}, {m[row + 12]}, {v[3]}, {dst_r}", f"M{row}3*v[3]")
                    self.composites[res_id] = res_ids

            elif opcode == "OpStore":
                ptr_id, val_id = args[0], args[1]

                if self.shader_type == "fragment" and self.storage_classes.get(ptr_id) == "Output":
                    # Fragment shader output
                    reg = self.get_reg(val_id)
                    name = self.id_to_name.get(ptr_id, "out")
                    self.emit(f"fsw {reg}, 0(out_{name})", f"Store {name}")
                    self.borg_io.append(("output", name, reg))
                elif ptr_id in self.ptr_map:
                    base_reg, offset = self.ptr_map[ptr_id]

                    # We want to store if it targets a4 (gl_Pos) or a3 (fragColor)
                    if base_reg in ["a3", "a4"]:
                        flat_vals = self.resolve_flat(val_id)
                        for i, r in enumerate(flat_vals):
                            self.emit(f"fsw {r}, {offset + (i*4)}({base_reg})", f"Store {r}")
                        # Track outputs for gl_Position (a4)
                        if base_reg == "a4" and len(flat_vals) >= 2:
                            self.borg_io.append(("output", "rx", flat_vals[0]))
                            self.borg_io.append(("output", "ry", flat_vals[1]))
                            if len(flat_vals) >= 3:
                                self.borg_io.append(("output", "rz", flat_vals[2]))
                            if len(flat_vals) >= 4:
                                self.borg_io.append(("output", "rw", flat_vals[3]))
                else:
                    # Virtual store for local variables (%s, %c, etc.)
                    self.local_vars[ptr_id] = val_id

            elif opcode == "OpReturn": self.emit("ret")

        # Emit Borg register annotations
        if self.borg_io:
            self.asm.append("")
            
            for entry in self.borg_io:
                # Uniform entries are 4-tuples (type, name, reg, member_idx),
                # bind entries are 4-tuples (type, name, vreg, target_preg),
                # others are 3-tuples (type, name, reg).
                io_type, name, reg = entry[0], entry[1], entry[2]
                if io_type == "bind":
                    # @borg bind <vreg> <target_preg>
                    target_preg = entry[3]
                    self.asm.append(f"# @borg bind {reg} {target_preg}")
                else:
                    self.asm.append(f"# @borg {io_type} {name} {reg}")

        return "\n".join(self.asm)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python spirv_compiler.py <input.spvasm> [output.s]")
        sys.exit(1)

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
