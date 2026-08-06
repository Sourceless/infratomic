## 1. LICENSE file

- [x] 1.1 Fetch the canonical text of the GNU Affero General Public License, version 3, from the Free Software Foundation's website and save it as `LICENSE` at the repo root, preserving all 17 operative license sections byte-for-byte.
- [x] 1.2 In the trailing "How to Apply These Terms to Your New Programs" section of `LICENSE`, fill in the placeholders: `<program>` → `infratomic`, `<year>` → `2026`, `<name of author>` → `Laurence Pakenham-Smith`, and the "one line to give the program's name and a brief idea of what it does" line → `infratomic, a state backend and logic system for infrastructure as code`.
- [x] 1.3 In that same notice block, remove the "or (at your option) any later version" clause so the text reads "...under the terms of the GNU Affero General Public License, version 3, as published by the Free Software Foundation." (version 3 only, not "or later").

## 2. README update

- [x] 2.1 Add a `## License` section as the final section of `README.md` reading:
  ```
  ## License

  infratomic is licensed under the [GNU Affero General Public License v3.0](LICENSE).
  ```

## 3. Verification

- [x] 3.1 Confirm `cat LICENSE | head -5` shows "GNU AFFERO GENERAL PUBLIC LICENSE Version 3".
- [x] 3.2 Confirm `grep -i affero README.md` returns a match.

## Note (2026-08-06, issue #13)

The license this change added was replaced with the Apache License 2.0. See the `issue-13` archived change for that follow-up.
