import collections
import os
import socket as socket_module
import struct
import sys
import threading

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

# Default socket path; override with BORGVK_SIM_SOCKET env var.
BORGVK_SIM_SOCKET = os.environ.get('BORGVK_SIM_SOCKET', '/tmp/borgvk_sim.sock')


def socket_server(path, byte_deque, stop_event):
    """Listen for borgvk connections; append received bytes to byte_deque."""
    try:
        os.unlink(path)
    except FileNotFoundError:
        pass
    srv = socket_module.socket(socket_module.AF_UNIX, socket_module.SOCK_STREAM)
    srv.bind(path)
    srv.listen(1)
    srv.settimeout(0.5)
    print(f"[viewer] borgvk socket: {path}")
    while not stop_event.is_set():
        try:
            conn, _ = srv.accept()
        except socket_module.timeout:
            continue
        print("[viewer] borgvk connected")
        with conn:
            conn.settimeout(0.1)
            while not stop_event.is_set():
                try:
                    data = conn.recv(4096)
                except socket_module.timeout:
                    continue
                if not data:
                    print("[viewer] borgvk disconnected")
                    break
                byte_deque.append(data)
    try:
        os.unlink(path)
    except FileNotFoundError:
        pass


def main():
    print("Starting Borg Kernel Viewer...")
    pygame.init()

    SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
    FW_PATH = os.path.join(SCRIPT_DIR, "../../software/borg/kernel.bin")

    if not os.path.exists(FW_PATH):
        print(f"Error: Firmware {FW_PATH} not found.")
        sys.exit(1)

    print(f"Loading Firmware: {FW_PATH}")
    # 128x128 matches borgCreateDevice()'s fallback framebuffer size (DRAM_IN
    # width/height negotiation only works when a host writes those DRAM words
    # before boot, which this sim harness doesn't do) — constructing at any
    # other size desyncs the driver's FRAME_STRIDE from what this class
    # expects and frame-completion is never detected.
    sim = borg_sim.BorgSimulator(FW_PATH, 128, 128)

    # kernel.bin is built at CLOCK_MHZ=25 (matching ULX3S) — the borgvk UART
    # drain loop's software polling needs that many cycles/bit of margin (the
    # hardware UART receiver stalls until the CPU reads out each buffered
    # byte, so bytes-per-poll-overhead must fit inside the bit period). Must
    # match the firmware's own UART_BAUD divisor: 115200 baud @ 25 MHz ≈ 217.
    sim.uart_set_cycles_per_bit(217)

    # Pre-gap: firmware needs ~3.5M cycles to boot and reach the drain loop.
    sim.uart_inject_gap(3500000)

    WIDTH, HEIGHT = sim.width, sim.height
    SCALE = max(1, 512 // WIDTH)

    screen = pygame.display.set_mode((WIDTH * SCALE, HEIGHT * SCALE))
    pygame.display.set_caption("Borg GPU Viewer")
    clock = pygame.time.Clock()

    # borgvk bytes arrive on a background thread and are queued here.
    # collections.deque is thread-safe for append (writer) + popleft (reader).
    byte_deque = collections.deque()
    stop_event = threading.Event()
    srv_thread = threading.Thread(
        target=socket_server,
        args=(BORGVK_SIM_SOCKET, byte_deque, stop_event),
        daemon=True,
    )
    srv_thread.start()

    cycles_simulated = 0
    running = True

    while running:
        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                running = False
            elif event.type == pygame.KEYDOWN and event.key == pygame.K_ESCAPE:
                running = False

        # Drain all borgvk bytes that arrived since the last iteration and
        # inject them into the UART transmitter.  borgvk paces its packet
        # writes (see borgvk_transport_write_paced in borgvk_serial.c) so the
        # firmware's gap-sync can find a genuine idle period between packets.
        while byte_deque:
            sim.uart_inject(byte_deque.popleft())

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
            pygame.display.set_caption(
                f"Borg GPU | Waiting for borgvk... {cycles_simulated/1000000:.1f}M cycles"
            )
            continue

        fb_array = sim.get_framebuffer()
        transposed_fb = np.transpose(fb_array, (1, 0, 2))
        surface = pygame.surfarray.make_surface(transposed_fb)
        scaled_surface = pygame.transform.scale(surface, (WIDTH * SCALE, HEIGHT * SCALE))
        screen.blit(scaled_surface, (0, 0))
        pygame.display.flip()

        clock.tick(60)
        fps = clock.get_fps()
        pygame.display.set_caption(
            f"Borg GPU | {cycles_simulated/1000000:.1f}M cycles | {fps:.1f} FPS"
        )

    stop_event.set()
    pygame.quit()


if __name__ == "__main__":
    main()
