// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later

// vkcube — textured cube with Borg logo on all visible faces.
// Dynamic GPU Matrix Hardware rendering bounds testing Khronos geometry
// coordinates directly.

#include "borg_driver.h"
#include "borg_fpu.h"
#include "borg_isa.h"
#include "borg_math.h"
#include "borg_sys.h"
#include "compiler/shader_blobs.h"
#include "vkcube_texture_blob.h"

#define FP16_N1 0xBC00 // -1.0 in FP16
#define FP16_P1 0x3C00 //  1.0 in FP16

#ifdef USE_SMALL_TEXTURE
#define TEX_WIDTH 64
#define TEX_HEIGHT 64
#else
#define TEX_WIDTH 256
#define TEX_HEIGHT 256
#endif

// 8 cube vertex positions (Khronos axes, FP16)
static const fp16_t cube_verts[8][3] = {
    {FP16_N1, FP16_N1, FP16_N1}, // 0: Front Top-Left
    {FP16_P1, FP16_N1, FP16_N1}, // 1: Front Top-Right
    {FP16_P1, FP16_P1, FP16_N1}, // 2: Front Bottom-Right
    {FP16_N1, FP16_P1, FP16_N1}, // 3: Front Bottom-Left
    {FP16_N1, FP16_N1, FP16_P1}, // 4: Back Top-Left
    {FP16_P1, FP16_N1, FP16_P1}, // 5: Back Top-Right
    {FP16_P1, FP16_P1, FP16_P1}, // 6: Back Bottom-Right
    {FP16_N1, FP16_P1, FP16_P1}, // 7: Back Bottom-Left
};

// 6 faces as quads: { TL, BL, BR, TR } vertex indices into cube_verts
static const uint8_t cube_faces[6][4] = {
    {0, 3, 2, 1}, // Front
    {1, 2, 6, 5}, // Right
    {4, 0, 1, 5}, // Top
    {5, 6, 7, 4}, // Back
    {4, 7, 3, 0}, // Left
    {3, 7, 6, 2}, // Bottom
};

// Each quad → 2 triangles: (TL,BL,BR) and (TL,BR,TR), always the same UV split.
static const uint8_t quad_qi[2][3] = {{0, 1, 2}, {0, 2, 3}};
static const fp16_t quad_uvs[2][3][2] = {
    {{FP16_ZERO, FP16_ZERO}, {FP16_ZERO, FP16_ONE}, {FP16_ONE, FP16_ONE}},
    {{FP16_ZERO, FP16_ZERO}, {FP16_ONE, FP16_ONE}, {FP16_ONE, FP16_ZERO}},
};

// ---- Host-uploaded geometry (Phase B) -----------------------------------
// The borgvk Mesa driver can ship the app's REAL geometry over serial (a 0xAE
// packet): a deduplicated set of model-space vertices + an indexed triangle
// list with per-triangle-vertex UVs.  When present, this replaces the hardcoded
// cube above so unmodified cube.c renders its own mesh.  Fixed max size keeps
// the 0xAE packet a constant length for the UART drain.
#define RX_GEOM_MAX_VERTS 16
#define RX_GEOM_MAX_TRIS  16
// 0xAE payload after the marker: nverts, ntris, verts(3*fp16), idx(3*u8 per tri),
// uv(3*2*fp16 per tri), checksum.
#define RX_GEOM_PKT_LEN \
  (1 + 2 + RX_GEOM_MAX_VERTS * 6 + RX_GEOM_MAX_TRIS * 3 + RX_GEOM_MAX_TRIS * 12 + 1)
static fp16_t  rx_geom_pos[RX_GEOM_MAX_VERTS * 3];
static uint8_t rx_geom_idx[RX_GEOM_MAX_TRIS * 3];
static fp16_t  rx_geom_uv[RX_GEOM_MAX_TRIS * 3 * 2];
static int     rx_geom_nverts = 0;
static int     rx_geom_ntris  = 0;
static int     rx_have_geom   = 0;
// True once draw_received_geom has successfully rendered a frame (so the
// descriptor has white vertex colors, not stale face_light from draw_cube).
static int     g_geom_recorded = 0;

// Phase-B upload instrumentation (measure HW packet absorption; remove later).
// 32-bit-only: the freestanding firmware has no libgcc 64-bit/popcount helpers.
static unsigned int rx_geom_pkts     = 0; // valid 0xAE packets applied
static unsigned int rx_tex_pkts      = 0; // valid 0xAF packets applied (incl. dups)
static unsigned int rx_csum_fail     = 0; // 0xAE/0xAF checksum mismatches
static unsigned int rx_tex_distinct  = 0; // distinct rows seen (of 64)
static unsigned int rx_tex_mask_lo   __attribute__((unused)) = 0; // rows 0..31
static unsigned int rx_tex_mask_hi   __attribute__((unused)) = 0; // rows 32..63

// 0xAF texture-row packet (Phase B): the host streams the app's texture one row
// at a time as RGB-FP16 (6 B/texel) at the firmware's texture dimension.
//   marker, y, dim texels (dim*6 B), checksum.
#define RX_TEX_DIM       TEX_WIDTH
#define RX_TEX_PKT_LEN   (1 + 1 + RX_TEX_DIM * 6 + 1)

// The shared drain buffer must hold the largest packet.
#define RX_PKT_BUF_LEN \
  (RX_GEOM_PKT_LEN > RX_TEX_PKT_LEN ? RX_GEOM_PKT_LEN : RX_TEX_PKT_LEN)

static void mat4_identity(fp16_t m[16]) {
  for (int i = 0; i < 16; i++)
    m[i] = FP16_ZERO;
  m[0] = FP16_ONE;
  m[5] = FP16_ONE;
  m[10] = FP16_ONE;
  m[15] = FP16_ONE;
}

// MVP = TS · R for the constant projection TS = scale(sxy,sxy,sz)·translate_z(z).
// Only ts[0]=ts[5]=sxy, ts[10]=sz, ts[14]=z, ts[15]=1 are nonzero, so this needs
// 12 muls + 4 fmadds instead of the 64 muls + 48 adds of a full mat4_mul (H1).
static void mat4_mul_ts(fp16_t out[16], const fp16_t ts[16], const fp16_t r[16]) {
  fp16_t sxy = ts[0], sz = ts[10], tz = ts[14];
  for (int col = 0; col < 4; col++) {
    out[col * 4 + 0] = borg_fp16_mul(sxy, r[col * 4 + 0]);
    out[col * 4 + 1] = borg_fp16_mul(sxy, r[col * 4 + 1]);
    out[col * 4 + 2] = borg_fp16_fmadd(sz, r[col * 4 + 2],
                                       borg_fp16_mul(tz, r[col * 4 + 3]));
    out[col * 4 + 3] = r[col * 4 + 3];
  }
}

static void mat4_scale(fp16_t m[16], float sxy, float sz) {
  mat4_identity(m);
  m[0] = fp16_from_float(sxy);
  m[5] = fp16_from_float(sxy);
  m[10] = fp16_from_float(sz);
}

#ifndef TARGET_ULX3S
static void mat4_mul(fp16_t out[16], const fp16_t a[16], const fp16_t b[16]) {
  for (int col = 0; col < 4; col++) {
    for (int row = 0; row < 4; row++) {
      fp16_t sum = FP16_ZERO;
      for (int k = 0; k < 4; k++) {
        fp16_t term = borg_fp16_mul(a[k * 4 + row], b[col * 4 + k]);
        sum = borg_fp16_add(sum, term);
      }
      out[col * 4 + row] = sum;
    }
  }
}

static void mat4_rotate_x(fp16_t m[16], float radians) {
  mat4_identity(m);
  fp16_t a = fp16_from_float(radians);
  fp16_t s = fp16_sin(a), c = fp16_cos(a);
  m[5] = c;
  m[9] = fp16_neg(s);
  m[6] = s;
  m[10] = c;
}

static void mat4_rotate_y(fp16_t m[16], float radians) {
  mat4_identity(m);
  fp16_t a = fp16_from_float(radians);
  fp16_t s = fp16_sin(a), c = fp16_cos(a);
  m[0] = c;
  m[8] = s;
  m[2] = fp16_neg(s);
  m[10] = c;
}
#endif // !TARGET_ULX3S

// Khronos vkcube light direction: negated normalize(0.6, 0.8, 1.0).
// The original direction (+0.424, +0.566, +0.707) points toward Z+, but
// with our MVP the camera looks from Z-, so the visible faces face Z-.
// Negating the light vector ensures the camera-facing faces are illuminated.
#define LIGHT_X 0xB6C9  // ≈ -0.424
#define LIGHT_Y 0xB887  // ≈ -0.566
#define LIGHT_Z 0xB9A8  // ≈ -0.707

// Per-face object-space normals as FP16 {x, y, z}.
// Axis-aligned: each normal has exactly one non-zero component (±1.0).
static const fp16_t face_normal[6][3] = {
    {FP16_ZERO, FP16_ZERO, FP16_N1},  // Face 0: Front  (Z-)
    {FP16_P1,   FP16_ZERO, FP16_ZERO}, // Face 1: Right  (X+)
    {FP16_ZERO, FP16_N1,   FP16_ZERO}, // Face 2: Top    (Y-)
    {FP16_ZERO, FP16_ZERO, FP16_P1},  // Face 3: Back   (Z+)
    {FP16_N1,   FP16_ZERO, FP16_ZERO}, // Face 4: Left   (X-)
    {FP16_ZERO, FP16_P1,   FP16_ZERO}, // Face 5: Bottom (Y+)
};

// Compute lighting for all 6 faces using the model rotation matrix.
// Transforms each face normal by the 3×3 rotation part of `model`, then
// dots with lightDir.  Stores FP16 max(0, dot) into face_light[6].
// Uses the hardware FPU — no soft-float.
static void compute_face_lighting(const fp16_t model[16], fp16_t face_light[6]) {
  const fp16_t Lx = LIGHT_X, Ly = LIGHT_Y, Lz = LIGHT_Z;
  for (int f = 0; f < 6; f++) {
    // Rotate normal: Nw = M * N (3×3 upper-left of column-major matrix)
    //   Nw.x = M[0]*Nx + M[4]*Ny + M[8]*Nz
    //   Nw.y = M[1]*Nx + M[5]*Ny + M[9]*Nz
    //   Nw.z = M[2]*Nx + M[6]*Ny + M[10]*Nz
    fp16_t Nx = face_normal[f][0];
    fp16_t Ny = face_normal[f][1];
    fp16_t Nz = face_normal[f][2];

    fp16_t wx = borg_fp16_add(borg_fp16_add(
                  borg_fp16_mul(model[0], Nx),
                  borg_fp16_mul(model[4], Ny)),
                  borg_fp16_mul(model[8], Nz));
    fp16_t wy = borg_fp16_add(borg_fp16_add(
                  borg_fp16_mul(model[1], Nx),
                  borg_fp16_mul(model[5], Ny)),
                  borg_fp16_mul(model[9], Nz));
    fp16_t wz = borg_fp16_add(borg_fp16_add(
                  borg_fp16_mul(model[2], Nx),
                  borg_fp16_mul(model[6], Ny)),
                  borg_fp16_mul(model[10], Nz));

    // dot(L, Nw)
    fp16_t dot = borg_fp16_add(borg_fp16_add(
                   borg_fp16_mul(Lx, wx),
                   borg_fp16_mul(Ly, wy)),
                   borg_fp16_mul(Lz, wz));

    // max(0.1, dot): ambient floor so back-faces are dim, not black.
    // Khronos reference uses max(0, dot) — we add a small ambient for
    // better visual appearance on the Borg's limited color depth.
    #define FP16_AMBIENT 0x2E66  // 0.1 in FP16
    fp16_t clamped = (dot & 0x8000) ? FP16_ZERO : dot;
    face_light[f] = (clamped < FP16_AMBIENT) ? FP16_AMBIENT : clamped;
  }
}

static void draw_cube(const borg_draw_data_t *draw, const fp16_t face_light[6]) {
  // HPG perf: transform the 8 UNIQUE cube positions once (vs 36 = 12 tris × 3),
  // then draw each triangle by index into the cached transforms.  Bit-identical
  // geometry (same MVP·position), so perf_frag is unchanged.
  fp16_t pos8[8 * 3];
  for (int i = 0; i < 8; i++) {
    pos8[i * 3 + 0] = cube_verts[i][0];
    pos8[i * 3 + 1] = cube_verts[i][1];
    pos8[i * 3 + 2] = cube_verts[i][2];
  }
  borgTransformVerts(draw, pos8, 8);

  for (int f = 0; f < 6; f++) {
    fp16_t l = face_light[f];

    for (int t = 0; t < 2; t++) {
      int idx[3];
      borg_vertex_t tri[3];
      for (int v = 0; v < 3; v++) {
        int ci = cube_faces[f][quad_qi[t][v]];
        idx[v] = ci;
        tri[v] = (borg_vertex_t){
            .color = {l, l, l},
            .uv    = {quad_uvs[t][v][0], quad_uvs[t][v][1]},
        };
      }
      borgCmdDrawIndexed(idx, tri, 0);
    }
  }
}

// Draw host-uploaded geometry (Phase B): the app's real mesh, textured only
// (cube.c has no per-vertex lighting), so vertex color is white.
static void draw_received_geom(const borg_draw_data_t *draw) {
  borgTransformVerts(draw, rx_geom_pos, rx_geom_nverts);
  for (int t = 0; t < rx_geom_ntris; t++) {
    int idx[3];
    borg_vertex_t tri[3];
    for (int v = 0; v < 3; v++) {
      idx[v] = rx_geom_idx[t * 3 + v];
      tri[v] = (borg_vertex_t){
          .color = {FP16_ONE, FP16_ONE, FP16_ONE},
          .uv    = {rx_geom_uv[(t * 3 + v) * 2 + 0], rx_geom_uv[(t * 3 + v) * 2 + 1]},
      };
    }
    borgCmdDrawIndexed(idx, tri, 0);
  }
}

// MEASUREMENT: report exact per-phase cycle counts (Hutt `cycle` CSR) once per
// frame.  The counter is read at phase boundaries, so the UART print cost lands
// OUTSIDE the measured phases — unlike the old busy-wait markers, this perturbs
// nothing.  Host: scripts/measure_fps.py parses the hex counts.  REMOVE later.
static void put_hex32(unsigned int v) {
  for (int i = 28; i >= 0; i -= 4) {
    int nib = (v >> i) & 0xF;
    putc_uart(nib < 10 ? '0' + nib : 'a' + nib - 10);
  }
}
static void report_phase(char tag, unsigned int cyc) {
  putc_uart(tag);
  put_hex32(cyc);
  putc_uart(' ');
}

int main() {
  borgCreateDevice();

  BorgShaderModule vert, rast, frag;
  borgCreateShaderModule(&vert, vert_borg, sizeof(vert_borg));
  borgCreateShaderModule(&rast, rasterize_borg, sizeof(rasterize_borg));
  borgCreateShaderModule(&frag, frag_borg, sizeof(frag_borg));
  borgCreateGraphicsPipeline(&vert, &rast, &frag);

  borg_upload_texture(borg_texture_small_dat, TEX_WIDTH);

  fp16_t ts[16], t1[16];
#ifndef TARGET_ULX3S
  fp16_t rx[16], ry[16];
#endif
  // Constant projection TS = scale(0.5,0.5,0.25) · translate_z(0.5).  Tz·S is
  // just the scale with the z-translate term in [14]; precompute it once (H1)
  // so the per-frame path is only Rx·Ry + one sparse TS multiply.
  mat4_scale(ts, 0.5f, 0.25f);  // big on screen (XY), depth compressed to z∈[0,1]
  ts[14] = fp16_from_float(0.5f);

  // Read shared parameters from PSRAM (offset 2 and 3 -> PSRAM base + 8 and 12)
  union {
    uint32_t u;
    float f;
  } rot_x_reader, rot_y_reader;

#ifdef TARGET_ULX3S
  // The host streams one packet per frame, in one of two formats sharing the
  // drain below (the marker byte selects length + decode):
  //
  //   0xAC : 37 bytes = marker + 9 LE float32 (column-major 3×3 rotation).
  //          Computed host-side (the CPU has no soft-float).  Validated by an
  //          integer-only magnitude check (every entry of a rotation matrix is
  //          in [-1,1], so |value| >= 2 on the raw bits means garbage).  The
  //          firmware bakes MVP = TS · rotation.  Sent by mouse_rotation.py.
  //
  //   0xAD : 66 bytes = marker + 16 LE float32 (column-major 4×4 MVP) + 1 XOR
  //          checksum byte.  cube.c already applied projection·view·model, so
  //          this is used DIRECTLY as draw.uniforms, bypassing the TS bake.
  //          MVP entries aren't bounded to [-1,1], so the checksum (not a
  //          magnitude range) guards against corruption.  Sent by the borgvk
  //          Mesa driver (mesa/src/borg/vulkan/borgvk_queue.c).
  static uint8_t pkt_buf[RX_PKT_BUF_LEN];
  static int pkt_pos = 0;
  static int pkt_marker = 0;   // marker of the in-progress packet (0xAC / 0xAD)
  // Initial orientation: 30° X tilt as a column-major 3×3 matrix
  // (cos=0.866, sin=0.5); matches the script's initial quaternion.
  static float rot_mat[9] = {
    1.0f,   0.0f,    0.0f,    // col 0: [m00, m10, m20]
    0.0f,   0.866f,  0.5f,    // col 1: [m01, m11, m21]
    0.0f,  -0.5f,    0.866f,  // col 2: [m02, m12, m22]
  };
  static float host_mvp[16];  // last full MVP from a valid 0xAD packet
  static int have_mvp = 0;    // 1 once a 0xAD MVP has been received
#endif

  while (1) {
#ifdef TARGET_ULX3S
    // Drain one packet from UART.  The HW has a 1-byte buffer; bytes arriving
    // during borg_present() are dropped, so a frame's drain restarts at a random
    // byte offset.  Scanning for a marker byte is unsafe: a float payload byte
    // can equal 0xAD/0xAC and cause a false mid-packet lock (mis-aligned window,
    // wrong/garbage decode).  Instead we sync on the inter-packet IDLE GAP: the
    // host streams each packet as a contiguous burst (bytes ~87 µs apart)
    // separated by a gap, so once the line has been idle for GAP_CYCLES the next
    // byte is GUARANTEED to be a genuine marker.  Then we read a fixed-length,
    // aligned payload (no marker search) and validate it.
    //   0xAC -> 37 B, 9 floats, accepted if every entry has magnitude < 2
    //           (integer-only check on the raw IEEE-754 bits — no soft-float).
    //   0xAD -> 66 B, 16 floats, accepted if the XOR checksum matches.
    //
    // Greedy texture drain: the firmware otherwise catches only ~1 packet per
    // ~300 ms render, so the host's 64-row texture upload finishes only ~30%
    // (measured R=20/64).  Keep re-draining while 0xAF rows keep arriving so a
    // whole burst is absorbed in one frame's drain window; break on anything
    // else (MVP/geom/idle) so the normal one-packet-per-frame behaviour and the
    // smooth spin are unchanged once the texture has uploaded.
    for (int drain_iter = 0; drain_iter < 16; drain_iter++) {
      int got_tex_row = 0;
      // Measure idle time in REAL cycles via rdcycle() (not loop iterations —
      // this loop's body is far heavier than a bare volatile decrement and the
      // CPU is multi-cycle, so iteration counts don't map to wall time).  At
      // 25 MHz: inter-byte = 87 µs = 2175 cyc, mouse 0xAC gap = 0.8 ms = 20000
      // cyc, 0xAD gap = 6.3 ms.  300 µs = 7500 cyc sits cleanly between the
      // inter-byte time and the smallest inter-packet gap, so it works for both.
      const unsigned GAP_CYCLES   = 7500;      // ~300 µs
      const unsigned GUARD_CYCLES = 4000000;   // ~160 ms hard cap

      // Step 1: advance to a packet boundary — discard bytes until the line has
      // been idle for GAP_CYCLES of real time (resetting the idle timer on every
      // byte seen).  The guard bounds a continuously busy line so the frame
      // never hangs.
      {
        unsigned t0 = rdcycle();        // idle-window start
        unsigned tg = t0;               // guard start
        while ((unsigned)(rdcycle() - t0) < GAP_CYCLES) {
          if (uart_rx_ready()) { (void)getc_uart(); t0 = rdcycle(); }
          if ((unsigned)(rdcycle() - tg) >= GUARD_CYCLES) break;
        }
      }

      // Step 2: wait up to ~15 ms for the next packet's marker.  If none comes,
      // keep the previous transform for this frame.
      for (volatile int t = 375000; !uart_rx_ready() && t > 0; t--) ;
      if (uart_rx_ready()) {
        pkt_marker = (uint8_t)getc_uart();
        int need = (pkt_marker == 0xAC) ? 37 : (pkt_marker == 0xAD) ? 66 :
                   (pkt_marker == 0xAE) ? RX_GEOM_PKT_LEN :
                   (pkt_marker == 0xAF) ? RX_TEX_PKT_LEN : 0;
        if (need) {
          pkt_buf[0] = pkt_marker;
          pkt_pos = 1;
          int ok = 1;
          // Aligned read of the fixed-length payload (short per-byte timeout).
          while (pkt_pos < need) {
            for (volatile int t = 4000; !uart_rx_ready() && t > 0; t--) ;
            if (!uart_rx_ready()) { ok = 0; break; }  // byte dropped mid-packet
            pkt_buf[pkt_pos++] = (uint8_t)getc_uart();
          }

          if (ok && pkt_marker == 0xAC) {
            uint32_t raw[9];
            int valid = 1;
            for (int i = 0; i < 9; i++) {
              int base = 1 + i * 4;
              raw[i] = (uint32_t)pkt_buf[base]          | ((uint32_t)pkt_buf[base+1] << 8) |
                       ((uint32_t)pkt_buf[base+2] << 16) | ((uint32_t)pkt_buf[base+3] << 24);
              if ((raw[i] & 0x7FFFFFFF) >= 0x40000000u) valid = 0;
            }
            if (valid) {
              union { uint32_t u; float f; } conv;
              for (int i = 0; i < 9; i++) { conv.u = raw[i]; rot_mat[i] = conv.f; }
              have_mvp = 0;  // a 0xAC packet reverts to the TS-bake rotation path
            }
          } else if (ok && pkt_marker == 0xAD) {
            uint8_t csum = 0;
            for (int i = 1; i <= 64; i++) csum ^= pkt_buf[i];
            if (csum == pkt_buf[65]) {
              union { uint32_t u; float f; } conv;
              for (int i = 0; i < 16; i++) {
                int base = 1 + i * 4;
                conv.u = (uint32_t)pkt_buf[base]          | ((uint32_t)pkt_buf[base+1] << 8) |
                         ((uint32_t)pkt_buf[base+2] << 16) | ((uint32_t)pkt_buf[base+3] << 24);
                host_mvp[i] = conv.f;
              }
              have_mvp = 1;
            }
            // Checksum mismatch / truncated payload: drop the packet silently and
            // keep the previous MVP (gap-sync makes mis-framing rare anyway).
          } else if (ok && pkt_marker == 0xAE) {
            // Host-uploaded geometry: fixed-offset regions (padded to max size).
            //   [1]=nverts [2]=ntris  verts@3  idx@(3+MAXV*6)  uv@(idx+MAXT*3)
            uint8_t csum = 0;
            for (int i = 1; i < RX_GEOM_PKT_LEN - 1; i++) csum ^= pkt_buf[i];
            int nv = pkt_buf[1], nt = pkt_buf[2];
            if (csum == pkt_buf[RX_GEOM_PKT_LEN - 1] &&
                nv >= 1 && nv <= RX_GEOM_MAX_VERTS &&
                nt >= 1 && nt <= RX_GEOM_MAX_TRIS) {
              int vbase = 3;
              int ibase = vbase + RX_GEOM_MAX_VERTS * 6;
              int ubase = ibase + RX_GEOM_MAX_TRIS * 3;
              for (int i = 0; i < nv * 3; i++)
                rx_geom_pos[i] = (uint16_t)pkt_buf[vbase + i*2] |
                                 ((uint16_t)pkt_buf[vbase + i*2 + 1] << 8);
              for (int i = 0; i < nt * 3; i++)
                rx_geom_idx[i] = pkt_buf[ibase + i];
              for (int i = 0; i < nt * 6; i++)
                rx_geom_uv[i] = (uint16_t)pkt_buf[ubase + i*2] |
                                ((uint16_t)pkt_buf[ubase + i*2 + 1] << 8);
              rx_geom_nverts = nv;
              rx_geom_ntris  = nt;
              rx_have_geom   = 1;
              rx_geom_pkts++;
            } else {
              rx_csum_fail++;
            }
          } else if (ok && pkt_marker == 0xAF) {
            // Host-uploaded texture row: [1]=y, then RX_TEX_DIM texels RGB-FP16.
            uint8_t csum = 0;
            for (int i = 1; i < RX_TEX_PKT_LEN - 1; i++) csum ^= pkt_buf[i];
            int yrow = pkt_buf[1];
            if (csum == pkt_buf[RX_TEX_PKT_LEN - 1] &&
                yrow >= 0 && yrow < RX_TEX_DIM) {
              borg_upload_texture_row(&pkt_buf[2], yrow, RX_TEX_DIM);
              rx_tex_pkts++;
              got_tex_row = 1;
              unsigned int bit = 1u << (yrow & 31);
              if (yrow < 32) {
                if (!(rx_tex_mask_lo & bit)) { rx_tex_mask_lo |= bit; rx_tex_distinct++; }
              } else {
                if (!(rx_tex_mask_hi & bit)) { rx_tex_mask_hi |= bit; rx_tex_distinct++; }
              }
            } else {
              rx_csum_fail++;
            }
          }
        }
      }
      pkt_pos = 0;
      if (!got_tex_row) break;  // not a texture row → stop draining, render this frame
    }
    (void)rot_x_reader;
    (void)rot_y_reader;
#else
    // Read the rotation angles from the host (shared via PSRAM_IN)
    rot_x_reader.u = PSRAM_IN(2);
    rot_y_reader.u = PSRAM_IN(3);

    float rx_f = rot_x_reader.f;
    float ry_f = rot_y_reader.f;

    // Default rotation when no angles set by host (headless / FPGA).
    // Compare raw uint32 to avoid soft-float __eqsf2 (0x0 == IEEE 754 +0.0).
    // Note: the Verilator/Arcilator sim and viewer override these via
    // set_camera_angles() — see simulation/verilator/main.cpp and viewer.py.
    if (rot_x_reader.u == 0 && rot_y_reader.u == 0) {
      rx_f =  0.5236f;  //  30° down in X
      ry_f =  0.7854f;  // +45° in Y
    }
#endif
    unsigned int c0 = rdcycle();

    borg_draw_data_t draw;
#ifdef TARGET_ULX3S
    if (have_mvp) {
      // 0xAD: the host (borgvk / cube.c) already computed the full
      // projection·view·model MVP.  Both it and draw.uniforms are column-major,
      // so convert straight to FP16 — no TS bake, no per-frame matrix multiply.
      for (int i = 0; i < 16; i++)
        draw.uniforms[i] = fp16_from_float(host_mvp[i]);
      // Per-face lighting wants the model rotation, which isn't separable from a
      // baked MVP.  Approximate with the MVP's upper-left 3×3 so faces still
      // differentiate and track the spin.  (A future packet field could ship the
      // model matrix or a precomputed face_light[] for exact lighting.)
      for (int i = 0; i < 16; i++)
        t1[i] = draw.uniforms[i];
    } else {
      // 0xAC: build t1 from the quaternion-derived 3×3 rotation, then MVP = TS·t1.
      mat4_identity(t1);
      t1[0] = fp16_from_float(rot_mat[0]); t1[1] = fp16_from_float(rot_mat[1]); t1[2]  = fp16_from_float(rot_mat[2]);
      t1[4] = fp16_from_float(rot_mat[3]); t1[5] = fp16_from_float(rot_mat[4]); t1[6]  = fp16_from_float(rot_mat[5]);
      t1[8] = fp16_from_float(rot_mat[6]); t1[9] = fp16_from_float(rot_mat[7]); t1[10] = fp16_from_float(rot_mat[8]);
      mat4_mul_ts(draw.uniforms, ts, t1);  // MVP = TS · rotation: sparse, 16 ops vs 112
    }
#else
    mat4_rotate_x(rx, rx_f);
    mat4_rotate_y(ry, ry_f);
    mat4_mul(t1, rx, ry);
    mat4_mul_ts(draw.uniforms, ts, t1);  // MVP = TS · rotation: sparse, 16 ops vs 112
#endif

    unsigned int c1 = rdcycle();  // M = matrix

    unsigned int c2, c3;
    if (rx_have_geom && g_geom_recorded) {
      // Command-buffer record-once: geometry is already in PSRAM with white
      // vertex colors (from draw_received_geom).  Update only clear color + MVP.
      borgFastFrameBegin((rgb16_t){0x3266, 0x3266, 0x3266});
      c2 = rdcycle();
      borgUpdateUniforms(&draw);
      c3 = rdcycle();
    } else {
      borg_clear_zbuffer(0, (rgb16_t){0x3266, 0x3266, 0x3266});
      borg_set_texture(TEX_WIDTH, TEX_HEIGHT);
      fp16_t face_light[6];
      compute_face_lighting(t1, face_light);
      c2 = rdcycle();  // C = clear + texture + lighting
      if (rx_have_geom) {
        // Force full re-recording so borgvk positions/UVs/white colors land in PSRAM
        // (avoids using stale draw_cube descriptor data after g_cmdbuf_valid=1).
        if (!g_geom_recorded)
          borgInvalidateCommandBuffer();
        draw_received_geom(&draw);  // Phase B: the app's real mesh (cube.c)
      } else {
        g_geom_recorded = 0;  // draw_cube uses face_light colors, not compatible with fast path
        draw_cube(&draw, face_light);
      }
      c3 = rdcycle();  // D = draw_cube / draw_received_geom
    }
    borg_present(0);
    // Set g_geom_recorded after present (borgCommandBufferValid updated in borgBinRenderAutonomous).
    if (rx_have_geom && borgCommandBufferValid())
      g_geom_recorded = 1;
    unsigned int c4 = rdcycle();  // P = present (GPU autonomous render)

    // Exact cycle report (hex), printed AFTER all phases so the UART cost is not
    // inside any measured interval.  Throttled to 1 frame in 16: at ~83 chars ×
    // ~2 ms (instruction-starved blind-write putc) the report would otherwise add
    // ~166 ms to every frame, slowing the *visible* demo to ~2.4 fps even though
    // compute is ~3.95 fps.  Reporting every 16th frame keeps the cube near full
    // speed while staying measurable.  (Remove the whole report before shipping.)
    static unsigned int frame_no = 0;
    if ((frame_no++ & 15) == 0) {
      report_phase('M', c1 - c0);
      report_phase('C', c2 - c1);
      report_phase('D', c3 - c2);
      report_phase('P', c4 - c3);
      // HW perf counters: present-phase decomposition (frozen at last seq run).
      // t=total g=frag(core) h=flush l=stall(gpuMem wait) a=dma
      report_phase('t', BORG_GPU->perf_total);
      report_phase('g', BORG_GPU->perf_frag);
      report_phase('h', BORG_GPU->perf_flush);
      report_phase('l', BORG_GPU->perf_stall);
      report_phase('a', BORG_GPU->perf_dma);
      // Phase-B upload telemetry: G=valid geom pkts, X=valid tex pkts,
      // R=distinct tex rows (of 64), F=checksum failures.
      report_phase('G', rx_geom_pkts);
      report_phase('X', rx_tex_pkts);
      report_phase('R', rx_tex_distinct);
      report_phase('F', rx_csum_fail);
      putc_uart('\r');
      putc_uart('\n');
    }

#ifndef TARGET_ULX3S
    // Wait until the host/viewer clears the DONE marker before rendering the
    // next frame.  The interactive simulation viewer clears it in
    // get_framebuffer() to request a new frame.  On hardware we skip this and
    // free-run, re-rendering each frame to animate the spin.
    int done_offset = BORG_FB_WIDTH * BORG_FB_HEIGHT * 2; // TBR tiled: 2 words/pixel, no ZB
    while (PSRAM_OUT(done_offset) == DONE_MARKER)
      ;
#endif
  }
  return 0;
}
