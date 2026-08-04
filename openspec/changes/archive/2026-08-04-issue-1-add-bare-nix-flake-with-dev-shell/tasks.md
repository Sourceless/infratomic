## 1. Flake

- [x] 1.1 Create `flake.nix` at repo root with a short one-line `description`, `inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable"`, and a single `devShells.x86_64-linux.default` output built with `pkgs.mkShell { packages = [ pkgs.git pkgs.nix ]; }`
- [x] 1.2 Generate and commit `flake.lock` (e.g. via `nix flake lock` or letting `nix flake check` generate it)

## 2. Repo hygiene

- [x] 2.1 Add `.gitignore` at repo root covering `.direnv/` and `result`/`result-*`

## 3. Verification

- [x] 3.1 Run `nix flake show` from repo root; confirm it succeeds and lists only `devShells.x86_64-linux.default`
- [x] 3.2 Run `nix flake check` from repo root; confirm it succeeds with no errors
- [x] 3.3 Run `nix develop` from repo root and, inside the shell, confirm `git --version` and `nix --version` both succeed
