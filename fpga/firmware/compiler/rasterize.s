# Batched edge function rasterizer shader
#
# Evaluates all three edge functions of a triangle for a given pixel position
# in a single Borg execution.  The host loads edge constants (uniforms) once
# per triangle and pixel deltas (attributes) per pixel.
#
# Each edge function:
#   e = dx * dpy + neg_dy * dpx
#
# Uniform inputs (loaded once per triangle):
#   dx0, neg_dy0, dx1, neg_dy1, dx2, neg_dy2
#
# Attribute inputs (loaded per pixel):
#   dpx0, dpy0, dpx1, dpy1, dpx2, dpy2
#
# Outputs:
#   e0, e1, e2  (>= 0 means inside this edge)
#
    fmul.s  f_e0, f_dx0, f_dpy0         # e0  = dx0 * dpy0
    fmadd.s f_e0, f_ndy0, f_dpx0, f_e0  # e0 += neg_dy0 * dpx0
    fmul.s  f_e1, f_dx1, f_dpy1         # e1  = dx1 * dpy1
    fmadd.s f_e1, f_ndy1, f_dpx1, f_e1  # e1 += neg_dy1 * dpx1
    fmul.s  f_e2, f_dx2, f_dpy2         # e2  = dx2 * dpy2
    fmadd.s f_e2, f_ndy2, f_dpx2, f_e2  # e2 += neg_dy2 * dpx2
    ret

# @borg uniform dx0 f_dx0
# @borg uniform neg_dy0 f_ndy0
# @borg uniform dx1 f_dx1
# @borg uniform neg_dy1 f_ndy1
# @borg uniform dx2 f_dx2
# @borg uniform neg_dy2 f_ndy2
# @borg attribute dpx0 f_dpx0
# @borg attribute dpy0 f_dpy0
# @borg attribute dpx1 f_dpx1
# @borg attribute dpy1 f_dpy1
# @borg attribute dpx2 f_dpx2
# @borg attribute dpy2 f_dpy2
# @borg output e0 f_e0
# @borg output e1 f_e1
# @borg output e2 f_e2
