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

### Step 1: Load and Review Plan
1. Read plan file
2. Review critically - identify any questions or concerns about the plan
3. If concerns: Raise them with your human partner before starting
4. If no concerns: Create TodoWrite and proceed

### Step 2: Execute Tasks

For each task:
1. Mark as in_progress
2. Follow each step exactly (plan has bite-sized steps)
3. Run verifications as specified
4. Mark as completed

### Step 3: Complete Development

After all tasks complete and verified:
- Verify all tests pass, present completion summary to user
- Invoke authx-feedback-loop to audit document hierarchy

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
