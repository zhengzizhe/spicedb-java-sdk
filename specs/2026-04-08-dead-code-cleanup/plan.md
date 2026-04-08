# Dead Code & Stale Content Cleanup — Implementation Plan

> **For agentic workers:** Use authx-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove confirmed dead code, unused dependencies, and stale documentation.

**Architecture:** Four independent deletions/edits — no new code, no new abstractions. Each task is self-contained and can be verified independently.

**Tech Stack:** Gradle, Git

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Delete | `src/main/java/com/authx/sdk/builtin/LogRedactionInterceptor.java` | Dead code — zero references |
| Edit | `build.gradle` (line 39) | Remove jackson-databind dependency |
| Edit | `README.md` (line 11) | Fix stale L2 cache description |
| Edit | `README_en.md` (line 11) | Fix stale L2 cache description (English) |
| Create | `specs/2026-04-07-cache-refactor/SUPERSEDED.md` | Mark spec as reversed |

---

### Task T001: Delete LogRedactionInterceptor

**Files:**
- Delete: `src/main/java/com/authx/sdk/builtin/LogRedactionInterceptor.java`

**Steps:**
1. Delete the file:
   ```bash
   rm src/main/java/com/authx/sdk/builtin/LogRedactionInterceptor.java
   ```
2. Verify no references remain:
   ```bash
   grep -r 'LogRedaction' src/
   ```
   Expected: zero matches.
3. Compile:
   ```bash
   ./gradlew compileJava
   ```
   Expected: BUILD SUCCESSFUL.
4. Commit:
   ```bash
   git add -A && git commit -m "chore: remove dead LogRedactionInterceptor"
   ```

---

### Task T002: Remove jackson-databind dependency

**Files:**
- Edit: `build.gradle` (line 39)

**Steps:**
1. Remove this line from `build.gradle`:
   ```
   implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
   ```
2. Verify no jackson references remain in build.gradle:
   ```bash
   grep 'jackson' build.gradle
   ```
   Expected: zero matches.
3. Compile:
   ```bash
   ./gradlew compileJava
   ```
   Expected: BUILD SUCCESSFUL.
4. Commit:
   ```bash
   git add build.gradle && git commit -m "build: remove unused jackson-databind dependency"
   ```

---

### Task T003: Update README stale L2 cache references

**Files:**
- Edit: `README.md` (line 11)
- Edit: `README_en.md` (line 11)

**Steps:**
1. In `README.md`, replace line 11:
   ```
   - **二级缓存**：L1 内存缓存（Caffeine）+ 可选 L2 分布式缓存，`PolicyAwareCheckCache` 按 resource type 独立 TTL
   ```
   with:
   ```
   - **智能缓存**：Caffeine 内存缓存 + Watch 实时失效，按 resource type 独立 TTL
   ```

2. In `README_en.md`, replace line 11:
   ```
   - **Two-Level Cache**: L1 in-memory cache (Caffeine) + optional L2 distributed cache, `PolicyAwareCheckCache` with independent TTL per resource type
   ```
   with:
   ```
   - **Smart Caching**: Caffeine in-memory cache + Watch-based real-time invalidation, with independent TTL per resource type
   ```

3. Verify no stale references remain:
   ```bash
   grep -r 'L2\|PolicyAwareCheckCache\|二级缓存\|分布式缓存\|distributed cache\|Two-Level Cache' README.md README_en.md
   ```
   Expected: zero matches.

4. Commit:
   ```bash
   git add README.md README_en.md && git commit -m "docs: update README cache description to reflect current architecture"
   ```

---

### Task T004: Mark superseded cache-refactor spec

**Files:**
- Create: `specs/2026-04-07-cache-refactor/SUPERSEDED.md`

**Steps:**
1. Create `specs/2026-04-07-cache-refactor/SUPERSEDED.md` with content:
   ```markdown
   # Superseded

   This spec was fully implemented (all 8 tasks completed), but the feature was subsequently reversed.

   **Decision:** ADR `2026-04-08-remove-redis-l2-cache.md` (commit 0213e90) decided to remove the Redis L2 cache layer. The L1 Caffeine cache + Watch-based invalidation was deemed sufficient.

   **Reason:** Redis L2 added operational complexity (deployment, monitoring, failure modes) without meaningful latency improvement for typical SDK deployments where a single JVM serves most requests.

   The spec and plan files are preserved for historical reference.
   ```

2. Commit:
   ```bash
   git add specs/2026-04-07-cache-refactor/SUPERSEDED.md && git commit -m "docs: mark cache-refactor spec as superseded by ADR"
   ```

---

### Task T005: Final verification

**Steps:**
1. Run full compile:
   ```bash
   ./gradlew compileJava
   ```
   Expected: BUILD SUCCESSFUL.

2. Run tests:
   ```bash
   ./gradlew test -x :test-app:test
   ```
   Expected: all tests pass.

3. Verify all dead references gone:
   ```bash
   grep -r 'LogRedaction' src/
   grep 'jackson' build.gradle
   grep -r 'PolicyAwareCheckCache\|二级缓存\|分布式缓存\|L2.*cache\|Two-Level Cache' README.md README_en.md
   ```
   Expected: all three return zero matches.
