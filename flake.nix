{
  inputs = {
    #nixpkgs.url = "github:gonsolo/nixpkgs/librelane";
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
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
        pkgs.iverilog
        pkgs.icestorm
        pkgs.jdk21
        pkgs.klayout
        pkgs.librelane
        pkgs.magic-vlsi
        pkgs.metals
        pkgs.mill
        pkgs.mpremote
        pkgs.netgen-vlsi
        pkgs.nextpnr
        pkgs.openroad
        pkgs.pandoc
        pkgs.pkg-config
        pkgs.scalafmt
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

        echo "Entering $GONSOLO_PROJECT development shell..."

        # Create a bin directory in our local nix-home
        mkdir -p $HOME/bin

        # Link native yosys to the name the python script is looking for
        ln -sf ${pkgs.yosys}/bin/yosys $HOME/bin/yowasp-yosys

        # Ensure our shim is at the front of the PATH
        export PATH="$HOME/bin:$PATH"
      '';
    };

    formatter.${system} = alejandra.defaultPackage.${system};
  };
}
