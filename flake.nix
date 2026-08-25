{
  inputs = {
    # gonsolo/nixpkgs#borg-toolchain-bump: our own integration branch,
    # merging several toolchain fixes we need that aren't upstream-merged
    # into NixOS/nixpkgs yet (each also exists as its own open nixpkgs PR --
    # see the branch's commit log for the individual PR numbers):
    #   - librelane 3.0.4 -> 3.0.8
    #   - yosys 0.67 -> 0.68 (fixes the autoname O(iterations x module
    #     size) blowup upstream, YosysHQ/yosys#6050 -- previously needed a
    #     local yosysFixed patch, no longer required)
    #   - or-tools: fix Python 3.14 support (nixpkgs' default python3 is
    #     3.14 here; or-tools -- openroad's dependency, transitively
    #     librelane's -- was broken/meta.broken against it)
    #   - openroad 26Q2 -> 26Q3 (fixes a real upstream bug,
    #     The-OpenROAD-Project/OpenROAD#10743, that crashes antenna-repair
    #     routing -- GRT-0183 heap underflow -- on designs needing many
    #     diode/jumper insertions)
    #   - sv-lang_10: fix build against fmt 12
    #   - klayout 0.30.10 -> 0.30.11 (0.30.10 has a real regression in how
    #     the DRC `.separation()` operator handles fully-overlapping
    #     regions, confirmed with wafer-space/gf180mcu-project-template's
    #     Leo Moser: it spuriously flags GR.2 -- COMP-to-GUARD_RING_MK
    #     spacing -- at the sealring's own reflex corners, even though
    #     COMP and GUARD_RING_MK are drawn exactly coincident there by
    #     design. 0.30.11 contains the upstream fix, KLayout/klayout#2425,
    #     merged 2026-08-20 -- verified clean with the PDK's unmodified
    #     code)
    # Pinned to a specific commit (not just the branch name) for
    # reproducibility. Switch back to a plain NixOS/nixpkgs commit once
    # these merge upstream.
    nixpkgs.url = "github:gonsolo/nixpkgs/fa81aeaea883a8d5d719666b7704f7f8ddd159cc";

    alejandra.url = "github:kamadorueda/alejandra/4.0.0";
    alejandra.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = {
    self,
    nixpkgs,
    alejandra,
  }: let
    system = "x86_64-linux";
    pkgs = import nixpkgs {inherit system;};

    # cocotb has no released Python 3.14 support upstream either
    # (cocotb/cocotb's setup.py hard-caps at 3.13; 3.14 support exists only
    # on cocotb's unreleased master). Bundle it with everything
    # test/soc's cocotb-based tests actually import: numpy (a real runtime
    # dependency of cocotb's own internals, not obvious from Borg's test
    # files -- CI caught this) and riscv-model (imported directly by
    # test/soc/test.py, test_util.py, tqv.py).
    #
    # This env's own PYTHONPATH must be used verbatim, not merged into the
    # shell's ambient one: nix's devShell construction aggregates
    # PYTHONPATH from every python.withPackages input regardless of
    # interpreter version, so having this (3.13) alongside pythonEnv
    # (3.14) in the same shell leaves plain `$PYTHONPATH` a mix of both --
    # cocotb's own numpy import then resolves to whichever copy (3.13 or
    # 3.14-compiled) happens to land first, which silently breaks it (CI
    # caught this too). See COCOTB_PYTHONPATH below and its use in the top
    # Makefile's TEST_SOC.
    cocotbForTests = pkgs.python313.withPackages (p: [p.cocotb p.numpy p.riscv-model]);

    pythonEnv = pkgs.python3.withPackages (p: [
      p.cairosvg
      p.chevron
      p.gdstk
      p.gitpython
      p.graphviz # for gen_hw_diagram.py
      p.jinja2
      p.klayout
      p.matplotlib
      p.mistune
      p.numpy
      p.peakrdl-cli
      p.peakrdl-cheader
      p.pip
      p.pygame
      p.pyaml
      p.pytest
      p.requests
      p.riscv-model
      p.systemrdl-compiler
      p.nanobind
      p.evdev
      p.pyserial
      p.mako # Mesa build (code generation)
      p.pyyaml # Mesa build
    ]);

    # Curated TeX Live for docs/poster (poster.tex + abstract.tex, HPG 2026)
    # and docs/talk (talk.tex, ORConf 2026) only.  The full scheme
    # (scheme-full) pulled thousands of obscure packages (e.g. qualitype,
    # lpform) whose cache.nixos.org artifacts are corrupt/hash-mismatched,
    # breaking every CI job that enters the dev shell.  We list just what
    # these two need — texlive.combine resolves each package's deps — and
    # keep it OUT of the default shell so CI never fetches it.

    # OpenSBI source — pkgs.opensbi.src is already an unpacked directory
    # (nixpkgs fetches it via fetchFromGitHub).  Pinned at v1.8.1 by nixpkgs.
    opensbiSrc = pkgs.opensbi.src;

    # Linux kernel source — pkgs.linux.src is a .tar.xz; unpack it into a
    # derivation so the Makefile can do `make -C $LINUX_SRC`.
    # Pinned at 6.12.x LTS by the nixpkgs commit in flake.nix.
    linuxSrc = pkgs.runCommand "linux-${pkgs.linux.version}-src" {} ''
      mkdir $out
      tar -xJf ${pkgs.linux.src} -C $out --strip-components=1
    '';

    borgTexlive = pkgs.texlive.combine {
      inherit (pkgs.texlive)
        scheme-small   # latex + pdflatex + latexmk + common (collection-latexrecommended)
        latexmk
        biber          # biber tool + biblatex backend
        biblatex
        acmart         # abstract.tex documentclass (+deps)
        tikzposter     # poster.tex documentclass (+deps)
        beamer         # docs/talk (ORConf 2026 slides) documentclass (+deps)
        qrcode
        microtype
        enumitem
        booktabs
        pgf            # tikz
        xcolor
        lm;            # Latin Modern (lmodern)
    };
  in {
    devShells.${system} = {
    default = pkgs.mkShell {
      # Use nativeBuildInputs for tools that provide executables
      nativeBuildInputs = [
        pkgs.bash-completion
        pkgs.bc           # Linux kernel build scripts
        pkgs.bear
        pkgs.bitwuzla
        pkgs.bzip2
        pkgs.dtc          # device tree compiler (borg.dts → borg.dtb)
        pkgs.circt
        pkgs.circt.llvm
        pkgs.cmake
        pkgs.coreutils
        pkgs.gcc
        pkgs.git
        pkgs.glslang
        pkgs.gnugrep
        pkgs.gnumake
        pkgs.gnused
        pkgs.graphviz # dot binary for gen_hw_diagram.py
        pkgs.ghostscript
        pkgs.inkscape
        pkgs.iverilog
        pkgs.icestorm
        pkgs.jdk21
        pkgs.klayout
        # yosys override: LibreLane bundles its own internal yosys, which
        # previously hit the autoname O(iterations x module size) blowup
        # (YosysHQ/yosys#5394, 4509, 2816) that made full Hutt+Borg SoC
        # synthesis take 49GB+/never complete -- confirmed hitting it
        # directly: asic/wafer.space's librelane run had yosys-abc at 7.3GB
        # RSS and climbing during ABC tech-mapping. Fixed upstream in yosys
        # 0.68 (YosysHQ/yosys#6050); force LibreLane onto nixpkgs' yosys
        # (now 0.68) instead of its own bundled copy.
        (pkgs.librelane.override { yosys = pkgs.yosys; })
        pkgs.magic-vlsi
        pkgs.metals
        pkgs.mill
        pkgs.meson # Mesa/borgvk build
        pkgs.ninja # Mesa/borgvk build
        pkgs.bison # Mesa build
        pkgs.flex # Mesa build
        # Rust toolchain for the borgvk NIR->Borg shader compiler (Phase C),
        # modeled on Mesa's NAK. Mesa requires bindgen >= 0.71.1 (have 0.72.1)
        # for its NIR Rust bindings (src/compiler/rust), plus rustc/cargo/rustfmt.
        pkgs.rustc
        pkgs.cargo
        pkgs.rustfmt
        pkgs.rust-bindgen
        pkgs.rust-cbindgen
        pkgs.vulkan-tools # vulkaninfo (borgvk enumeration gate)
        pkgs.mpremote
        pkgs.netgen-vlsi
        pkgs.nextpnr
        pkgs.openfpgaloader
        pkgs.openroad
        pkgs.pandoc
        pkgs.pkg-config
        pkgs.scalafmt
        pkgs.trellis
        pkgs.tio
        pkgs.typst
        pkgs.verilator
        pkgs.which
        pkgs.yosys
        pkgs.z3
        pkgs.pkgsCross.riscv32-embedded.buildPackages.gcc
        pkgs.pkgsCross.riscv32-embedded.buildPackages.binutils
        # riscv64 bare-metal toolchain — firmware build (software/borg, software/hutt).
        # Provides riscv64-none-elf-gcc/as/ld/objcopy.
        pkgs.pkgsCross.riscv64-embedded.buildPackages.gcc
        pkgs.pkgsCross.riscv64-embedded.buildPackages.binutils
        # riscv64 Linux cross toolchain — Gate 2: cross-build borgvk for the
        # future RV64 Hutt Linux target.  Confirms the driver is RV64 + soft-float
        # (lp64) clean.  Buildroot will own the final rootfs ABI; this just gates
        # the code.  ($CROSS64 below names the binutils/gcc prefix.)
        pkgs.pkgsCross.riscv64.buildPackages.gcc
        pkgs.pkgsCross.riscv64.buildPackages.binutils
        pythonEnv
        cocotbForTests
      ];

      # Library dependencies for the Mesa "borgvk" Vulkan driver. Kept in
      # buildInputs so pkg-config picks up their headers/.pc files. X11/xcb
      # and Wayland are for hosting unmodified Vulkan-Tools/cube.c; the real
      # output is the ULX3S HDMI (build -Dplatforms=x11,wayland).
      buildInputs = [
        pkgs.vulkan-headers
        pkgs.vulkan-loader
        pkgs.libdrm
        pkgs.spirv-headers
        pkgs.spirv-tools
        pkgs.expat
        pkgs.zlib
        pkgs.zstd
        pkgs.libffi
        pkgs.libxml2
        pkgs.libxcb
        pkgs.libx11
        pkgs.libxext
        pkgs.libxrandr
        pkgs.libxfixes
        pkgs.libxshmfence
        pkgs.libxcb-keysyms
        pkgs.wayland.dev
        pkgs.wayland-protocols
        pkgs.wayland-scanner
      ];

      shellHook = ''
        export GONSOLO_PROJECT="borg_tinyqv"

        # cocotbForTests' own site-packages (cocotb, numpy, riscv-model),
        # to be used verbatim -- not merged into the shell's ambient
        # PYTHONPATH -- when invoking cocotb-based tests. See
        # cocotbForTests' own comment above for why.
        export COCOTB_PYTHONPATH="${cocotbForTests}/${pkgs.python313.sitePackages}"

        # OpenSBI + Linux kernel sources (pinned via nixpkgs; no manual hashes).
        export OPENSBI_SRC="${opensbiSrc}"
        export LINUX_SRC="${linuxSrc}"

        # Gate 2: riscv64 Linux cross toolchain prefix (borgvk RV64 cross-build).
        # Also used for OpenSBI — riscv64-unknown-linux-gnu-gcc can build freestanding
        # firmware with -march=rv64imac_zicsr -mabi=lp64 (set in config.mk).
        export CROSS64=riscv64-unknown-linux-gnu

        # PURE MODE COMPATIBILITY:
        # 1. Mill/Java require a HOME to write lockfiles and caches.
        # If we are in --ignore-environment, HOME is empty.
        if [ -z "$HOME" ] || [ "$HOME" = "/" ]; then
          export HOME=$(pwd)/.nix-home
          mkdir -p $HOME
          echo "Notice: Pure mode detected. Using local $HOME for caches."
        fi

        # 2. Point to the JDK21 home so Java apps don't have to search the PATH
        export JAVA_HOME=${pkgs.jdk21}

        # 3. bindgen (Mesa NIR Rust bindings) needs libclang to parse C headers.
        export LIBCLANG_PATH="${pkgs.llvmPackages.libclang.lib}/lib"

        echo "Entering $GONSOLO_PROJECT development shell..."

        # Create a bin directory in our local nix-home
        mkdir -p $HOME/bin

        # Link native yosys to the name the python script is looking for
        ln -sf ${pkgs.yosys}/bin/yosys $HOME/bin/yowasp-yosys

        # Bare `python3` on PATH can resolve to any nativeBuildInput's own
        # bundled interpreter (e.g. klayout's, or librelane's own wrapper --
        # which is itself just nixpkgs' python3 plus PYTHONPATH entries, not
        # a separate interpreter) rather than pythonEnv's.
        #
        # NOTE: a global `export PYTHONPATH=...` here previously broke
        # cocotb (python3.13) by leaking pythonEnv's/librelane's python3.14
        # numpy onto its import path -- PYTHONPATH is inherited by every
        # child process, not just "bare python3" PATH resolution, so it
        # doesn't stay scoped to the scripts that actually need it. Use
        # per-script named wrappers instead, each setting PYTHONPATH only
        # for its own exec:
        #  - python3-borg-rdl: pythonEnv's python3 (systemrdl-compiler
        #    etc.), for the top Makefile's `rdl` target.
        #  - python3-librelane: pythonEnv's python3 plus whatever
        #    PYTHONPATH librelane's own wrapper computes for itself
        #    (~150 entries -- its own package plus every transitive
        #    Python dependency, e.g. httpx -- too many to enumerate by
        #    hand, so source the wrapper's env-setup lines, everything but
        #    its final `exec`, and capture the result), for
        #    asic/wafer.space/scripts/padring.py.
        ln -sf ${pythonEnv}/bin/python3 $HOME/bin/python3-borg-rdl
        cat > $HOME/bin/python3-librelane << 'WRAPPER_EOF'
#!${pkgs.bash}/bin/bash
export PYTHONPATH="$(source <(head -n -1 ${pkgs.librelane}/bin/librelane); echo "$PYTHONPATH")"
exec ${pythonEnv}/bin/python3 "$@"
WRAPPER_EOF
        chmod +x $HOME/bin/python3-librelane

        # Ensure our shim is at the front of the PATH
        export PATH="$HOME/bin:$PATH"

        # Wayland: Nix splits wayland.xml into wayland-scanner (not wayland-client).
        # cmake's BUILD_WSI_WAYLAND_SUPPORT queries wayland-client pkgdatadir for
        # wayland.xml but finds an empty dir.  Override with a local .pc that
        # redirects pkgdatadir to the scanner's share/wayland — no upstream patch needed.
        _wl_pc=$(mktemp -d)
        sed 's|pkgdatadir=.*|pkgdatadir=${pkgs.wayland-scanner}/share/wayland|' \
          ${pkgs.wayland.dev}/lib/pkgconfig/wayland-client.pc > "$_wl_pc/wayland-client.pc"
        export PKG_CONFIG_PATH="$_wl_pc''${PKG_CONFIG_PATH:+:''${PKG_CONFIG_PATH}}"

        # Gate 2: emit a meson cross file for the riscv64 Linux target so we can
        # cross-build the borgvk driver (proves it links for RV64; the unknown for
        # the future on-Hutt Linux stack).  Toolchain ABI is rv64gc/lp64d (the
        # pkgsCross default); Buildroot owns the final soft-float lp64 rootfs.
        # pkg_config_path points at riscv64-cross target libs (libdrm/expat/zlib/
        # zstd), kept out of the native PKG_CONFIG_PATH so host builds are unaffected.
        export BORG_RV64_PCPATH="${pkgs.pkgsCross.riscv64.libdrm.dev}/lib/pkgconfig:${pkgs.pkgsCross.riscv64.expat.dev}/lib/pkgconfig:${pkgs.pkgsCross.riscv64.zlib.dev}/lib/pkgconfig:${pkgs.pkgsCross.riscv64.zstd.dev}/lib/pkgconfig"
        cat > "$(pwd)/mesa/riscv64-cross.txt" <<CROSSEOF
[binaries]
c = '${pkgs.pkgsCross.riscv64.buildPackages.gcc}/bin/riscv64-unknown-linux-gnu-gcc'
cpp = '${pkgs.pkgsCross.riscv64.buildPackages.gcc}/bin/riscv64-unknown-linux-gnu-g++'
ar = '${pkgs.pkgsCross.riscv64.buildPackages.binutils}/bin/riscv64-unknown-linux-gnu-ar'
strip = '${pkgs.pkgsCross.riscv64.buildPackages.binutils}/bin/riscv64-unknown-linux-gnu-strip'
pkg-config = '${pkgs.pkg-config}/bin/pkg-config'

[built-in options]
pkg_config_path = '$BORG_RV64_PCPATH'

[host_machine]
system = 'linux'
cpu_family = 'riscv64'
cpu = 'riscv64'
endian = 'little'
CROSSEOF
      '';
    };

    # Poster shell: everything in the default shell PLUS the curated TeX Live,
    # for building docs/poster and docs/talk. Use `nix develop .#poster
    # --command make -C docs/poster` (or `-C docs/talk`).
    # Kept separate so CI (which uses the default shell) never fetches texlive.
    poster = pkgs.mkShell {
      inputsFrom = [ self.devShells.${system}.default ];
      nativeBuildInputs = [ borgTexlive ];
    };
    };

    formatter.${system} = alejandra.defaultPackage.${system};
  };
}
