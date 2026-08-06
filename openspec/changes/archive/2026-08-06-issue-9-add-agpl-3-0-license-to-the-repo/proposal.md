## Why

The infratomic repository currently has no `LICENSE` file and GitHub reports no detected license (`gh repo view --json licenseInfo` returns `null`). Without an explicit license, the project has no clear legal terms and cannot be run as unlicensed hosted SaaS without contributing back. Adding a copyleft network-server license now, while the repo is small, avoids relicensing friction later.

Note (2026-08-06, issue #13): the license chosen by this change was later replaced with the Apache License 2.0. See `openspec/changes/archive/*issue-13*` for that follow-up.

## What Changes

- Add a root-level `LICENSE` file containing the full, unmodified text of the GNU Affero General Public License, version 3, copied verbatim from the Free Software Foundation's published license text, with only the bracketed placeholders in the trailing "How to Apply These Terms to Your New Programs" section filled in (program name, year, author, one-line description), and the "or (at your option) any later version" clause removed from the notice text so the project is licensed under version 3 only, not version 3 or any later version.
- Add a `## License` section as the final section of `README.md` stating the project is licensed under that license and linking to `LICENSE`.

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
