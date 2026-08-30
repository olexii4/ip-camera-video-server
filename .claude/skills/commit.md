---
description: Create a commit following project conventions (conventional commits + permitted trailers only)
---

# Commit Skill

Always follow `.claude/rules/development-conventions.md` when committing.

## Template

```bash
git commit -m "$(cat <<'EOF'
type(scope): short description ≤ 50 chars

Optional body explaining why, not what.

Assisted-by: AI Assistant
Signed-off-by: Jane Developer <dev@example.com>
EOF
)"
```

## Rules

- Subject ≤ 50 chars
- Types: `feat`, `fix`, `chore`, `refactor`, `test`, `docs`
- Only `Assisted-by` and `Signed-off-by` trailers — no `Co-Authored-By`
- Stage specific files; avoid `git add -A` to prevent accidental inclusion of secrets or generated files
- Never commit `openspec/docs/*.md` unless explicitly requested
