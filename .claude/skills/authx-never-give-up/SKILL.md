---
name: authx-never-give-up
description: "连续失败 2 次或准备说'暂缓/无法解决'时强制触发——穷尽一切方案才允许放弃"
---

# Never Give Up — 穷尽机制

## When to Use

**Automatic triggers** — invoke this skill when you catch yourself:
- About to say "暂缓", "defer", "cannot solve", "let's skip this"
- Failed the same task 2+ times consecutively
- About to blame the environment without verifying
- Suggesting the user do something manually that you should be able to do

**Manual trigger** — user says any of: "再试试", "别放弃", "穷尽", "try harder", "don't give up"

## The Protocol

When triggered, STOP and execute these steps IN ORDER. Do not skip any step.

### Step 1: Inventory — What have I actually tried?

List every approach attempted so far, with the specific failure reason for each:

```
Attempted approaches:
1. [approach] → failed because [specific reason]
2. [approach] → failed because [specific reason]
```

If you can't list specific failure reasons, you weren't paying attention. Go back and re-read the error messages.

### Step 2: What haven't I tried?

List at least 3 fundamentally different approaches you have NOT tried. "Fundamentally different" means:

- If you were modifying code → try reading the source/docs first
- If you were reading code → try searching the web
- If you were searching → try a completely different search query
- If you were guessing → try adding diagnostic logging
- If you were working top-down → try working bottom-up

**"Tweak the same thing slightly differently" is NOT a new approach.**

### Step 3: Use your tools

Before giving up, verify you have used ALL available tools:

| Tool | Used? | What to do |
|------|-------|------------|
| Read | | Read the ACTUAL source code, not just the error message |
| Grep | | Search the codebase for similar patterns that work |
| WebSearch | | Search with 3 different query formulations |
| Bash | | Run diagnostic commands, check logs, verify environment |
| Agent | | Dispatch a subagent with fresh context to try independently |

If any tool is unused and potentially relevant, USE IT NOW.

### Step 4: Fresh eyes

Explain the problem to yourself as if briefing a colleague who just walked in:
1. What is the expected behavior?
2. What is the actual behavior?
3. What is the EXACT error message? (copy-paste, don't paraphrase)
4. What have you already ruled out?

Often, writing this explanation reveals the answer.

### Step 5: Decision gate

After completing steps 1-4:

**If you found a new approach** → Try it. If it works, report the win. If it fails, you've earned more information — loop back to Step 1 with updated inventory.

**If you genuinely exhausted everything** → You may now say so, but you MUST:
- List every approach tried (minimum 5)
- State the specific failure reason for each
- State what information is missing that would unblock you
- Propose what the user can do that you cannot (be specific)

```
Exhaustion report:
Tried 6 approaches over N attempts:
1. [approach]: failed because [reason]
2. [approach]: failed because [reason]
...
Missing information: [what you need]
Suggested user action: [specific action]
```

**"I don't know" is acceptable. "I didn't try" is not.**

## Anti-Patterns

| Giving up | Not giving up |
|-----------|---------------|
| "This might be an environment issue" | "Let me verify: `env \| grep X`, `which Y`, `docker ps`" |
| "I suggest you do this manually" | "Let me try one more approach: [specific]" |
| "Let's defer this to a later task" | "I've tried 3 approaches. Let me try 2 more before we decide." |
| "I cannot solve this" | "I've exhausted 5 approaches. Here's what I know and what's missing." |
| "This seems like a bug in [tool]" | "Let me verify by checking [tool]'s issue tracker and docs" |

## Integration with Other Skills

- **authx-systematic-debugging**: This skill's Failure Escalation Protocol (L1→L3) feeds into Never Give Up. If you hit L3 exhaustion in debugging and are still stuck, invoke this skill for the final push.
- **authx-verification-before-completion**: Red Line 3 ("Did you exhaust?") references this skill as the standard for what "exhausted" means.
