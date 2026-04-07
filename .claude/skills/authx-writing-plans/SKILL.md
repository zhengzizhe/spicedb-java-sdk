---
name: authx-writing-plans
description: "有需求或规格说明后、动手写代码之前，先制定实施计划"
---

# Writing Plans

## Overview

Write comprehensive implementation plans assuming the engineer has zero context for our codebase and questionable taste. Document everything they need to know: which files to touch for each task, code, testing, docs they might need to check, how to test it. Give them the whole plan as bite-sized tasks. DRY. YAGNI. TDD. Frequent commits.

Assume they are a skilled developer, but know almost nothing about our toolset or problem domain. Assume they don't know good test design very well.

**Announce at start:** "I'm using the writing-plans skill to create the implementation plan."

**Context:** This should be run in a dedicated worktree (created by brainstorming skill).

**Artifact chain:** brainstorming creates `specs/YYYY-MM-DD-<topic>/spec.md`. This skill creates two files in the SAME directory:
- `specs/YYYY-MM-DD-<topic>/plan.md` — architecture, file structure, approach
- `specs/YYYY-MM-DD-<topic>/tasks.md` — structured task list with dependencies

Read spec.md FIRST. The plan and tasks must trace back to spec requirements.

## Scope Check

If the spec covers multiple independent subsystems, it should have been broken into sub-project specs during brainstorming. If it wasn't, suggest breaking this into separate plans — one per subsystem. Each plan should produce working, testable software on its own.

## File Structure

Before defining tasks, map out which files will be created or modified and what each one is responsible for. This is where decomposition decisions get locked in.

- Design units with clear boundaries and well-defined interfaces. Each file should have one clear responsibility.
- You reason best about code you can hold in context at once, and your edits are more reliable when files are focused. Prefer smaller, focused files over large ones that do too much.
- Files that change together should live together. Split by responsibility, not by technical layer.
- In existing codebases, follow established patterns. If the codebase uses large files, don't unilaterally restructure - but if a file you're modifying has grown unwieldy, including a split in the plan is reasonable.

This structure informs the task decomposition. Each task should produce self-contained changes that make sense independently.

## Bite-Sized Task Granularity

**Each step is one action (2-5 minutes):**
- "Write the failing test" - step
- "Run it to make sure it fails" - step
- "Implement the minimal code to make the test pass" - step
- "Run the tests and make sure they pass" - step
- "Commit" - step

## Plan Document Header

**Every plan MUST start with this header:**

```markdown
# [Feature Name] Implementation Plan

> **For agentic workers:** Use authx-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** [One sentence describing what this builds]

**Architecture:** [2-3 sentences about approach]

**Tech Stack:** [Key technologies/libraries]

---
```

## Task Structure (in tasks.md)

Tasks use a structured format with IDs, parallelization markers, and spec traceability:

```
- [ ] T001 [P] [SR:req-id] Description — `path/to/file.java`
```

- `T###` — sequential task ID
- `[P]` — parallelizable (can run concurrently with other [P] tasks in same phase)
- `[SR:xxx]` — traces back to a spec requirement (from spec.md)
- Description — what to do
- File path — primary file affected

### Task phases in tasks.md

```markdown
## Phase 0: Setup
- [ ] T001 Create feature branch and test scaffolding

## Phase 1: Foundation (blocks all other phases)
- [ ] T002 [SR:req-1] Define interfaces — `src/.../Foo.java`
- [ ] T003 [SR:req-1] Write tests for interfaces — `src/test/.../FooTest.java`

## Phase 2: Core Implementation
- [ ] T004 [P] [SR:req-2] Implement FooService — `src/.../FooService.java`
- [ ] T005 [P] [SR:req-3] Implement BarHandler — `src/.../BarHandler.java`

## Phase 3: Integration
- [ ] T006 [SR:req-4] Wire components together — `src/.../AuthxClient.java`
- [ ] T007 Run full test suite and verify

## Dependencies
T004, T005 depend on T002, T003
T006 depends on T004, T005
T004 and T005 are parallelizable [P]
```

### Each task in plan.md keeps detailed steps

````markdown
### Task T004: Implement FooService

**Files:**
- Create: `src/main/java/com/authx/sdk/FooService.java`
- Test: `src/test/java/com/authx/sdk/FooServiceTest.java`

**Steps:**
1. Write failing test
2. Run to verify it fails
3. Implement minimal code to pass
4. Run to verify it passes
5. Commit
````

## No Placeholders

Every step must contain the actual content an engineer needs. These are **plan failures** — never write them:
- "TBD", "TODO", "implement later", "fill in details"
- "Add appropriate error handling" / "add validation" / "handle edge cases"
- "Write tests for the above" (without actual test code)
- "Similar to Task N" (repeat the code — the engineer may be reading tasks out of order)
- Steps that describe what to do without showing how (code blocks required for code steps)
- References to types, functions, or methods not defined in any task

## Remember
- Exact file paths always
- Complete code in every step — if a step changes code, show the code
- Exact commands with expected output
- DRY, YAGNI, TDD, frequent commits

## Self-Review — Cross-Artifact Consistency Analysis

After writing plan.md and tasks.md, run this analysis against spec.md. This is a checklist you run yourself — not a subagent dispatch.

**Pass 1 — Coverage:** For every requirement in spec.md, find the task(s) in tasks.md that implement it. Output a coverage table:

```
| Spec Requirement | Task(s) | Status |
|---|---|---|
| req-1: ... | T002, T003 | Covered |
| req-2: ... | T004 | Covered |
| req-3: ... | — | **GAP** |
```

Any GAP is a plan failure. Add the missing task.

**Pass 2 — Placeholder scan:** Search plan.md for red flags — any of the patterns from the "No Placeholders" section above. Fix them.

**Pass 3 — Type consistency:** Do the types, method signatures, and property names you used in later tasks match what you defined in earlier tasks? A function called `clearLayers()` in Task 3 but `clearFullLayers()` in Task 7 is a bug.

**Pass 4 — Dependency integrity:** Are task dependencies in tasks.md consistent with the phase structure? Can parallelizable [P] tasks actually run independently?

**Pass 5 — Contradiction scan:** Do plan.md and spec.md contradict each other? Does the architecture in plan.md actually satisfy the requirements in spec.md?

If you find issues, fix them inline. If you find a spec requirement with no task, add the task.

## Execution Handoff

After saving plan.md and tasks.md, commit both files and report:

> "Plan and tasks saved to `specs/YYYY-MM-DD-<topic>/`. Artifact chain:
> - `spec.md` — what and why
> - `plan.md` — how (architecture, file structure, detailed steps)
> - `tasks.md` — execution checklist with dependencies
>
> Ready to execute with authx-executing-plans?"

**REQUIRED:** Use authx-executing-plans for execution. The executing-plans skill reads tasks.md and marks items `[X]` as completed.
