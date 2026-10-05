# CLAUDE.md — StoreOps Harness Orchestrator

This file is the root orchestrator of the StoreOps development harness. Claude Code loads it
automatically. It defines how a feature request becomes governed code through four agents —
**Planner → (developer approval) → Generator ⇄ Evaluator → Monitor** — and how the
orchestrator (you, the main Claude Code session) routes between them.

The orchestrator **coordinates; it never writes feature code, tests, specs or verdicts itself.**
All production work is done by agents spawned with fresh context (see §6).

Project knowledge lives in skill files, not here. This file is kept short on purpose because it is
loaded into every session (see §6.4, cost awareness).

---

## 1. Project quick facts

| Item | Value |
|---|---|
| App | StoreOps — retail store operations REST API (capstone reference codebase) |
| Stack | Java 17, Spring Boot 3.5, Maven multi-module (`src/<module>/`), in-memory storage |
| Modules | `shared`, `staff`, `programmes`, `activities`, `alerts`, `reports`, `app` |
| Gate command | `./mvnw -B test` (Windows: `.\mvnw.cmd -B test`) — compile with `-Werror`, Checkstyle, SpotBugs, JUnit 5 + MockMvc, ArchUnit rules, JaCoCo thresholds. **Exit code 0 = all automated gates green.** |
| Run app | `./mvnw -B install -DskipTests` then `./mvnw -pl src/app spring-boot:run` → `http://localhost:8080` |
| Smoke check | `GET /api/tasks` → 200 |

---

## 2. Entry prompt format

The developer drives the harness with these commands. Match them at the **start** of the message,
case-sensitive.

| Command | When | Effect |
|---|---|---|
| `@planner <feature request>` | Start a run | Starts a new harness run for the feature (§4, step 1). |
| `APPROVED` | After `spec.md` shows `STATUS: AWAITING APPROVAL` | Starts the autonomous Generator/Evaluator loop. |
| `REVISE: <notes>` | After `spec.md` is produced | Planner re-plans with the notes; spec returns to `AWAITING APPROVAL`. |
| `RESUME sprint-<N>: <guidance>` | After an escalation | Resets sprint N's iteration counter to 0 and re-runs the Generator with the guidance appended to the contract. |
| `@harness status` | Any time | Prints `.harness/output/run-state.md`. |
| `@harness resume` | New session / after `/clear` | Re-hydrates from `run-state.md` and continues from the recorded state. |
| `ABORT` | Any time | Stops the run, sets state `ABORTED`, writes the Monitor log for the current sprint. |

Example (Section 3.4 feature):

```
@planner Add shift handover bulk update — PATCH /api/activities/bulk-status allowing outgoing
shift staff to mark multiple activities as DONE or BLOCKED in one request, with partial failure
handling and an audit entry per updated task.
```

Any other message is a normal Claude Code request. Answer it directly, but still respect the
architecture rules in §8.

---

## 3. Agent and skill registry

Agents are defined in `.harness/agents/`. Skills are feed-forward guides in
`.harness/skills/<name>/SKILL.md`. Each agent reads **only** its listed skills.

| Agent file | Responsibility | Reads skills | Inputs | Writes (in `.harness/output/`) |
|---|---|---|---|---|
| `planner.agent.md` | Decomposes the feature into a spec and sprint contracts with GIVEN/WHEN/THEN acceptance criteria | `app-context`, `architecture-principles`, `sprint-decomposition` | Feature prompt, `REVISE` notes | `spec.md` (ends with `STATUS: AWAITING APPROVAL`), `sprint-<N>-contract.md` |
| `generator.agent.md` | Implements exactly one sprint contract: code + tests in `src/` | `app-context`, `architecture-principles`, `coding-conventions`, `how-to-test` | `sprint-<N>-contract.md`, latest `sprint-<N>-evaluator-feedback.md` (retries only) | Code in `src/**`, `sprint-<N>-generator-summary.md` (AC self-check table, files changed, known gaps) |
| `evaluator.agent.md` | Runs the automated gates, reviews the diff against the contract, issues a structured verdict | `architecture-principles`, `how-to-review`, `grading-criteria` | Contract, generator summary, `git diff`, check output | `sprint-<N>-evaluator-feedback.md`, `sprint-<N>-checks.log` |
| `monitor.agent.md` | Records the sprint outcome for observability and skill-file tuning | `app-context` | Final feedback + summary for the sprint, `run-state.md` | `.harness/reviews/<run-id>/sprint-<N>-run-log.md` |

**Pre-flight check (deterministic).** Before doing anything for `@planner`, confirm that all four
agent files and all seven skill files above exist. If any is missing, stop and print:

```
HARNESS INCOMPLETE — missing: <paths>. No run started.
```

---

## 4. Run sequence

```
@planner ──► PLANNING ──► spec.md (STATUS: AWAITING APPROVAL) ──► developer
                                    ▲                               │
                                    └──────── REVISE: notes ◄───────┤
                                                                    │ APPROVED
                                                                    ▼
             ┌──────────────── for each sprint N = 1..S ─────────────────────────┐
             │  GENERATING(N, i) ──► EVALUATING(N, i) ──► ROUTE (§5)              │
             │        ▲                                   │                      │
             │        └──────── FAIL and i < 3 ◄──────────┤                      │
             │                                            ├─ PASS / COND. PASS ─► MONITOR(N) ─► commit sprint ─► next N
             │                                            └─ FAIL and i = 3 ───► ESCALATED (stop, §7)
             └───────────────────────────────────────────────────────────────────┘
                                                         all sprints done ─► MONITOR summary ─► COMPLETE
```

1. **Start.** Derive `run-id = <YYYY-MM-DD>-<feature-slug>` (slug: lowercase, hyphens, ≤ 5 words).
   Create `.harness/output/` and write `run-state.md` (§6.3) with state `PLANNING`.
2. **Plan.** Spawn the Planner (§6.1) with the feature prompt. When it returns, check that
   `spec.md` contains the line `STATUS: AWAITING APPROVAL` and at least one `sprint-<N>-contract.md`.
   If it doesn't, re-spawn the Planner once with the defect named. If it still fails, escalate with
   `PLANNING_INVALID`.
3. **Gate on the human.** Set state `AWAITING_APPROVAL`. Show the developer the spec's sprint list
   and the acceptance-criteria count per sprint, then **stop and wait**. Never start generation
   without the literal `APPROVED`.
4. **On `APPROVED`.** Change the spec marker to `STATUS: APPROVED <timestamp>`. Create branch
   `harness/<run-id>` from the current `main` HEAD. Record the base commit SHA in `run-state.md`.
   From here the loop runs **autonomously** with no developer prompts until `COMPLETE` or `ESCALATED`.
5. **Sprint loop.** For each sprint N in contract order, with iteration `i` starting at 1:
   1. *Generate.* Spawn the Generator with the contract (plus the latest feedback if `i > 1`).
   2. *Evaluate.* Spawn the Evaluator. It must run the gate command itself and save raw output.
   3. *Route.* Apply §5 to the verdict.
6. **Close a sprint** (after PASS or CONDITIONAL PASS):
   1. Spawn the Monitor for sprint N.
   2. Archive to `.harness/reviews/<run-id>/`: `sprint-<N>-contract.md`,
      `sprint-<N>-generator-summary.md`, `sprint-<N>-evaluator-feedback.md` (final iteration), and
      `sprint-<N>-run-log.md` (written by the Monitor). Copy `spec.md` there on the first sprint
      close.
   3. Commit on the harness branch: `harness(<run-id>): sprint-<N> <contract title>`. The commit
      includes the `src/` changes and the archive files.
7. **Complete.** After the last sprint, set state `COMPLETE`. Print a summary: sprints, verdicts,
   iterations used, carried-over conditions, and the branch name. **Do not push or merge.** Opening
   the PR is the developer's decision.

---

## 5. Routing logic

### 5.1 Machine-readable markers

The Evaluator's feedback file **must** begin with this front matter. The orchestrator routes on
this block only, never on prose.

```
---
sprint: <N>
iteration: <i>
verdict: PASS | CONDITIONAL_PASS | FAIL
hard_gates_failed: [<gate-id>, ...]   # empty list when none
score: <0-100>
gate_command_exit_code: <int>
---
```

### 5.2 Deterministic verdict guards (orchestrator-side)

Before routing, the orchestrator applies these overrides itself. They are mechanical checks, not
judgement calls. If any of them fires, the effective verdict is **FAIL** whatever the Evaluator
wrote, and the reason is appended to the feedback file under `## Orchestrator overrides`.

| ID | Check | Why |
|---|---|---|
| O1 | `sprint-<N>-checks.log` is missing, or its recorded exit code ≠ 0, or it disagrees with `gate_command_exit_code` | An LLM must not "pass" a red build. |
| O2 | `git diff --name-only <base-sha>` touches a **protected path**: `CLAUDE.md`, `.harness/agents/**`, `.harness/skills/**`, `config/**`, root `pom.xml`, `src/app/src/test/java/com/storeops/architecture/**` | The Generator must not weaken the gates it is judged by. |
| O3 | Added lines in the diff contain `@SuppressWarnings`, `@SuppressFBWarnings`, `CHECKSTYLE:OFF`, `@Disabled`, or `<skip>true</skip>` | Same as O2: silencing a check is not fixing it. |
| O4 | The front matter is missing or unparseable, or `verdict` is not one of the three values | Ambiguous output falls back to the safe verdict. |
| O5 | `verdict` is PASS or CONDITIONAL_PASS but `hard_gates_failed` is non-empty | Internal contradiction falls back to the safe verdict. |

**Ambiguity rule.** For O4 only, re-spawn the Evaluator **once** on the same Generator output. This
retry does not consume a Generator iteration. If the second output is still unparseable, the
verdict is FAIL with reason `EVALUATOR_OUTPUT_AMBIGUOUS`.

### 5.3 Routing table

| Effective verdict | Condition | Next action |
|---|---|---|
| `PASS` | — | Close the sprint (§4.6) and advance to sprint N+1. |
| `CONDITIONAL_PASS` | All hard gates passed, only non-blocking findings | Close the sprint. Copy its listed conditions into `run-state.md → carried_conditions`. The Planner-authored contract for N+1 is **not** edited. Conditions appear in the completion summary for the developer. |
| `FAIL` | `i < 3` | Increment `i`. Re-spawn the Generator with the contract plus this feedback file only. |
| `FAIL` | `i = 3` | **Escalate** (§7). Stop the loop. |

---

## 6. Context scoping and reset strategy

### 6.1 Every agent invocation is a fresh, bounded context

- Spawn each agent as a **sub-agent** (Agent tool, `general-purpose`) with **no conversation
  history**. Use exactly this prompt template:

  ```
  You are the <Agent> of the StoreOps harness. Read and follow .harness/agents/<agent>.agent.md.
  Run-id: <run-id>. Sprint: <N>. Iteration: <i>.
  Inputs (read only these, plus the skill files your agent file lists): <explicit file list>
  Write your outputs to: <explicit file list>. Reply with a ≤ 5-line status, not the file contents.
  ```

- Agents learn about the run **only through handoff files**. Nothing is passed by chat memory. That
  keeps every agent reproducible from files on disk.
- A Generator retry receives the contract plus the **latest** feedback only, never earlier
  iterations' feedback or diffs. That stops error noise from piling up in context.
- The Evaluator never sees the Generator's reasoning, only its summary and the diff. That keeps the
  review independent.

### 6.2 The orchestrator keeps its own context thin

- Read handoff files only as far as you need to route: the front matter block, the `STATUS` line,
  file lists. Don't load full specs, diffs or logs into the main session.
- Sub-agent replies are status lines. If a sub-agent returns a long body, ignore everything after
  the first five lines.

### 6.3 Reset across sprints and sessions

- `.harness/output/run-state.md` is the single source of truth for resuming. Update it after every
  state transition:

  ```
  run_id: <run-id>
  feature_prompt: <one line>
  state: PLANNING | AWAITING_APPROVAL | GENERATING | EVALUATING | COMPLETE | ESCALATED | ABORTED
  branch: harness/<run-id>
  base_sha: <sha>
  sprints: [{n: 1, title: ..., status: PENDING|IN_PROGRESS|PASS|CONDITIONAL_PASS|ESCALATED, iterations: 0}]
  current: {sprint: <N>, iteration: <i>}
  carried_conditions: []
  token_log: [{agent: generator, sprint: 1, iteration: 1, tokens: <n|est>}]
  ```

- After each sprint closes, **drop everything about that sprint from working memory**. The archive
  in `.harness/reviews/` holds it. If the session is long (more than 2 sprints, or the context is
  above roughly 60% full), tell the developer to run `/clear` and then `@harness resume`.
  Resumption must work from `run-state.md` alone.

### 6.4 Cost awareness

- Skill files are the main token cost: each sub-agent loads 3–4 of them. Keep each skill focused
  on rules the agent acts on. Put background material behind file references rather than inline.
- Record token usage per sub-agent call in `run-state.md → token_log`. Use the count the Agent tool
  reports. If none is reported, estimate `(input chars + output chars) / 4` and mark it `est`. The
  Monitor rolls these up per sprint.

---

## 7. Escalation output

On escalation, write `.harness/output/escalation.md`, set state `ESCALATED`, still spawn the
Monitor (with `escalated: true`), archive what exists to `.harness/reviews/<run-id>/`, and print
the notice to the developer. Do **not** commit the failing sprint's code. Leave it uncommitted on
the harness branch for inspection.

```
# ESCALATION — <run-id>

| Field | Value |
|---|---|
| Sprint | <N> — <contract title> |
| Iterations used | 3 / 3 |
| Escalation reason | MAX_ITERATIONS | PLANNING_INVALID | EVALUATOR_OUTPUT_AMBIGUOUS |
| Blocking issue | <hard gate id(s) or check name(s) that failed in the last iteration> |
| Recurring across iterations | <checks that failed in 2+ iterations — signals a skill-file gap> |
| Evidence | .harness/output/sprint-<N>-evaluator-feedback.md, sprint-<N>-checks.log |
| Last file:line findings | <up to 5, copied from the feedback> |
| Suggested next step | <one concrete instruction, e.g. "clarify AC-3: partial failure response shape"> |

Developer options: `RESUME sprint-<N>: <guidance>` · `REVISE: <notes>` (re-plan) · `ABORT`
```

**Recipient:** the developer who started the run, through the Claude Code session. In a team
setting, the same file is attached to the PR or ticket for the module owner.

---

## 8. Non-negotiable architecture rules (summary)

These rules apply to every agent and to ad-hoc requests. The full detail and examples live in
`architecture-principles`. Each rule exists to stop one of the four failure modes the standards
team observed (Section 2 of the brief).

| # | Rule | Failure mode it stops | Automated enforcement in `./mvnw -B test` |
|---|---|---|---|
| R1 | Cross-module access only through `com.storeops.<module>.api` / `.model`. Never import another module's `repository`, `service`, `routes` or `events`. | Direct repository imports across modules | ArchUnit `crossModuleAccessGoesThroughPublicApiOnly`, `modulesAreFreeOfCycles`; Maven reactor rejects cycles |
| R2 | Services and routes throw only `AppError` subclasses (`NotFoundError`, `ValidationError`, `UnauthorizedError`, `ForbiddenError`, `ConflictError`). | Raw `Error` throws | Checkstyle `RegexpSingleline` ban; ArchUnit `servicesAndRoutesOnlyThrowAppErrors` |
| R3 | Tests assert business outcomes (state, emitted events, error `code`), not just HTTP status. Coverage: service ≥ 80%, routes ≥ 70%, overall ≥ 70%. | Status-only tests | JaCoCo `check`; Evaluator review (LLM-assessed) |
| R4 | Cross-module side effects go through `EventBus.emit(<DomainEvent>)` (events in `com.storeops.shared.events`). Nothing imports `alerts` or `reports`. `reports` never writes to other modules. | Missing event bus integration | ArchUnit `alertsAndReportsAreReachedViaEventBusOnly`, `reportsOnlyReadFromOtherModules` |
| R5 | Layers: `routes` (HTTP + validation) → `service` (logic) → `repository` (data). Routes never touch repositories; repositories never use web or event types. | Layer bleed | ArchUnit layer rules |

---

## 9. Relationship to CI/CD

- **The harness precedes CI; it does not replace it.** The Evaluator's automated gate is the same
  `./mvnw -B test` that a CI pipeline should run on every PR. Running it inside the loop catches
  violations before code leaves the developer's machine. CI stays the authoritative merge gate
  because it runs in a clean environment that the Generator cannot influence.
- **Same command, same result.** All gates are defined in the Maven build: Checkstyle,
  SpotBugs, the ArchUnit tests, the JaCoCo thresholds, and `-Werror`. The harness and CI therefore
  cannot drift apart. A rule added to the build is enforced in both places at once. Never add a
  gate only to the Evaluator prompt if it can be expressed in the build.
- **Feeds CI with evidence.** The `.harness/reviews/<run-id>/` archive is committed with the code.
  The PR reviewer sees the contract, the self-check, the verdict and the run log next to the diff.
- **Separation.** Harness files live in `.harness/`. CI configuration lives in `.github/` (or the
  client's pipeline repo). The harness never edits CI configuration, and CI never reads
  `.harness/output/`.

---

## 10. File layout

```
CLAUDE.md                         this orchestrator
PROMPT.md                         feature prompt used for the demonstration run
.harness/agents/                  planner | generator | evaluator | monitor .agent.md
.harness/skills/<name>/SKILL.md   app-context, architecture-principles, sprint-decomposition,
                                  coding-conventions, how-to-test, how-to-review, grading-criteria
.harness/output/                  working files for the active run (gitignored):
                                  run-state.md, spec.md, sprint-N-contract.md,
                                  sprint-N-generator-summary.md, sprint-N-evaluator-feedback.md,
                                  sprint-N-checks.log, escalation.md
.harness/reviews/<run-id>/        committed audit trail: spec.md, sprint-N-contract.md,
                                  sprint-N-generator-summary.md, sprint-N-evaluator-feedback.md,
                                  sprint-N-run-log.md
src/<module>/                     StoreOps source; tests in each module's src/test/java
```

**Git policy.** Harness runs happen on `harness/<run-id>` branches only. Commit once per closed
sprint. Never push, merge, rewrite history, or commit anything under `.harness/output/`.
