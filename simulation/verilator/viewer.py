import os
import sys

# Force Pygame to connect to XWayland to bypass broken native Nix Wayland libraries
os.environ["SDL_VIDEODRIVER"] = "x11"
import pygame
import numpy as np

# Load the compiled nanobind module
script_dir = os.path.dirname(os.path.abspath(__file__))
use_arc = '--arc' in sys.argv or 'arc_viewer' in os.path.basename(sys.argv[0])
sys.path.insert(0, os.path.join(script_dir, 'build'))
sys.path.insert(0, os.path.join(script_dir, '../arcilator/build'))
sys.path.append(script_dir)

if use_arc:
    import arc_sim as borg_sim
else:
    import borg_sim

def main():
    print("Starting Borg Kernel Viewer...")
    print("borgvk must be running (connected to the sim socket) to receive content.")
    pygame.init()

    SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
    FW_PATH = os.path.join(SCRIPT_DIR, "../../software/borg/kernel.bin")

    if not os.path.exists(FW_PATH):
        print(f"Error: Firmware {FW_PATH} not found.")
        sys.exit(1)

    print(f"Loading Firmware: {FW_PATH}")
    sim = borg_sim.BorgSimulator(FW_PATH, 32, 32)

    WIDTH, HEIGHT = sim.width, sim.height
    SCALE = max(1, 512 // WIDTH)

    screen = pygame.display.set_mode((WIDTH * SCALE, HEIGHT * SCALE))
    pygame.display.set_caption("Borg GPU Viewer")

    clock = pygame.time.Clock()
    cycles_simulated = 0
    running = True

    print("Waiting for borgvk packets...")

    while running:
        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                running = False
            elif event.type == pygame.KEYDOWN and event.key == pygame.K_ESCAPE:
                running = False

        frame_done = False
        for _ in range(5):
            if sim.step(50000):
                frame_done = True
                cycles_simulated += 50000
                break
            cycles_simulated += 50000

        if not frame_done:
            pygame.display.flip()
            clock.tick(60)
            pygame.display.set_caption(f"Borg GPU | Waiting for borgvk... {cycles_simulated/1000000:.1f}M cycles")
            continue

        fb_array = sim.get_framebuffer()
        transposed_fb = np.transpose(fb_array, (1, 0, 2))
        surface = pygame.surfarray.make_surface(transposed_fb)
        scaled_surface = pygame.transform.scale(surface, (WIDTH * SCALE, HEIGHT * SCALE))
        screen.blit(scaled_surface, (0, 0))
        pygame.display.flip()

        clock.tick(60)
        fps = clock.get_fps()
        pygame.display.set_caption(f"Borg GPU | {cycles_simulated/1000000:.1f}M cycles | {fps:.1f} FPS")

    pygame.quit()

if __name__ == "__main__":
    main()
