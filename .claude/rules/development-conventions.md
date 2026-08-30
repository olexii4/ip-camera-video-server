---
description: Commit message and trailer conventions for this project
---

# Development Conventions

## Commit Trailers

Only these trailers are permitted:

```
Assisted-by: {AGENT_NAME}
Signed-off-by: {AUTHOR_NAME} <{AUTHOR_EMAIL}>
```

- `{AGENT_NAME}` — specific agent name, e.g. `AI Assistant`
- `{AUTHOR_NAME}` / `{AUTHOR_EMAIL}` — from `git config user.name` / `git config user.email`

**Do NOT add:** `Made-with`, `Co-authored-by`, `Co-Authored-By`, or duplicate trailers.
**Do NOT add** AI explanation comments inside source code.
**On amend:** always pass the full message with `-m "..."` so trailers are not stacked.

## Commit Message Format

- Subject line ≤ 50 chars
- Conventional commits: `type(scope): short description`
- Common types: `fix`, `feat`, `chore`, `refactor`, `test`, `docs`

### Example

```
fix(ui): prevent error message overflowing the widget

Assisted-by: AI Assistant
Signed-off-by: Jane Developer <dev@example.com>
```

## Code Style

- Kotlin only — no Java source files
- No AI explanation comments inside source code
- One responsibility per file
- Coroutines + Flow for all async — no RxJava, no AsyncTask
