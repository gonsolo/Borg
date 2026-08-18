{
  inputs = {
    #nixpkgs.url = "github:gonsolo/nixpkgs/librelane-opensta3-fix";
    #nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

    # librelane 3.0.8, and a base for klayout (overridden to 0.30.9 below,
    # see the overlay's own comment). yosys is already 0.68 here too, which
    # fixes the autoname O(iterations x module size) blowup upstream
    # (YosysHQ/yosys#6050) -- the local yosysFixed patch below is no longer
    # needed.
    # Pinned just past NixOS/nixpkgs#551902 (sv-lang_10: fix build against
    # fmt 12, merged 2026-08-16) rather than a same-day master commit, so
    # this resolves against Hydra's cache instead of forcing a from-source
    # rebuild of nearly everything. The nixos-unstable channel pointer
    # itself (e5bdc4a4) predates that fix by ~8h and hits the exact sv-lang
    # build failure it resolves -- this commit is the next best thing.
    nixpkgs.url = "github:NixOS/nixpkgs/055f428aed456a836a7079a27c5bad1d2b36aa58";

    alejandra.url = "github:kamadorueda/alejandra/4.0.0";
    alejandra.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = {
    self,
    nixpkgs,
    alejandra,
  }: let
    system = "x86_64-linux";
    # nixpkgs' own default python3 is 3.14 here, which breaks or-tools
    # (openroad's dependency, transitively librelane's) -- its meta.broken
    # is conditioned on pythonAtLeast "3.14" (real pybind11 test failures,
    # not yet fixed upstream: NixOS/nixpkgs#551898). or-tools takes python3
    # as a direct override arg, so fix just that one package back to 3.13
    # instead of overriding the whole set -- everything else (openroad,
    # librelane, klayout, yosys, pythonEnv, ...) stays on nixpkgs' own
    # default, maximizing cache hits.
    #
    # doCheck = false: even on 3.13, or-tools' own test suite has one
    # unrelated failure (python_contrib_check_dependencies, a stale
    # pkg_resources/setuptools deprecation check -- 610/611 other tests
    # pass). Same workaround NixOS/nixpkgs#551846's author used.
    # klayout 0.30.9, not nixpkgs' own 0.30.10 -- 0.30.10 has a real
    # regression in how the DRC `.separation()` operator handles fully-
    # overlapping regions (confirmed with wafer-space/gf180mcu-project-
    # template's Leo Moser: it spuriously flags GR.2 -- COMP-to-
    # GUARD_RING_MK spacing -- at the sealring's own reflex corners, even
    # though COMP and GUARD_RING_MK are drawn exactly coincident there by
    # design; verified clean with the PDK's unmodified code once run under
    # 0.30.9 instead). 0.30.9 is also wafer.space's officially supported
    # version. nixpkgs jumped straight from 0.30.8 to 0.30.10 (never
    # packaged 0.30.9), so build it from upstream's own release tag.
    pkgs = import nixpkgs {
      inherit system;
      overlays = [
        (final: prev: {
          or-tools =
            (prev.or-tools.overrideAttrs (_: {doCheck = false;}))
            .override {python3 = final.python313;};
          klayout = prev.klayout.overrideAttrs (old: {
            version = "0.30.9";
            src = prev.fetchFromGitHub {
              owner = "KLayout";
              repo = "klayout";
              tag = "v0.30.9";
              hash = "sha256-5Yu8MP0T4Bb1huuHOXnBYno1WJ4UNKjxYG332A9vrew=";
            };
          });
        })
      ];
    };

    # cocotb has no released Python 3.14 support upstream either
    # (cocotb/cocotb's setup.py hard-caps at 3.13; 3.14 support exists only
    # on cocotb's unreleased master). Pin only cocotb itself to
    # python313Packages -- it doesn't need to share an interpreter with
    # anything else: Borg's own `test/soc/Makefile` drives it via
    # `cocotb-config --makefiles`, which generates a Makefile.sim pointing
    # at cocotb's own bundled interpreter internally, so this needs no
    # PATH/shim wiring at all.
    cocotbForTests = pkgs.python313Packages.cocotb;

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
        # a separate interpreter) rather than pythonEnv's. Since they're all
        # the same underlying python3.14, export PYTHONPATH globally instead
        # of chasing PATH order per-script: whichever python3 wins can then
        # still `import systemrdl` (from pythonEnv) or `import librelane`
        # (from librelane's own site-packages), covering both
        # hardware/rdl/generate.py and asic/wafer.space/scripts/padring.py.
        # librelane's wrapper adds ~150 PYTHONPATH entries (its own package
        # plus every transitive Python dependency, e.g. httpx) -- too many
        # to enumerate by hand, so source the wrapper's own env-setup lines
        # (everything but its final `exec`) in a subshell and capture the
        # PYTHONPATH it computes, rather than reimplementing it.
        export PYTHONPATH="${pythonEnv}/${pkgs.python3.sitePackages}:$(source <(head -n -1 ${pkgs.librelane}/bin/librelane); echo "$PYTHONPATH")''${PYTHONPATH:+:''${PYTHONPATH}}"

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
