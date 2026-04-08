---
name: authx-executing-plans
description: "有了书面实施计划后，在独立会话中分步执行并设置审查检查点"
---

# Executing Plans

## Overview

Load plan, review critically, execute all tasks, report when complete.

**Announce at start:** "I'm using the executing-plans skill to implement this plan."

**Note:** Quality is significantly higher with subagent support (Claude Code). Use the Agent tool to dispatch independent tasks in parallel when possible.

## The Process

### Step 1: Load and Review Artifacts
1. Read `tasks.md` and `plan.md` from the specs directory
2. Review critically — identify any questions or concerns
3. If concerns: Raise them before starting
4. If no concerns: Create task list and proceed

### Step 2: Execute Tasks by Phase

For each phase in tasks.md:
1. Execute tasks in phase order, respecting dependencies
2. [P] tasks within the same phase can be dispatched to parallel agents
3. After EACH task: compile (`./gradlew compileJava`). If it fails, fix before moving on.
4. After EACH phase: run tests (`./gradlew test -x :test-app:test`). If new failures, stop and fix.
5. Mark completed tasks as `[X]` in tasks.md

**Phase Gate:** Do NOT start the next phase until the current phase's tests pass.

**Iceberg Check (after each task):** After completing a task, ask:
1. Does this change affect upstream or downstream code that wasn't in the task?
2. Are there similar patterns elsewhere in the codebase that need the same treatment?
3. Did I spot any code quality issues while working that I should fix now?

If yes to any: fix it now, don't leave it for later. One problem in, zero problems out.

### Step 3: Completion Gate

After all tasks complete:
1. Run full test suite: `./gradlew test -x :test-app:test`
2. Verify zero new failures
3. Present completion summary with coverage table:
   ```
   | Task | Status | Spec Requirement |
   |---|---|---|
   | T001 | [X] | req-1 |
   | T002 | [X] | req-2 |
   ```
4. Invoke authx-feedback-loop to audit document hierarchy

## When to Stop and Ask for Help

**STOP executing immediately when:**
- Hit a blocker (missing dependency, test fails, instruction unclear)
- Plan has critical gaps preventing starting
- You don't understand an instruction
- Verification fails repeatedly

**Ask for clarification rather than guessing.**

## When to Revisit Earlier Steps

**Return to Review (Step 1) when:**
- Partner updates the plan based on your feedback
- Fundamental approach needs rethinking

**Don't force through blockers** - stop and ask.

## Remember
- Review plan critically first
- Follow plan steps exactly
- Don't skip verifications
- Reference skills when plan says to
- Stop when blocked, don't guess
- Never start implementation on main/master branch without explicit user consent

## Final Step

After all tasks are executed and verified, invoke **authx-feedback-loop** to audit the document hierarchy against changes made. Non-negotiable.

## Integration

**Required workflow skills:**
- **authx-writing-plans** - Creates the plan this skill executes
- **authx-feedback-loop** - REQUIRED: Run after execution completes
