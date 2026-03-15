# Edge function rasterizer shader
#
# Evaluates one edge of a triangle for a given pixel position.
# The host calls this shader three times per pixel (once per edge).
# If all three results are >= 0, the pixel is inside the triangle.
#
# The edge function for an edge from vertex A to vertex B at pixel P is:
#
#   e = (bx - ax) * (py - ay) - (by - ay) * (px - ax)
#     = dx * dpy - dy * dpx
#
# To map this to fmul + fmadd (avoiding fmsub), the host negates dy:
#
#   e = dx * dpy + (-dy) * dpx
#
# Register inputs (precomputed by host per edge per pixel):
#   a0 = dx     = bx - ax    (edge vector x component)
#   a1 = neg_dy = -(by - ay) (negated edge vector y component)
#   a2 = dpx    = px - ax    (pixel-to-vertex x delta)
#   a3 = dpy    = py - ay    (pixel-to-vertex y delta)
#
# Register output:
#   a4 = edge function result (>= 0 means inside this edge)
#
    li.s f_zero, 0.0
    li.s f_one, 1.0
    flw f0, 0(a0)            # Load dx
    flw f1, 0(a1)            # Load neg_dy
    flw f2, 0(a2)            # Load dpx
    flw f3, 0(a3)            # Load dpy
    fmul.s f4, f0, f3        # f4 = dx * dpy
    fmadd.s f4, f1, f2, f4   # f4 = neg_dy * dpx + dx * dpy = edge
    fsw f4, 0(a4)            # Store edge result
    ret

# @borg attribute dx f0
# @borg attribute neg_dy f1
# @borg attribute dpx f2
# @borg attribute dpy f3
# @borg output edge f4
