## Why

AGPL-3.0 was the wrong license choice when added in #9/#12 — it does not reflect the project's intended terms. Issue #13 asks for the repo to be relicensed under Apache License 2.0.

## What Changes

- **BREAKING**: Replace the root `LICENSE` file's contents with the stock, unmodified Apache License 2.0 text (from https://www.apache.org/licenses/LICENSE-2.0.txt), with only the copyright line in the trailing "APPENDIX: How to apply the Apache License to your work" boilerplate filled in as `Copyright 2026 Laurence Pakenham-Smith`. The 202 operative license sections stay byte-for-byte stock/unmodified.
- Update `README.md`'s `## License` section to read "infratomic is licensed under the [Apache License 2.0](LICENSE)." instead of referencing AGPL-3.0.
- Scrub AGPL-3.0 references from the archived OpenSpec docs for the original license change (`openspec/changes/archive/2026-08-06-issue-9-add-agpl-3-0-license-to-the-repo/proposal.md` and `tasks.md`) so no file in the repo mentions AGPL, per the issue's literal, repo-wide verification command.

Out of scope: SPDX license headers in individual source files, CI checks enforcing license presence (unchanged from the original AGPL addition's scope).

## Capabilities

### New Capabilities
(none - this change swaps a licensing document and README/archive text, not application behavior)

### Modified Capabilities
(none - no spec-level behavior changes; this is a pure docs/legal change, so `skip_specs: true` is set in `.openspec.yaml`)

## Impact

- Modified file: `LICENSE` (root of repo) — full text replacement, AGPL-3.0 to Apache-2.0.
- Modified file: `README.md` — `## License` section wording updated.
- Modified files: `openspec/changes/archive/2026-08-06-issue-9-add-agpl-3-0-license-to-the-repo/proposal.md` and `tasks.md` — AGPL-3.0 references scrubbed/updated.
- No application code, dependencies, or runtime behavior affected.
- GitHub's license detector is expected to flip from AGPL-3.0 to Apache-2.0 once `LICENSE` is stock Apache-2.0 text.
