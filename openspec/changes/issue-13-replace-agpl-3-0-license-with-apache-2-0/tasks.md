## 1. LICENSE file

- [ ] 1.1 Fetch the canonical Apache License 2.0 text from https://www.apache.org/licenses/LICENSE-2.0.txt and replace `LICENSE` at the repo root with it, preserving all 202 operative license sections byte-for-byte.
- [ ] 1.2 In the trailing "APPENDIX: How to apply the Apache License to your work" boilerplate, fill in the copyright line: `[yyyy] [name of copyright owner]` -> `2026 Laurence Pakenham-Smith`. Leave the rest of the appendix notice text (the "Licensed under the Apache License, Version 2.0..." boilerplate) unmodified.

## 2. README update

- [ ] 2.1 Replace the `## License` section of `README.md` with:
  ```
  ## License

  infratomic is licensed under the [Apache License 2.0](LICENSE).
  ```

## 3. Scrub archived issue-9 OpenSpec docs

- [ ] 3.1 Edit `openspec/changes/archive/2026-08-06-issue-9-add-agpl-3-0-license-to-the-repo/proposal.md` to remove/replace its AGPL-3.0 references (rationale, "What Changes", "Impact" sections) so it no longer matches a case-insensitive `agpl` grep.
- [ ] 3.2 Edit `openspec/changes/archive/2026-08-06-issue-9-add-agpl-3-0-license-to-the-repo/tasks.md` to remove/replace its AGPL-3.0 references (task descriptions, verification commands) so it no longer matches a case-insensitive `agpl` grep.

## 4. Verification

- [ ] 4.1 Confirm `head -5 LICENSE` shows "Apache License" / "Version 2.0, January 2004".
- [ ] 4.2 Confirm `grep -i apache README.md` returns a match and `grep -i agpl README.md` returns no match.
- [ ] 4.3 Confirm `grep -ri agpl -r .` (excluding `.git`) returns zero matches across the whole repo.
- [ ] 4.4 Confirm `gh repo view --json licenseInfo` reflects (or is expected to reflect, post-merge) Apache-2.0 once GitHub re-detects the license.
