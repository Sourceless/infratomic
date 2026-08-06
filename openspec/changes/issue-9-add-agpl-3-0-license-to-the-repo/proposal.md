## Why

The infratomic repository currently has no `LICENSE` file and GitHub reports no detected license (`gh repo view --json licenseInfo` returns `null`). Without an explicit license, the project has no clear legal terms and cannot be run as unlicensed hosted SaaS without contributing back. Adding AGPL-3.0 now, while the repo is small, avoids relicensing friction later.

## What Changes

- Add a root-level `LICENSE` file containing the full, unmodified GNU AGPLv3 text copied verbatim from https://www.gnu.org/licenses/agpl-3.0.txt, with only the bracketed placeholders in the trailing "How to Apply These Terms to Your New Programs" section filled in (program name, year, author, one-line description), and the "or (at your option) any later version" clause removed from the notice text so the project is AGPL-3.0-only rather than AGPL-3.0-or-later.
- Add a `## License` section as the final section of `README.md` stating the project is licensed under AGPL-3.0 and linking to `LICENSE`.

Out of scope (per issue #9): SPDX license headers in individual source files, and CI checks enforcing license presence.

## Capabilities

### New Capabilities
(none - this change adds a licensing document and README section, not application behavior)

### Modified Capabilities
(none - no spec-level behavior changes; this is a pure docs/legal addition, so `skip_specs: true` is set in `.openspec.yaml`)

## Impact

- New file: `LICENSE` (root of repo).
- Modified file: `README.md` (new `## License` section appended at the end).
- No application code, dependencies, or runtime behavior affected.
