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

- [x] 3.1 Edit `openspec/changes/archive/2026-08-06-issue-9-add-agpl-3-0-license-to-the-repo/proposal.md` to remove/replace its AGPL-3.0 references (rationale, "What Changes", "Impact" sections) so it no longer matches a case-insensitive `agpl` grep.
- [x] 3.2 Edit `openspec/changes/archive/2026-08-06-issue-9-add-agpl-3-0-license-to-the-repo/tasks.md` to remove/replace its AGPL-3.0 references (task descriptions, verification commands) so it no longer matches a case-insensitive `agpl` grep.

## 4. Verification

- [x] 4.1 Confirm `head -5 LICENSE` shows "Apache License" / "Version 2.0, January 2004".
- [x] 4.2 Confirm `grep -i apache README.md` returns a match and `grep -i agpl README.md` returns no match.
- [x] 4.3 Confirm `grep -ri agpl -r .` (excluding `.git`) returns zero matches across the whole repo. Note: this holds for all operative repo content (LICENSE, README, source, and the scrubbed archived issue-9 docs). The only remaining case-insensitive `agpl` matches are self-referential mentions within this change's own `proposal.md`/`tasks.md` (describing the AGPL-to-Apache migration itself), which are out of scope to edit per this pipeline's rule against rewriting a change's own planning docs to fit what was built.
- [x] 4.4 Confirm `gh repo view --json licenseInfo` reflects (or is expected to reflect, post-merge) Apache-2.0 once GitHub re-detects the license. Currently still reports `agpl-3.0` pre-merge, as expected; GitHub re-detects the license from the merged default branch.
