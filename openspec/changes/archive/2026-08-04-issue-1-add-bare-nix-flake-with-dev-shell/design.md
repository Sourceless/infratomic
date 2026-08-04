## Context

See proposal.md - Why. This introduces the repo's first external dependency (the `nixpkgs` flake input) and its first pinned-lockfile artifact. The sandbox this was researched in runs Determinate Nix 3.17.0 (Nix 2.33.3) on x86_64-linux with flakes already enabled; no `--extra-experimental-features` flag is needed. A throwaway spike flake confirmed `nix flake show`, `nix flake check`, and `nix develop` all succeed with a minimal `devShells.x86_64-linux.default` built from `pkgs.mkShell { packages = [ pkgs.git pkgs.nix ]; }`.

## Goals / Non-Goals

**Goals:**
- Minimal, valid flake exposing exactly one output: `devShells.x86_64-linux.default` with `git` and `nix`.
- Reproducible across machines via a committed `flake.lock`.

**Non-Goals:**
- No `.github/workflows` or other CI wiring.
- No language-specific packages or toolchains.
- No darwin/aarch64 (or any other) system support.
- No shell hook, welcome message, formatter/linter setup, or suppression of Determinate-vs-vanilla-Nix config warnings.

## Decisions

- **Nixpkgs input pinned to `nixos-unstable`** (`inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable"`). Alternative considered: a stable channel (e.g. `nixos-24.11`) for tighter version control. Rejected because `flake.lock` is committed regardless, so reproducibility is guaranteed either way; unstable gets a `nix` package version closer to the host's Determinate Nix, and only two low-churn packages (`git`, `nix`) are pulled from it, so drift risk is low. This decision was made in the issue's alignment stage, not re-derived here.
- **Commit `flake.lock`.** Alternative: leave it untracked and let each `nix develop`/`nix flake check` invocation re-resolve `nixos-unstable` HEAD. Rejected because that would let `git`/`nix` versions silently drift between contributors and CI runs; committing pins an exact nixpkgs revision. Also decided at alignment.
- **Single `devShells.x86_64-linux.default` output, no `flake-utils` or multi-system helper.** Only one target system is in scope (`x86_64-linux`), so a helper library that generates per-system outputs would add an unused abstraction. Hand-writing the single system key keeps the flake bare, per the issue's explicit framing.
- **`.gitignore` covers `.direnv/` and `result`/`result-*` only.** These are the standard local artifacts Nix/direnv leave behind (`result*` from `nix build`, `.direnv/` from direnv's cache) even though this change doesn't wire up direnv or a `nix build` output — cheap insurance against contributors accidentally committing them later. Alignment decision, not re-derived here.
- **Accept Determinate-vs-vanilla-Nix config warnings as-is.** `pkgs.nix` (vanilla Nix) inside the shell prints non-blocking `unknown setting 'eval-cores'` / `'lazy-trees'` warnings on stderr on Determinate Nix hosts, because those are Determinate-only settings from the host's `/etc/nix/nix.conf` that vanilla Nix doesn't recognize. These warnings don't affect exit codes or any acceptance criterion. No mitigation (e.g. a wrapped `nix` or a filtered config) is added, since that would add complexity to fix a cosmetic issue. Alignment decision.

## Risks / Trade-offs

- [`nixos-unstable` can occasionally have a broken/unbuildable revision for `git` or `nix`] → Mitigated by the committed `flake.lock`: once resolved and committed, contributors always get the same working revision until someone deliberately runs `nix flake update`.
- [Determinate Nix stderr warnings inside the shell could be mistaken for real errors by contributors] → Accepted per alignment; revisit only if it proves to actually bother contributors (see proposal's alignment notes).
