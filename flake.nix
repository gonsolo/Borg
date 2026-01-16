{
  inputs = {
    #nixpkgs.url = "github:NixOS/nixpkgs/240062ab";
    #nixpkgs.url = "github:gonsolo/nixpkgs/librelane";
    nixpkgs.url = "github:gonsolo/nixpkgs/riscv-model+librelane";
    alejandra.url = "github:kamadorueda/alejandra/4.0.0";
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
      p.cocotb
      p.chevron
      p.gitpython
      p.klayout
      p.matplotlib
      p.mistune
      p.pip
      p.pyaml
      p.pytest
      p.requests
      p.riscv-model

      pkgs.python3Packages.gdstk
    ]);
  in {
    devShells.${system}.default = pkgs.mkShell {
      buildInputs = [
        pkgs.bash-completion
        pkgs.bzip2
        pkgs.cmake
        pkgs.gnumake
        pkgs.iverilog
        pkgs.jdk25
        pkgs.librelane
        pkgs.mill
        pythonEnv
      ];

      shellHook = ''
        export GONSOLO_PROJECT="borg_peripheral"
        echo "Entering $GONSOLO_PROJECT development shell..."
      '';
    };

    formatter.${system} = alejandra.defaultPackage.${system};
  };
}
