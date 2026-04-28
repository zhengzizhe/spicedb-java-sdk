# Tooling Guidelines

> Project-local automation and developer tooling contracts.

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| _None currently active_ | Project-local tooling has no extra guideline files at this time. | No completion notification hook is configured |

---

## How to Use These Guidelines

Use these files for local automation that supports development in this
repository but does not affect SDK runtime behavior.

Codex lifecycle hooks in `.codex/hooks.json` are limited to Trellis context
injection. Do not add a turn-completion notification hook unless a new task
explicitly reintroduces that behavior.
