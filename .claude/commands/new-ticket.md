---
description: Relentlessly question you to nail down purpose and outcome, then draft and file a well-formed GitHub issue via `gh issue create`
argument-hint: <short description of the bug/feature/task>
allowed-tools: Read, Grep, Glob, AskUserQuestion, Bash(gh issue create:*), Bash(gh issue list:*), Bash(gh repo view:*)
---

You are drafting a GitHub issue for this repo. Topic: $ARGUMENTS

Two goals, in order:
1. **Nail down the real purpose and outcome before writing anything.** Do not draft off a vague ask.
2. Once aligned, produce a ticket that's correct, verifiable, and easy to read for someone with **severe ADHD** — short, scannable, zero fluff. Then file it.

## Process

### 1. Interrogate first — do not draft yet

Question the user until you are both aligned on all four of these:
- **Purpose** — why does this need to happen? What's broken or missing without it?
- **Outcome** — what does "done" concretely look like? What observably changes?
- **Scope** — what's explicitly in, what's explicitly out?
- **Verification** — how will anyone confirm this actually worked?

Rules:
- Ask 1-3 sharp questions per round. Wait for answers. Never dump a full questionnaire at once.
- Push back on vague answers. "It should just work" is not an outcome — what observably changes? "Users will be happier" is not verification — what's the concrete check?
- If $ARGUMENTS already answers a dimension clearly, don't re-ask it — state it back in one line and move on.
- If the ticket touches existing code, use this phase to ground yourself: Grep/Glob/Read the relevant file(s) so acceptance criteria point at real functions/behavior, not guesses. Surface anything you find that complicates or contradicts what the user said (e.g. "that path already handles X — do you want Y on top of it, or instead of it?").
- You are done with this phase only when you can state the purpose and the outcome back to the user in one sentence each, and they agree without correction. Do not proceed to drafting before that.

### 2. Draft the ticket

Use exactly this structure. Keep every section short — that's the whole point.

```markdown
# <Title: verb + specific outcome, under 10 words>

**TL;DR:** <one sentence — what happens and why, no jargon>

## User story
As a <specific role>, I want <specific capability>, so that <specific benefit>.

## Acceptance criteria
- [ ] <testable, specific, one idea per line>
- [ ] <testable, specific, one idea per line>
- [ ] <testable, specific, one idea per line>

## How to verify
<Concrete steps or a command someone can run to confirm this is actually done. Not "test it works" — the literal steps/command and the expected result.>

## Out of scope
- <only include if there's real risk of scope creep — omit this section otherwise>
```

Formatting rules (non-negotiable):
- Short sentences. Active voice. No hedging ("might", "could potentially").
- One idea per bullet. Never nest bullets more than one level.
- Bold only the 1-3 words that matter most, if any — don't bold whole sentences.
- No walls of text. If a section runs past 3 lines of prose, cut it or convert to bullets.
- Acceptance criteria are checkable facts ("returns 404 when X"), never vague goals ("works well", "is robust").
- Total length excluding code fences: aim under 150 words.

### 3. Show the draft, then file it

Print the drafted ticket. Then file it — this repo always uses `gh issue create`, so there's no "how would you like to deliver this" question to ask:

```
gh issue create --title "<title>" --body "<body>"
```

Report the issue URL back once created.
