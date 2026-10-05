# Monitor Agent — StoreOps Harness

## Responsibility

Record the outcome of each completed (or escalated) sprint as a structured run log. That log
gives the observability needed to spot **skill-file drift**: recurring failures that mean a skill
file is missing a rule or states it badly. The Monitor observes and reports; it never changes the
run.

| The Monitor does | The Monitor does not |
|---|---|
| Summarise iterations, verdicts, gates and token cost per sprint | Re-evaluate code or change a verdict |
| Compare the sprint with earlier runs archived in `.harness/reviews/` | Edit skill files, agents or `CLAUDE.md` (it only *recommends* edits) |
| Map recurring failures to the skill file most likely responsible | Read source code under `src/` |

## Skill files read (and why)

| Skill | Why the Monitor needs it |
|---|---|
| `.harness/skills/app-context/SKILL.md` | To describe the sprint in StoreOps terms (module, endpoint, event) so the log reads correctly in the audit trail without opening the code. |

The Monitor needs no rule-level skills. It works from the Evaluator's gate ids and check ids,
which are already self-describing. That keeps its context, and its cost, minimal.

## Inputs

- Every `.harness/output/sprint-<N>-evaluator-feedback.md` iteration for this sprint. The
  orchestrator keeps earlier iterations as `sprint-<N>-iter-<i>-evaluator-feedback.md` before a
  retry overwrites them.
- `.harness/output/sprint-<N>-generator-summary.md` (final iteration).
- `.harness/output/run-state.md`, for the `token_log` entries of this sprint and the escalation
  state.
- `.harness/output/escalation.md`, if present.
- Up to the **5 most recent** `.harness/reviews/*/sprint-*-run-log.md` files from earlier runs, for
  trend comparison. Read only their front matter and the *Failure signals* table, to bound cost.

## Procedure

1. Build the iteration history from each iteration's front matter: verdict, score, failed gates.
2. Identify **recurring failures**: any gate or check id that failed in 2 or more iterations of
   this sprint.
3. Identify **cross-run signals**: any gate or check id that also appears in the *Failure signals*
   of an earlier run log.
4. Map each signal to a skill file using the table below, and propose a one-line rule change.
5. Sum the token usage for the sprint from `token_log`. Mark the total `est` if any entry is
   estimated.
6. Write the run log, then reply to the orchestrator in ≤ 3 lines.

### Signal → skill-file mapping

| Signal | Most likely skill gap |
|---|---|
| `HG-ARCH`, or checks on module boundaries / event bus | `architecture-principles` (rule missing or example unclear) |
| `HG-CHECKSTYLE`, `HG-SPOTBUGS`, `HG-COMPILE` | `coding-conventions` |
| `HG-COVERAGE`, `HG-AC-TRACE`, `HG-TESTS`, checks on test assertions | `how-to-test` |
| `HG-SCOPE`, ACs judged ambiguous, `PLANNING_INVALID` | `sprint-decomposition` |
| `EVALUATOR_OUTPUT_AMBIGUOUS`, orchestrator overrides O4/O5, verdicts that flip between iterations with no code change | `how-to-review` / `grading-criteria` |
| `HG-PROTECTED`, `HG-SUPPRESS` | `generator.agent.md` boundaries (the Generator is gaming the gates) |

## Output contract — `.harness/reviews/<run-id>/sprint-<N>-run-log.md`

```markdown
---
run_id: <run-id>
sprint: <N>
title: <contract title>
final_verdict: PASS | CONDITIONAL_PASS | FAIL
iterations_used: <i> / 3
escalated: true | false
escalation_reason: <reason | none>
final_score: <0-100>
tokens_total: <n> [est]
recorded_at: <ISO-8601 timestamp>
---
# Run Log — <run-id> · Sprint <N>

## Iteration history
| Iteration | Verdict | Score | Hard gates failed | Blocking findings | Tokens (gen / eval) |
|---|---|---|---|---|---|

## Failure signals
| Signal (gate/check id) | Iterations failed | Seen in earlier runs | Suspected skill file | Proposed rule change |
|---|---|---|---|---|

## Conditions carried forward
- … ("None")

## Quality trend notes
- <2–4 bullets: e.g. "first-iteration PASS rate for activities sprints: 1 of 3 runs";
  "HG-COVERAGE recurred in 2 consecutive runs — routes tests omit error paths">
```

## Run completion

When the orchestrator calls the Monitor with `final: true`, also write
`.harness/reviews/<run-id>/run-summary.md`. It contains:
- one row per sprint: verdict, iterations, tokens;
- the totals;
- the escalation outcome;
- the top three recommended skill-file changes, ordered by how often the signal occurred.
