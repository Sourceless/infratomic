## Why

Contributors setting up this repo today have no reproducible way to get a working toolchain. Adding a bare Nix flake with a dev shell lets anyone run `nix develop` and get `git` and `nix` on `PATH` without manually installing anything, closing out issue #1.

## What Changes

- Add `flake.nix` at repo root defining a single `devShells.x86_64-linux.default` output built with `pkgs.mkShell`, providing `git` and `nix` on `PATH`.
- Pin the `nixpkgs` input to `nixos-unstable`.
- Commit the generated `flake.lock` to pin the exact nixpkgs revision for reproducibility.
- Add a minimal `.gitignore` covering `.direnv/` and `result`/`result-*` (standard local Nix/direnv artifacts).
- No language-specific packages, no CI workflow, no darwin/aarch64 support, no shell hook/welcome message, no formatter/linter setup — deliberately bare, per the issue's acceptance criteria and out-of-scope notes.

## Capabilities

### New Capabilities
- `nix-dev-shell`: A `nix develop` shell, defined by a repo-root flake, that provides `git` and `nix` on `PATH` for `x86_64-linux`, with no other tooling.

### Modified Capabilities
(none)

## Impact

- New files at repo root: `flake.nix`, `flake.lock`, `.gitignore`.
- No changes to existing application code (repo currently has no application code).
- No CI wiring introduced (CI running `nix flake check` is explicitly out of scope, per the issue).
