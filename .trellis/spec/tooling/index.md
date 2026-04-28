# Tooling Guidelines

> Project-local automation and developer tooling contracts.

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Beads Task Backend](./beads-task-backend.md) | Beads-first Trellis task lifecycle, loader, and hook contracts | Active |

---

## How to Use These Guidelines

Use these files for local automation that supports development in this
repository but does not affect SDK runtime behavior.

Codex lifecycle hooks in `.codex/hooks.json` are limited to Trellis context
injection. Do not add a turn-completion notification hook unless a new task
explicitly reintroduces that behavior.
