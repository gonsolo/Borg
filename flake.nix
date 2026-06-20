{
  inputs = {
    #nixpkgs.url = "github:gonsolo/nixpkgs/librelane-opensta3-fix";
    #nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

    # librelane 3.0.3
    nixpkgs.url = "github:NixOS/nixpkgs/220a1d1bac3d8706a19e2cf715bf0dcdb6b1102c";

    alejandra.url = "github:kamadorueda/alejandra/4.0.0";
    alejandra.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = {
    self,
    nixpkgs,
    alejandra,
  }: let
    system = "x86_64-linux";
    pkgs = nixpkgs.legacyPackages.${system};

    pythonEnv = pkgs.python313.withPackages (p: [
      p.cairosvg
      p.chevron
      p.cocotb
      p.gdstk
      p.gitpython
      p.graphviz # for gen_hw_diagram.py
      p.jinja2
      p.klayout
      p.matplotlib
      p.mistune
      p.numpy
      p.peakrdl
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

    # Curated TeX Live for the HPG poster (docs/poster: poster.tex + abstract.tex)
    # only.  The full scheme (scheme-full) pulled thousands of obscure packages
    # (e.g. qualitype, lpform) whose cache.nixos.org artifacts are corrupt/hash-
    # mismatched, breaking every CI job that enters the dev shell.  We list just
    # what the poster needs — texlive.combine resolves each package's deps — and
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
        pkgs.librelane
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
        # riscv64 Linux cross toolchain — Gate 2: cross-build borgvk for the
        # future RV64 Hutt Linux target.  Confirms the driver is RV64 + soft-float
        # (lp64) clean.  Buildroot will own the final rootfs ABI; this just gates
        # the code.  ($CROSS64 below names the binutils/gcc prefix.)
        pkgs.pkgsCross.riscv64.buildPackages.gcc
        pkgs.pkgsCross.riscv64.buildPackages.binutils
        pythonEnv
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
    # for building docs/poster.  Use `nix develop .#poster --command make -C docs/poster`.
    # Kept separate so CI (which uses the default shell) never fetches texlive.
    poster = pkgs.mkShell {
      inputsFrom = [ self.devShells.${system}.default ];
      nativeBuildInputs = [ borgTexlive ];
    };
    };

    formatter.${system} = alejandra.defaultPackage.${system};
  };
}
