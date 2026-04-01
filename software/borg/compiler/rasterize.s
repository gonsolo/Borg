# Batched edge function rasterizer shader (Step 10.5.2)
#
# Evaluates all three edge functions using hardware-injected pixel centers
# in r30 (px + 0.5) and r31 (py + 0.5).  No per-pixel attribute loading
# needed — the CPU only loads negated vertex positions once per triangle.
#
# Each edge function:
#   dpx = px + (-vx)          via fadd with r30
#   dpy = py + (-vy)          via fadd with r31
#   e   = dx * dpy + neg_dy * dpx
#
# Uniform inputs (loaded once per triangle):
#   dx0, neg_dy0, dx1, neg_dy1, dx2, neg_dy2     (edge constants)
#   neg_vx0, neg_vy0, neg_vx1, neg_vy1, neg_vx2, neg_vy2  (negated vertex positions)
#
# Hardware inputs (per pixel, auto-injected):
#   r30 = coordLut[iter_x] = px + 0.5
#   r31 = coordLut[iter_y] = py + 0.5
#
# Outputs:
#   e0, e1, e2  (>= 0 means inside this edge)
#
    fadd.s  f_dpx0, f_r30, f_neg_vx0       # dpx0 = px - vx0
    fadd.s  f_dpy0, f_r31, f_neg_vy0       # dpy0 = py - vy0
    fmul.s  f_e0,   f_dx0, f_dpy0          # e0  = dx0 * dpy0
    fmadd.s f_e0,   f_ndy0, f_dpx0, f_e0   # e0 += neg_dy0 * dpx0
    fadd.s  f_dpx1, f_r30, f_neg_vx1       # dpx1 = px - vx1
    fadd.s  f_dpy1, f_r31, f_neg_vy1       # dpy1 = py - vy1
    fmul.s  f_e1,   f_dx1, f_dpy1          # e1  = dx1 * dpy1
    fmadd.s f_e1,   f_ndy1, f_dpx1, f_e1   # e1 += neg_dy1 * dpx1
    fadd.s  f_dpx2, f_r30, f_neg_vx2       # dpx2 = px - vx2
    fadd.s  f_dpy2, f_r31, f_neg_vy2       # dpy2 = py - vy2
    fmul.s  f_e2,   f_dx2, f_dpy2          # e2  = dx2 * dpy2
    fmadd.s f_e2,   f_ndy2, f_dpx2, f_e2   # e2 += neg_dy2 * dpx2
    ret

# @borg uniform dx0 f_dx0
# @borg uniform neg_dy0 f_ndy0
# @borg uniform dx1 f_dx1
# @borg uniform neg_dy1 f_ndy1
# @borg uniform dx2 f_dx2
# @borg uniform neg_dy2 f_ndy2
# @borg uniform neg_vx0 f_neg_vx0
# @borg uniform neg_vy0 f_neg_vy0
# @borg uniform neg_vx1 f_neg_vx1
# @borg uniform neg_vy1 f_neg_vy1
# @borg uniform neg_vx2 f_neg_vx2
# @borg uniform neg_vy2 f_neg_vy2
# @borg output e0 f_e0
# @borg output e1 f_e1
# @borg output e2 f_e2
