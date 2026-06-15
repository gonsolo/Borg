"""Headless fast-sim smoke test — exercises the nanobind borg_sim module.

Loads vkcube firmware, runs until the first frame completes (or 10M cycles),
then asserts the framebuffer is correctly shaped and contains non-black pixels.
"""
import sys
sys.path.insert(0, "build_fast")
import borg_sim
import numpy as np

def main():
    sim = borg_sim.BorgSimulator("../../software/borg/vkcube.bin", True)

    done = False
    for i in range(1000):
        if sim.step(10000):
            done = True
            print(f"[headless] Frame done at {(i+1)*10000} cycles")
            break

    assert done, "Frame did not complete within 10M cycles — render hang?"

    fb = sim.get_framebuffer()
    assert fb.shape == (32, 32, 3), f"Unexpected framebuffer shape: {fb.shape}"
    assert fb.max() > 0, "Framebuffer is all-black — GPU produced no output"

    non_black = np.count_nonzero(fb.sum(axis=2))
    assert non_black > 10, f"Too few lit pixels: {non_black} (expected > 10)"

    print(f"[headless] PASS — {non_black}/{fb.shape[0]*fb.shape[1]} pixels lit")

if __name__ == "__main__":
    main()
