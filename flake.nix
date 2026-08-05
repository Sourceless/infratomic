{
  description = "Dev shell providing git, nix, and the Clojure toolchain for the State Backend";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system; };
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [ pkgs.git pkgs.nix pkgs.jdk17 pkgs.clojure ];
      };
    };
}
