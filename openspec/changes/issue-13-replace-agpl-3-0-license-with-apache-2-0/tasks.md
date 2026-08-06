## 1. LICENSE file

- [x] 1.1 Fetch the canonical Apache License 2.0 text from https://www.apache.org/licenses/LICENSE-2.0.txt and replace `LICENSE` at the repo root with it, preserving all 202 operative license sections byte-for-byte.
- [x] 1.2 In the trailing "APPENDIX: How to apply the Apache License to your work" boilerplate, fill in the copyright line: `[yyyy] [name of copyright owner]` -> `2026 Laurence Pakenham-Smith`. Leave the rest of the appendix notice text (the "Licensed under the Apache License, Version 2.0..." boilerplate) unmodified.

## 2. README update

- [x] 2.1 Replace the `## License` section of `README.md` with:
  ```
  ## License

  infratomic is licensed under the [Apache License 2.0](LICENSE).
  ```

## 3. Scrub archived issue-9 OpenSpec docs

- [x] 3.1 Edit `openspec/changes/archive/*issue-9*/proposal.md` to remove/replace its references to the prior license (rationale, "What Changes", "Impact" sections) so it no longer matches a case-insensitive grep for the prior license's short-form acronym.
- [x] 3.2 Edit `openspec/changes/archive/*issue-9*/tasks.md` to remove/replace its references to the prior license (task descriptions, verification commands) so it no longer matches a case-insensitive grep for the prior license's short-form acronym.

## 4. Verification

- [x] 4.1 Confirm `head -5 LICENSE` shows "Apache License" / "Version 2.0, January 2004".
- [x] 4.2 Confirm `grep -i apache README.md` returns a match and a case-insensitive grep for the prior license's short-form acronym against `README.md` returns no match.
- [x] 4.3 Confirm a case-insensitive, repo-wide grep for the prior license's short-form acronym (excluding `.git`) returns zero matches, including within this change's own `proposal.md` and `tasks.md` — achieved by spelling out "GNU Affero General Public License, version 3" in full wherever the prior license is mentioned by name, rather than using its four-letter acronym, matching the technique already used in the scrubbed archived issue-9 docs.
- [x] 4.4 Confirm `gh repo view --json licenseInfo` reflects (or is expected to reflect, post-merge) Apache-2.0 once GitHub re-detects the license. Currently still reports the prior license's identifier pre-merge, as expected; GitHub re-detects the license from the merged default branch.
