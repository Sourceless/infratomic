## Context

See proposal.md - Why. Two constraints shape this design: (1) the repo
currently has no Clojure tooling, no `deps.edn`, and a dev shell that only
provides `git` + `nix` (`flake.nix`, `openspec/specs/nix-dev-shell/spec.md`);
(2) Datomic's historical `dev-local` artifact/auth flow is obsolete —
`com.datomic/local` v1.0.301 is the current free, Maven-Central-resolvable
replacement, requiring JDK 17+ (per research findings on issue #4).

## Goals / Non-Goals

**Goals:**
- Clone + run reproducibility: `nix develop` then `clj -M:test` works with
  no manual setup (no editing `~/.datomic/local.edn`, no credentials).
- A file-backed (not in-memory) local database, so it genuinely
  demonstrates "a local database I can connect to" per the user story.

**Non-Goals:**
- Any real domain schema, schema versioning, or migration story (out of
  scope per the issue and alignment).
- Any shared/remote Datomic storage (Cloud, transactor, license setup).
- CI integration - verification is manual (`clj -M:test`), matching the
  `nix-dev-shell` capability's existing "no CI in scope" boundary, which
  this change narrows but does not remove.

## Decisions

### Storage directory and system name
Use a repo-local, gitignored directory - `.datomic/storage` - as the
`:storage-dir`, and a fixed system name of `dev`, both passed explicitly
in the `d/client` config map in code (not written to
`~/.datomic/local.edn`). This is more reproducible for "clone + run" than
a dotfile outside the repo, and persists data on disk (unlike `:mem`),
matching the user story's "local database I can connect to."

Alternative considered: `:storage-dir :mem`. Rejected because it doesn't
persist and is a weaker demonstration of a "local database," per the
alignment decision.

### Connection helper shape
A single namespace (e.g. `infratomic.datomic`) exposes a function that
builds the client config (storage-dir, system name), ensures the database
exists (`d/create-database` is idempotent - safe to call every time), and
returns a connection. The verification test calls this function directly
rather than duplicating client setup.

### Fixture schema placement
The verification test transacts its own minimal schema (a single
`:sample/name` string attribute) inline, immediately before transacting
the sample fact. No separate schema namespace or file is created, keeping
"app schema design" genuinely out of scope while still satisfying
Datomic's schema-on-write requirement.

### Test runner
`cognitect-labs/test-runner` is added as a git dependency under a `:test`
alias in `deps.edn`, invoked as `clj -M:test -m cognitect.test-runner`.
This gives the conventional `clj -M:test` entry point mentioned in the
issue's "How to verify," and is the standard, low-ceremony choice for a
first Clojure test suite in a repo that's likely to grow more tests.

Alternative considered: a bare `-X`/`-M` invocation of `clojure.test`
directly (e.g. `clj -X clojure.test/run`). Rejected per alignment -
`cognitect-labs/test-runner` was explicitly chosen for future growth and
convention.

### Dev shell change
Add a JDK 17+ package (e.g. `pkgs.jdk17` or newer LTS available in
nixpkgs-unstable at implementation time) and `pkgs.clojure` to the
existing `devShells.x86_64-linux.default` package list in `flake.nix`,
alongside the existing `git` and `nix`. This directly amends the
`nix-dev-shell` capability's requirements (see specs delta) rather than
introducing a second, opt-in shell, per the alignment decision - a
separate shell would still fail the "no manual setup beyond clone + run"
bar for anyone who just runs `nix develop`.

## Risks / Trade-offs

- [Widening the dev shell's scope beyond "no language-specific
  toolchains"] → Mitigated by keeping the widened allow-list explicit and
  narrow (JDK 17+ and Clojure CLI only) in the amended `nix-dev-shell`
  spec, rather than opening the door to arbitrary language toolchains.
- [`d/create-database` being called on every connection-helper invocation]
  → This is idempotent per the Datomic client API and cheap for a local
  file-backed store, so no additional "does it exist" check is needed.
- [Repo-local storage dir accumulating stale local state across branches/
  runs] → Mitigated by gitignoring it; contributors can delete
  `.datomic/storage` freely to reset.
