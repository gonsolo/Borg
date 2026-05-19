# The Hutt CPU

Hutt (**H**ave **U** **T**ried **T**urning it off and on again?) is a clean
multi-cycle RV32I processor written in Chisel. It replaced the older TinyQV
nibble-serial design to give the Borg SoC a full 32-bit datapath and a simple
Decoupled bus interface that the memory controller and peripheral fabric can
integrate against directly.

Hutt implements the base RV32I instruction set only — no M extension, no
compressed (RV32C) instructions, no CSRs, no traps. FENCE and SYSTEM
instructions are treated as no-ops.

## Module Hierarchy

```text
Hutt
├── HuttRegFile  — 32 × 32-bit integer register file
├── HuttAlu      — combinational RV32I ALU
└── HuttDecode   — combinational instruction decoder (inline, stateless)
```

`Hutt` is the only stateful module. `HuttAlu` and `HuttDecode` are pure
combinational logic; `HuttRegFile` holds the architectural register state.

## Bus Interface

Hutt exposes two Decoupled buses through `HuttIO`:

### Instruction bus (`HuttInstrBus`)

```
req  : Decoupled(UInt(instrAddrWidth.W))   — word address (units of 4 bytes)
resp : Flipped(Decoupled(UInt(32.W)))      — 32-bit instruction word
```

The CPU drives `req.valid` in `sFetchReq`, waits for `req.fire`, then
accepts `resp` in `sFetchResp`. Branch redirect is expressed by presenting a
new `req` before the previous `resp` has been consumed; the memory controller
must accept it.

Default `instrAddrWidth` is 23, addressing 32 MB of SDRAM word-by-word
(8 M words × 4 bytes).

### Data bus (`HuttBus`)

```
req  : Decoupled(HuttBusReq)               — addr, write, size (0=B/1=H/2=W), data
resp : Flipped(Decoupled(UInt(32.W)))
```

Bus contract:
- **Store**: CPU sends data unshifted in the low bits of `req.bits.data`;
  the memory controller positions bytes in the word using `addr[1:0]` and
  `size`.
- **Load**: memory controller returns the addressed bytes already extracted
  to the low bits of `resp.bits`; Hutt sign- or zero-extends the result
  based on `funct3`.

Default `dataAddrWidth` is 28, covering the full SoC address space.

## Five-State FSM

Each RV32I instruction executes in a fixed sequence of FSM states:

```
sFetchReq → sFetchResp → sExec ─┬─ ALU/branch/JAL/LUI/AUIPC → sFetchReq
                                 └─ load/store → sMemReq → sMemResp → sFetchReq
```

| State | Action |
|-------|--------|
| `sFetchReq` | Assert `instr.req.valid`; advance on `req.fire` |
| `sFetchResp` | Assert `instr.resp.ready`; latch instruction word on `resp.fire` |
| `sExec` | Decode, compute results; if load/store capture context and go to `sMemReq`; otherwise writeback + advance PC |
| `sMemReq` | Assert `data.req.valid`; advance on `req.fire` |
| `sMemResp` | Assert `data.resp.ready`; on `resp.fire`: sign/zero-extend and writeback load result, advance PC |

ALU instructions, branches, JAL, JALR, LUI, and AUIPC all complete in
`sExec` — three FSM cycles after the start of the instruction. Loads and
stores add two more cycles (`sMemReq` + `sMemResp`).

## Combinational Decoder (`HuttDecode`)

`HuttDecode.apply(instr)` is a purely combinational function. It extracts
all five immediate encodings (I, S, B, U, J — sign-extended to 32 bits) and
produces one-hot instruction-class flags (`isLui`, `isBranch`, `isLoad`, …)
directly from the 7-bit opcode field. No clock, no registers.

The decoder runs continuously over the `instr` register held by the CPU. Its
outputs are wires, so the FSM can safely read decoded fields in any state
after `sFetchResp` has latched the instruction.

## ALU (`HuttAlu`)

A full 32-bit combinational ALU supporting all ten RV32I operations:

| `AluOp` | Operation |
|---------|-----------|
| `Add` | `a + b` |
| `Sub` | `a - b` |
| `Sll` | `a << b[4:0]` |
| `Slt` | `a <ₛ b` → 0 or 1 |
| `Sltu` | `a <ᵤ b` → 0 or 1 |
| `Xor` | `a ^ b` |
| `Srl` | `a >> b[4:0]` (logical) |
| `Sra` | `a >>ₛ b[4:0]` (arithmetic) |
| `Or` | `a \| b` |
| `And` | `a & b` |

`HuttAluDecode` maps `(funct3, funct7[5])` to an `AluOp` for OP and OP-IMM
instructions. Load, store, JALR, and AUIPC all compute addresses with `Add`
as the default.

## Register File (`HuttRegFile`)

32 entries × 32 bits. Both read ports (`rs1`, `rs2`) are asynchronous.
Writes are synchronous on the rising clock edge when `wen` is asserted.
`x0` is hardwired to zero: writes are silently dropped, reads always return 0.

## Writeback and PC Advance

Non-memory instructions commit in `sExec`:

| Instruction class | Writeback value | Next PC |
|-------------------|----------------|---------|
| OP / OP-IMM | ALU result | PC + 4 |
| JAL | PC + 4 (link) | PC + J-imm |
| JALR | PC + 4 (link) | (rs1 + I-imm) & ~1 |
| LUI | U-imm | PC + 4 |
| AUIPC | PC + U-imm | PC + 4 |
| Branch (not taken) | — | PC + 4 |
| Branch (taken) | — | PC + B-imm |
| FENCE / SYSTEM | — | PC + 4 |

Load writeback happens in `sMemResp` after sign/zero extension:

| `funct3` | Operation |
|---------|-----------|
| `000` LB | Sign-extend byte |
| `001` LH | Sign-extend halfword |
| `010` LW | Full word |
| `100` LBU | Zero-extend byte |
| `101` LHU | Zero-extend halfword |
