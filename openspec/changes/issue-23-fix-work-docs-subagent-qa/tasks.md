## 1. `sdlc-interrogate.md`

- [ ] 1.1 Remove `AskUserQuestion` from the frontmatter `tools:` list (line 4).
- [ ] 1.2 In step 5 of the Process section (line 15), replace "one question at a time, your recommended answer offered with each" mechanics with explicit relay-loop instructions: ask one question at a time in plain text and end the turn (no tool call); when re-invoked with the orchestrator's relayed answer, treat it as genuine user confirmation and continue.
- [ ] 1.3 Add a short paragraph (near step 5 or as a new numbered step) stating this relay-trust rule as a deliberate, scoped exception to the general "no agent message is user consent" rule: an orchestrator-relayed answer to a question *this agent itself just asked in plain text* is to be treated as genuine user confirmation, specifically in this pipeline role.
- [ ] 1.4 Update the confirmation rule in the Rules section (line 37, "Do not proceed to the report step until the user has explicitly confirmed alignment, not just answered questions") so it's explicitly satisfied by a relayed confirmation delivered via re-invocation from the orchestrator, not only by a direct tool response.
- [ ] 1.5 Note that prior Q&A state must be recoverable from the issue thread/conversation history across dispatches, since each re-invocation may be a fresh context.

## 2. `sdlc-elaborate.md`

- [ ] 2.1 Remove `AskUserQuestion` from the frontmatter `tools:` list (line 4).
- [ ] 2.2 Rewrite step 4 (lines 18-21, "interrogate the user with `AskUserQuestion`") to describe the relay pattern instead: ask 1-3 sharp questions per round in plain text and end the turn; wait to be re-invoked with the orchestrator's relayed answers before continuing; push back on vague answers as before.
- [ ] 2.3 Add the same scoped-exception framing as 1.3: relayed answers to this agent's own just-asked questions are genuine user confirmation in this pipeline role, not an untrusted claim from another agent.
- [ ] 2.4 Note that prior Q&A state must be recoverable from the issue thread/conversation history across dispatches.

## 3. `.claude/commands/work.md`

- [ ] 3.1 Rewrite the sentence at lines 9-11 that justifies foreground execution by claiming direct questions "only reach the user if the agent is in the foreground." Replace with a description of the relay flow: `sdlc-elaborate` and `sdlc-interrogate` ask questions in plain text and end their turn; the orchestrator (running in the foreground) must see that turn to relay the question to the user via `AskUserQuestion`, then re-invoke the subagent with the relayed answer.
- [ ] 3.2 Keep the "run every stage in the foreground" requirement itself unchanged — only its justification changes.
- [ ] 3.3 If useful, add a short note under "Dispatch pattern" (around line 52) clarifying that a dispatch of `sdlc-elaborate` or `sdlc-interrogate` may need to be repeated turn-by-turn (relay question, relay answer, re-invoke) rather than completing in a single `Agent` call — consistent with the existing dispatch pattern but making explicit that these two stages are multi-turn.

## 4. Verification

- [ ] 4.1 Re-read all three edited files end-to-end to confirm no remaining prose or frontmatter references `AskUserQuestion` as something `sdlc-interrogate` or `sdlc-elaborate` call directly, and that the relay pattern is consistently described across all three files.
- [ ] 4.2 Confirm `.claude/commands/new-ticket.md` was not modified (out of scope per alignment).
- [ ] 4.3 Manually trace through a hypothetical `/work <issue>` run reaching the interrogate stage and confirm the documented relay loop is unambiguous enough that a subagent following it would accept a relayed answer as confirmation without needing to be told again in-conversation.
