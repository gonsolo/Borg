import sys
sys.path.insert(0, "build_fast")
import borg_sim

def main():
    print("Starting headless fast_sim test...")
    sim = borg_sim.BorgSimulator("../../software/borg/vkcube.bin", True)
    print("Simulator created. Stepping 10,000,000 cycles...")
    # Step 10,000,000 cycles
    for i in range(1000):
        done = sim.step(10000)
        if done:
            print(f"Frame finished early at iteration {i} ({(i+1)*10000} cycles)!")
            break
    print("Done stepping.")
    print("Done stepping.")
    fb = sim.get_framebuffer()
    print(f"Framebuffer shape: {fb.shape}")

if __name__ == "__main__":
    main()
