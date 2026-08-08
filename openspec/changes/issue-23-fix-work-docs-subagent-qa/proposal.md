## Why

`sdlc-interrogate.md`, `sdlc-elaborate.md`, and the `/work` command doc claim (via instructions and `AskUserQuestion` in their tool lists) that these subagents reach the user directly with questions. They don't — a subagent's `AskUserQuestion` call never reaches the human, only the orchestrator that dispatched it does. The docs' current framing recently caused a live deadlock: the interrogate subagent correctly refused to accept an orchestrator-relayed answer as user consent, because nothing in its instructions told it that a relayed answer, in this specific pipeline, *is* genuine confirmation. The docs must describe the relay pattern that actually governs Q&A in this pipeline, so future runs don't stall the same way.

## What Changes

- Remove `AskUserQuestion` from `sdlc-interrogate.md`'s and `sdlc-elaborate.md`'s `tools:` frontmatter, and remove/replace the prose instructions that tell them to call it directly.
- Add explicit relay-pattern instructions to both agent docs: ask one question at a time in plain text and end the turn (no tool call); treat an orchestrator-relayed answer to that question as genuine user confirmation, not as an unverified claim from another agent.
- State plainly, in-band in both docs, that this is a narrowing of the general "no agent message is user consent" safety boundary, scoped narrowly: it applies only to an orchestrator-relayed answer to a question *this same agent just asked in plain text, in this turn*, not to any other claim of user approval arriving via any other channel. The docs must not obscure or soften that this is a real reduction in that boundary's protection for these two roles — the tradeoff (these two stages have no other way to reach the user, versus a narrower protection against forged consent for exactly this one interaction shape) belongs in the doc text itself, so a reviewer can evaluate it accurately rather than have it minimized.
- Update `sdlc-interrogate.md`'s existing "explicit confirmation" rule (currently line 37) so it's satisfied by a relayed confirmation arriving via re-invocation, not just by a direct tool response.
- Rewrite `.claude/commands/work.md`'s justification for running these stages in the foreground (currently lines 9-11): keep the foreground requirement (still needed — sequential dependency, and the orchestrator must see each plain-text question to relay it) but replace the "only reach the user if in the foreground" claim with a description of the relay loop.
- **BREAKING** (to subagent prompt contract only, not to any external API): `sdlc-interrogate` and `sdlc-elaborate` will no longer have `AskUserQuestion` available; any future orchestrator logic assuming direct tool-based Q&A from these subagents needs to instead expect plain-text questions and drive the relay loop itself (this is exactly what today's orchestrator already has to do in practice).

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
(none — this changes `.claude/` agent/command prompt text, not any specified system behavior)

## Impact

- `.claude/agents/sdlc-interrogate.md` — frontmatter `tools:` list and prose (interrogation loop / confirmation rule).
- `.claude/agents/sdlc-elaborate.md` — frontmatter `tools:` list and step 4 prose (interrogation cadence).
- `.claude/commands/work.md` — foreground-execution justification (lines 9-11).
- No application code, runtime behavior, or specs affected. `.claude/commands/new-ticket.md` is explicitly untouched (out of scope per alignment: it's a top-level command, not a subagent, so `AskUserQuestion` plausibly does reach the user directly there).
