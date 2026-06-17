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
  in {
    devShells.${system}.default = pkgs.mkShell {
      # Use nativeBuildInputs for tools that provide executables
      nativeBuildInputs = [
        pkgs.bash-completion
        pkgs.bear
        pkgs.bitwuzla
        pkgs.bzip2
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
        pkgs.texlive.combined.scheme-full
        pkgs.tio
        pkgs.typst
        pkgs.verilator
        pkgs.which
        pkgs.yosys
        pkgs.z3
        pkgs.pkgsCross.riscv32-embedded.buildPackages.gcc
        pkgs.pkgsCross.riscv32-embedded.buildPackages.binutils
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
      '';
    };

    formatter.${system} = alejandra.defaultPackage.${system};
  };
}
