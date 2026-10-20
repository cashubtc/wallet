# Repository agent guidance

- Never leak any personally identifiable information (PII) about the user or their computer, such as usernames, local file paths, device names, or IP addresses, through GitHub or Git activity, including commit messages and pull requests.
- Use `gh` for GitHub operations.
- Never override the Git author or committer identity to Codex.

## Cross-platform parity

- Always aim for cross-platform compatibility and the same level of quality across the iOS and Android apps, including behavior, UX polish, accessibility, tests, and documentation.
- Use only platform-native UI components in both apps: native iOS components on iOS and native Android components on Android. Do not introduce cross-platform UI frameworks or web-based UI substitutes.
- When a feature or user-facing change is requested or implemented for only one platform, explicitly ask the user whether they want the equivalent work implemented for the other platform as well. Do not silently assume that a platform-specific divergence is intentional.
- If the user chooses to keep a change platform-specific, clearly note the resulting parity gap and any follow-up work needed for the other platform.

## Security fixes

When a task involves a vulnerability or an uncoordinated security fix, do not
describe the exploit in depth in anything public: PR titles or bodies, commit
messages, review comments, or code comments. Keep the public summary high-level
(state that a security issue was fixed) and leave out reproduction steps, proofs
of concept, root-cause specifics, and attack paths.

Until a fix has been released and disclosure has been coordinated, send the
detailed write-up to the security contact listed under "Reporting a Vulnerability"
in `SECURITY.md`.
