{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/96c7c82ade771bd1efc43a84c18c0747755140a7";
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

    # cocotb does not support Python 3.14 yet: its tests run on 3.13 with their own PYTHONPATH.
    cocotbForTests = pkgs.python313.withPackages (p: [p.cocotb p.numpy p.riscv-model]);

    pythonEnv = pkgs.python3.withPackages (p: [
      p.cairosvg
      p.chevron
      p.gdstk
      p.gitpython
      p.graphviz # gen_hw_diagram.py
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
      p.mako # Mesa build
      p.pyyaml # Mesa build
      p.pikepdf # docs/talk: scripts/add_video_annotation.py
    ]);

    # TeX Live for docs/poster and docs/talk only, kept out of the default shell (CI).
    borgTexlive = pkgs.texlive.combine {
      inherit
        (pkgs.texlive)
        scheme-small
        latexmk
        biber
        biblatex
        acmart
        tikzposter
        beamer
        qrcode
        microtype
        enumitem
        booktabs
        pgf
        xcolor
        lm
        ;
    };

    opensbiSrc = pkgs.opensbi.src;

    # Newer than nixpkgs' linux: the Rust DRM driver needs a kernel that supports the current rustc.
    linuxSrc = let
      tarball = pkgs.fetchurl {
        url = "https://cdn.kernel.org/pub/linux/kernel/v7.x/linux-7.2.9.tar.xz";
        hash = "sha256-tMXfvlGjZKbH8DhpIA+IyOH3dANTkAXxS3/GvJG42Lo=";
      };
    in
      pkgs.runCommand "linux-7.2.9-src" {} ''
        mkdir $out
        tar -xJf ${tarball} -C $out --strip-components=1
      '';

  in {
    # QEMU with the Borg bridge device (software/linux/tests/qemu), riscv64 system emulation only.
    packages.${system}.qemu-borg =
      (pkgs.qemu.override {
        hostCpuTargets = ["riscv64-softmmu"];
        guestAgentSupport = false;
      }).overrideAttrs (old: {
        patches = (old.patches or []) ++ [./software/linux/tests/qemu/borg.patch];
        dontCheckForBrokenSymlinks = true;
      });

    devShells.${system} = {
      default = pkgs.mkShell {
        # The kernel does its own hardening; the wrapper's -fzero-call-used-regs is rejected for riscv64.
        hardeningDisable = ["all"];
        nativeBuildInputs = [
          pkgs.bash-completion
          pkgs.bc # Linux kernel build scripts
          pkgs.bear
          pkgs.bitwuzla
          pkgs.bzip2
          pkgs.dtc # borg.dts -> borg.dtb
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
          pkgs.graphviz
          pkgs.ghostscript
          pkgs.inkscape
          pkgs.iverilog # cocotb's simulator (test/soc)
          pkgs.icestorm
          pkgs.jdk21
          pkgs.klayout
          pkgs.librelane
          pkgs.magic-vlsi
          pkgs.metals
          pkgs.mill
          pkgs.meson # Mesa
          pkgs.ninja # Mesa
          pkgs.bison # Mesa
          pkgs.flex # Mesa
          pkgs.rustc
          pkgs.cargo
          pkgs.rustfmt
          pkgs.rust-bindgen
          pkgs.rust-cbindgen
          # Kernel LLVM=1 build with Rust: clang/lld/llvm must match rustc's LLVM and bindgen's libclang.
          pkgs.llvmPackages_21.clang-unwrapped
          pkgs.llvmPackages_21.lld
          pkgs.llvmPackages_21.llvm
          pkgs.rustPlatform.rustLibSrc # the Rust kernel build (borg DRM driver)
          pkgs.vulkan-tools # vulkaninfo
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
          pkgs.pkgsCross.riscv64-embedded.buildPackages.gcc # firmware (software/borg, software/hutt)
          pkgs.pkgsCross.riscv64-embedded.buildPackages.binutils
          pkgs.pkgsCross.riscv64.buildPackages.gcc # Linux userspace and borgvk for RV64 ($CROSS64)
          pkgs.pkgsCross.riscv64.buildPackages.binutils
          pythonEnv
          cocotbForTests
          pkgs.ffmpeg # docs/talk: make video
        ];

        # Mesa's borgvk; X11/xcb and Wayland host the unmodified Vulkan-Tools cube.c.
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
          export COCOTB_PYTHONPATH="${cocotbForTests}/${pkgs.python313.sitePackages}"
          export OPENSBI_SRC="${opensbiSrc}"
          export LINUX_SRC="${linuxSrc}"
          export RUST_LIB_SRC="${pkgs.rustPlatform.rustLibSrc}"
          export KERNEL_BINDGEN="${pkgs.rust-bindgen-unwrapped}/bin/bindgen"
          export CROSS64=riscv64-unknown-linux-gnu

          # Pure mode: Mill and Java need a HOME for their caches.
          if [ -z "$HOME" ] || [ "$HOME" = "/" ]; then
            export HOME=$(pwd)/.nix-home
            mkdir -p $HOME
            echo "Notice: Pure mode detected. Using local $HOME for caches."
          fi
          export JAVA_HOME=${pkgs.jdk21}
          export LIBCLANG_PATH="${pkgs.llvmPackages.libclang.lib}/lib"

          echo "Entering $GONSOLO_PROJECT development shell..."

          # Named interpreters: a bare python3 on PATH can be any input's own.
          #  python3-borg-rdl:  pythonEnv, for `make rdl`.
          #  python3-librelane: pythonEnv plus librelane's own PYTHONPATH, for padring.py.
          mkdir -p $HOME/bin
          ln -sf ${pkgs.yosys}/bin/yosys $HOME/bin/yowasp-yosys
          ln -sf ${pythonEnv}/bin/python3 $HOME/bin/python3-borg-rdl
          cat > $HOME/bin/python3-librelane << 'WRAPPER_EOF'
          #!${pkgs.bash}/bin/bash
          export PYTHONPATH="$(source <(head -n -1 ${pkgs.librelane}/bin/librelane); echo "$PYTHONPATH")"
          exec ${pythonEnv}/bin/python3 "$@"
          WRAPPER_EOF
          chmod +x $HOME/bin/python3-librelane
          export PATH="$HOME/bin:$PATH"

          # Nix keeps wayland.xml in wayland-scanner; point wayland-client's pkgdatadir there.
          _wl_pc=$(mktemp -d)
          sed 's|pkgdatadir=.*|pkgdatadir=${pkgs.wayland-scanner}/share/wayland|' \
            ${pkgs.wayland.dev}/lib/pkgconfig/wayland-client.pc > "$_wl_pc/wayland-client.pc"
          export PKG_CONFIG_PATH="$_wl_pc''${PKG_CONFIG_PATH:+:''${PKG_CONFIG_PATH}}"

          # Meson cross file for borgvk on riscv64 Linux.
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

      # The default shell plus TeX Live: nix develop .#poster --command make -C docs/poster
      poster = pkgs.mkShell {
        inputsFrom = [self.devShells.${system}.default];
        nativeBuildInputs = [borgTexlive];
      };
    };

    formatter.${system} = alejandra.defaultPackage.${system};
  };
}
