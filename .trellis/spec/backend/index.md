# Backend Development Guidelines

> Best practices for backend development in this project.

---

## Overview

This directory contains backend guidelines extracted from the repository's
current Java SDK code, Gradle configuration, tests, README examples, and
optional Redisson/test-app modules.

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | Module organization and file layout | Filled from existing Java SDK code |
| [Database Guidelines](./database-guidelines.md) | SpiceDB/Redis persistence conventions | Filled from existing Java SDK code |
| [Error Handling](./error-handling.md) | Error types, handling strategies | Filled from existing Java SDK code |
| [Quality Guidelines](./quality-guidelines.md) | Code standards, forbidden patterns | Filled from existing Java SDK code |
| [Logging Guidelines](./logging-guidelines.md) | Structured logging, log levels | Filled from existing Java SDK code |

---

## How to Use These Guidelines

These files document conventions observed in the current Java SDK codebase.
When implementing future backend changes:

1. Load the guideline that matches the layer being changed.
2. Follow the cited package boundaries and helper APIs before adding new ones.
3. Prefer updating these files when real code establishes a new convention.
4. Keep examples tied to existing repository paths.

The goal is to help AI assistants and new team members match this SDK's actual
patterns instead of writing generic backend code.

---

**Language**: All documentation should be written in **English**.
