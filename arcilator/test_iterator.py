import sys

with open("triangle_00.ppm") as f:
    lines = f.read().split()

w, h = int(lines[1]), int(lines[2])
pixels = lines[4:]

print("Row 20:")
for x in range(16):
    idx = (20*w + x)*3
    print(f"x={x}: r={pixels[idx]}, g={pixels[idx+1]}, b={pixels[idx+2]}")

