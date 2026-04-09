import os
import sys

# Force Pygame to connect to XWayland to bypass broken native Nix Wayland libraries
os.environ["SDL_VIDEODRIVER"] = "x11"
import pygame
import numpy as np

# Load the compiled nanobind module
sys.path.append(os.path.dirname(os.path.abspath(__file__)))
import borg_sim

def main():
    print("Starting Interactive Borg Viewer...")
    pygame.init()
    
    SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
    FW_PATH = os.path.join(SCRIPT_DIR, "../../software/borg/vkcube.bin")
    TEX_PATH = os.path.join(SCRIPT_DIR, "../../software/borg/borg_texture.dat")
    
    if not os.path.exists(FW_PATH):
        print(f"Error: Firmware {FW_PATH} not found.")
        sys.exit(1)
        
    print(f"Loading Firmware: {FW_PATH}")
    sim = borg_sim.BorgSimulator(FW_PATH)
    
    if os.path.exists(TEX_PATH):
        print(f"Loading Texture: {TEX_PATH}")
        sim.load_texture(TEX_PATH)
        
    SCALE = 16
    WIDTH, HEIGHT = 32, 32
    
    screen = pygame.display.set_mode((WIDTH * SCALE, HEIGHT * SCALE))
    pygame.display.set_caption("Borg GPU Interactive Viewer")
    
    clock = pygame.time.Clock()
    
    cycles_simulated = 0
    running = True
    
    # Camera interaction state
    rot_x = -0.4363
    rot_y = 0.6109
    mouse_dragging = False
    last_mouse_pos = (0, 0)

    # Initial camera setup
    sim.set_camera_angles(rot_x, rot_y)

    print("Borg pipeline")
    
    while running:
        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                running = False
            elif event.type == pygame.KEYDOWN and event.key == pygame.K_ESCAPE:
                running = False
            elif event.type == pygame.MOUSEBUTTONDOWN:
                if event.button == 1: # Left click
                    mouse_dragging = True
                    last_mouse_pos = event.pos
            elif event.type == pygame.MOUSEBUTTONUP:
                if event.button == 1:
                    mouse_dragging = False
            elif event.type == pygame.MOUSEMOTION:
                if mouse_dragging:
                    dx = event.pos[0] - last_mouse_pos[0]
                    dy = event.pos[1] - last_mouse_pos[1]
                    # Sensitivity scaling
                    rot_y += dx * 0.01
                    rot_x += dy * 0.01
                    last_mouse_pos = event.pos
                    # Update the simulator PSRAM
                    sim.set_camera_angles(rot_x, rot_y)
                
        # 1. Update uniforms (to be implemented: send new MVP matrix based on rotation)
        # sim.set_uniforms(...)
        
        # 2. Render frame via hardware simulation (Chunked to prevent OS window hangs)
        # Advance the C++ hardware engine by 200,000 cycles per tick.
        # This keeps the 60FPS UI perfectly responsive while the background simulator churns.
        frame_done = False
        for _ in range(5):
            if sim.step(50000):
                frame_done = True
                cycles_simulated += 50000
                break
            cycles_simulated += 50000
        
        if not frame_done:
            # Pumping events but skipping blitting until frame completes
            pygame.display.flip()
            clock.tick(60)
            pygame.display.set_caption(f"Borg GPU | Simulating... {cycles_simulated/1000000:.1f}M cycles | {clock.get_fps():.1f} UI FPS")
            continue
            
        # 3. Fetch framebuffer zero-copy ndarray (shape: 32, 32, 3, dtype: uint8)
        fb_array = sim.get_framebuffer()

        # 4. Blit to Pygame
        # Pygame expects (width, height, 3). The numpy array is already correctly shaped.
        # But we need to use pygame.surfarray.make_surface.
        # Note: Pygame surfaces usually expect width as the first axis. Numpy might be (height, width, 3).
        # We'll rotate/transpose it if needed.
        # The memory array is stored as (Y, X, RGB), so shape is (32, 32, 3).
        # Numpy transpose axis to match Pygame (X, Y, RGB) -> (1, 0, 2)
        transposed_fb = np.transpose(fb_array, (1, 0, 2))
        
        surface = pygame.surfarray.make_surface(transposed_fb)
        
        # Scale to window
        scaled_surface = pygame.transform.scale(surface, (WIDTH * SCALE, HEIGHT * SCALE))
        screen.blit(scaled_surface, (0, 0))
        
        pygame.display.flip()
        
        # Pygame clock tick (limit to 60 fps to save CPU if simulator is too fast)
        # Verilator is doing software emulation, so it might naturally restrict FPS anyway.
        clock.tick(60)
        
        # Print actual FPS to title
        fps = clock.get_fps()
        pygame.display.set_caption(f"Borg GPU Interactive Viewer | {fps:.1f} FPS")

    pygame.quit()

if __name__ == "__main__":
    main()
