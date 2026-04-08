# Dead Code & Stale Content Cleanup — Spec

## Goal

Remove confirmed dead code, unused dependencies, and stale documentation from the SDK to reduce noise and prevent confusion.

## Scope

Precision cleanup only — no architectural changes, no SPI removal, no refactoring.

## Requirements

### req-1: Remove dead LogRedactionInterceptor

Delete `src/main/java/com/authx/sdk/builtin/LogRedactionInterceptor.java`.

**Evidence:** Zero imports, zero references across entire codebase. Comment claims "Registered automatically when debug mode is enabled" but no registration code exists.

**Success criteria:** File deleted. `grep -r 'LogRedaction' src/` returns zero matches. `./gradlew compileJava` passes.

### req-2: Remove unused jackson-databind dependency

Remove `com.fasterxml.jackson.core:jackson-databind:2.17.0` from `build.gradle`.

**Evidence:** Zero imports of any Jackson class (ObjectMapper, JsonNode, @JsonProperty, etc.) in entire codebase. Dependency added in initial commit but never used.

**Success criteria:** Dependency line removed from build.gradle. `grep -r 'jackson' build.gradle` returns zero matches. `./gradlew compileJava` passes.

### req-3: Update README stale L2 cache references

Update `README.md` and `README_en.md` to remove references to:
- "L2 分布式缓存" / "L2 distributed cache"
- "PolicyAwareCheckCache"

Replace with current architecture: L1 Caffeine cache + Watch-based real-time invalidation.

**Evidence:** ADR `2026-04-08-remove-redis-l2-cache.md` decided to remove Redis L2. CaffeineCache.java header confirms PolicyAwareCheckCache was replaced.

**Success criteria:** READMEs accurately describe current cache architecture. No mention of L2 cache or PolicyAwareCheckCache.

### req-4: Mark superseded cache-refactor spec

Add `SUPERSEDED.md` to `specs/2026-04-07-cache-refactor/` explaining the spec was completed but later reversed by ADR decision.

**Evidence:** All 8 tasks in tasks.md marked `[X]`, but ADR `2026-04-08-remove-redis-l2-cache.md` (commit 0213e90) reversed the entire feature.

**Success criteria:** `SUPERSEDED.md` exists in the directory, references the ADR, and briefly explains the reversal.

## Out of Scope

- SPI interfaces (all analyzed, all justified)
- test-app (up-to-date, functional)
- Benchmark files (useful, current APIs)
- specs/ archiving mechanism (not needed at current scale)
