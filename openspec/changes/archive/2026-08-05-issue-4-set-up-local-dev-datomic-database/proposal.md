## Why

Developers starting work on infratomic need a local Datomic database to
build and test app code against, without depending on shared/remote
infrastructure or manual per-machine setup (issue #4).

## What Changes

- Add a minimal Clojure project (`deps.edn`) with `com.datomic/local`
  v1.0.301 as a dependency, plus a `:test` alias wired to
  `cognitect-labs/test-runner`.
- Add code to configure a repo-local, gitignored dev-local storage
  directory and system name, create a local database, and get a
  connection (`datomic.client.api`).
- Add an automated test that connects, creates a DB, transacts a minimal
  inline fixture schema plus a sample fact, queries it back, and asserts
  the expected result — runnable via `clj -M:test` with no setup beyond
  clone + run.
- **BREAKING**: amend `flake.nix` and the `nix-dev-shell` spec to add a
  JDK 17+ package and the Clojure CLI (`clojure`) to the default dev
  shell, superseding the existing "no language-specific toolchains"
  requirement from issue #1 — a JVM + Clojure CLI on `PATH` is required
  for the "clone + run" verification bar to hold.
- Document bring-up and verification in `README.md`, following the
  existing LocalStack/Terraform section's structure.

## Capabilities

### New Capabilities
- `local-datomic-database`: provides a local, file-backed Datomic
  database (via `com.datomic/local`) that developers can connect to,
  create, transact against, and query, with an automated verification
  test and no setup beyond clone + run.

### Modified Capabilities
- `nix-dev-shell`: the default dev shell must additionally provide a
  JDK 17+ runtime and the Clojure CLI on `PATH`, replacing the existing
  "no language-specific toolchains" requirement with one permitting
  exactly these two additions.

## Impact

- New files: `deps.edn`, a namespace providing the dev-local
  client/connection helpers, and a `clojure.test` verification test.
- Modified files: `flake.nix` (add JDK 17+ and Clojure CLI packages),
  `.gitignore` (ignore the repo-local storage dir and Clojure build
  artifacts such as `.cpcache/`), `README.md` (new section).
- No app schema, no shared/remote Datomic storage (Cloud, transactor,
  license setup), and no CI workflow are introduced.
