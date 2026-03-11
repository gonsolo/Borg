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
