# Contributing to 1Key

## Open source, not open contribution

Borrowed in spirit from SQLite: 1Key is open source so you can read, audit, and verify it. That is the point of a privacy tool. It is not a community project, and the maintainer is one person.

If you have come here looking for a side quest or a portfolio commit, please look elsewhere — there are larger projects that need help more.

## What's welcome

- Bug reports with reproduction steps and the affected version
- Security findings, responsibly disclosed first (open a private advisory or email the maintainer before filing a public issue)
- Pull requests that fix real bugs or close audit findings
- Documentation corrections — typos, broken links, factual errors
- Translations of user-facing strings, once a localisation framework lands

## What will likely be closed

- Large refactors without prior discussion
- Speculative features not on the roadmap
- "Vibe-coded" or AI-generated patches without clear reasoning in the PR description
- Cosmetic changes to working code (lint-driven nits, whitespace passes)
- Anything that adds a network dependency, telemetry, or analytics
- Anything that weakens the threat model documented in `README.md` and the white paper

## How to propose something larger

If in doubt, open an issue first and wait for a reply before writing code. The maintainer would rather close a one-line issue than close a thousand-line PR.

For security findings specifically, please disclose privately through GitHub's security advisory feature or via the contact in the repository. Public disclosure before a fix ships puts every existing 1Key user at risk.

## Code review expectations

PRs are reviewed at the maintainer's pace. There is no SLA. If a PR sits open for a while it usually means the maintainer is thinking about whether the change is the right shape for the project — not that it has been ignored.

Reviews focus on:

- Does this change preserve the local-only, no-network, no-telemetry properties?
- Does it fit the existing architecture (MVVM + clean-arch layers)?
- Are tests included for non-trivial changes?
- Does the diff respect the project's style — terse comments, descriptive names, no "vibe" abstractions?

## Licence

Contributions to this repository are licensed under the same GPL-3.0 that the project ships under (see [LICENSE](LICENSE)). By submitting a pull request you confirm you have the right to release the contributed code under that licence.
