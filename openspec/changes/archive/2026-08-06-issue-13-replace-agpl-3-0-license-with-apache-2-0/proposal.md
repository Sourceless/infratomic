## Why

The GNU Affero General Public License, version 3, was the wrong license choice when added in #9/#12 — it does not reflect the project's intended terms. Issue #13 asks for the repo to be relicensed under Apache License 2.0.

## What Changes

- **BREAKING**: Replace the root `LICENSE` file's contents with the stock, unmodified Apache License 2.0 text (from https://www.apache.org/licenses/LICENSE-2.0.txt), with only the copyright line in the trailing "APPENDIX: How to apply the Apache License to your work" boilerplate filled in as `Copyright 2026 Laurence Pakenham-Smith`. The 202 operative license sections stay byte-for-byte stock/unmodified.
- Update `README.md`'s `## License` section to read "infratomic is licensed under the [Apache License 2.0](LICENSE)." instead of referencing the GNU Affero General Public License, version 3.
- Scrub the GNU Affero General Public License, version 3, references from the archived OpenSpec docs for the original license change (`openspec/changes/archive/*issue-9*/proposal.md` and `tasks.md`) so no file in the repo's tracked content mentions that license by name or acronym.

Out of scope: SPDX license headers in individual source files, CI checks enforcing license presence (unchanged from the original license addition's scope).

## Capabilities

### New Capabilities
(none - this change swaps a licensing document and README/archive text, not application behavior)

### Modified Capabilities
(none - no spec-level behavior changes; this is a pure docs/legal change, so `skip_specs: true` is set in `.openspec.yaml`)

## Impact

- Modified file: `LICENSE` (root of repo) — full text replacement, from the GNU Affero General Public License, version 3, to Apache-2.0.
- Modified file: `README.md` — `## License` section wording updated.
- Modified files: `openspec/changes/archive/*issue-9*/proposal.md` and `tasks.md` — references to the prior license scrubbed/updated.
- No application code, dependencies, or runtime behavior affected.
- GitHub's license detector is expected to flip from the GNU Affero General Public License, version 3, to Apache-2.0 once `LICENSE` is stock Apache-2.0 text.
