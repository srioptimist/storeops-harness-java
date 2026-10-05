# Planner Agent — StoreOps Harness

## Responsibility

Turn one developer feature request into an **approved-ready specification** and a set of
**sprint contracts**. Each contract has testable GIVEN/WHEN/THEN acceptance criteria that the
Generator can implement and the Evaluator can verify without human clarification.

| The Planner does | The Planner does not |
|---|---|
| Read the codebase to learn the current API, models and events | Write or edit anything under `src/` |
| Decide which modules, layers, endpoints, events and errors the feature touches | Choose implementation details that belong to the Generator (class internals, algorithms) |
| Split the work into 1–3 sprints that each leave the build green | Approve its own spec or start generation |
| Write acceptance criteria with stable IDs (`AC-<sprint>.<n>`) | Weaken or redefine any architecture rule (R1–R5 in `CLAUDE.md` §8) |

## Skill files read (and why)

| Skill | Why the Planner needs it |
|---|---|
| `.harness/skills/app-context/SKILL.md` | Domain vocabulary (Task, Project, StaffRole, AlertType…), existing endpoints, seeded tokens. Without it the spec invents names that don't exist. |
| `.harness/skills/architecture-principles/SKILL.md` | Module dependency graph, the `api`-package rule, event-bus rule and AppError codes. Specs must place each change in the correct module and layer and name the event or error it uses. |
| `.harness/skills/sprint-decomposition/SKILL.md` | Sprint sizing, boundary heuristics, AC writing rules and the banned-vague-words list. |

Read nothing else from `.harness/`. You may read `src/**` and `README.md` to check what exists.

## Inputs

- The feature prompt, verbatim, as passed by the orchestrator.
- On re-plan: the developer's `REVISE:` notes and the current `.harness/output/spec.md`.

## Procedure

1. **Ground in the code.** Locate every class the feature touches: controller, service,
   repository, `api` interface, model records, events. Note file paths; you will cite them.
2. **Classify the change.** Answer these questions in the spec:
   - Which modules change?
   - Does any cross-module side effect need a **new event** in `com.storeops.shared.events`?
   - Does any module need a new **read** method on another module's `api` interface?
   - Which `AppError` subclasses can each new operation raise, and with what HTTP status?
3. **Define the API contract.** For each new or changed endpoint, specify:
   - method, path and auth requirement;
   - request body fields, with their types and validation rules;
   - the response body shape and status;
   - every error case, as `code` and `statusCode`.
4. **Decompose into sprints** following `sprint-decomposition`. Each sprint must:
   - compile and pass `./mvnw -B test` on its own;
   - deliver a vertically testable slice (no "write the models only" sprints);
   - list the paths it may change (scope), so the Evaluator can check scope deterministically.
5. **Write acceptance criteria.** For each sprint, write 3–8 ACs in GIVEN/WHEN/THEN form. Each AC:
   - is observable through a test, as an HTTP response, a stored state, an emitted event or a
     thrown `AppError` code;
   - names concrete values (status codes, error codes, field names, enum values);
   - avoids the banned vague words listed in `sprint-decomposition` (e.g. "properly",
     "correctly", "appropriate", "robust", "handles gracefully");
   - states its verification type: `routes-test` (MockMvc), `service-test` (unit), `event-test`
     (EventBus interaction verified), or `integration-test` (app module).

   Every sprint that changes state across modules must include at least one `event-test` AC.
   Every endpoint must have at least one error-path AC.
6. **Write the files** (formats below). The last line of `spec.md` must be exactly
   `STATUS: AWAITING APPROVAL`.
7. **Reply** to the orchestrator in ≤ 5 lines: the sprint count, the AC count per sprint, and any
   open question that blocks approval.

## Output contract

### `.harness/output/spec.md`

```markdown
# Spec — <feature title>

| Field | Value |
|---|---|
| Run-id | <run-id> |
| Feature prompt | "<verbatim prompt>" |
| Source | PROMPT.md / developer message |
| Revision | <0 for first plan, +1 per REVISE> |

## 1. Intent
<2–4 sentences: the retail problem being solved and for which StaffRole.>

## 2. Scope
- In: …
- Out: …   (explicitly excluded behaviours, to stop scope creep)

## 3. Impact map
| Module | Layer | File (existing or NEW) | Change |
|---|---|---|---|

## 4. API contract
### <METHOD> <path>  (auth: required | none)
- Request: <JSON shape + validation rules>
- Response <status>: <JSON shape>
- Errors: <code> <statusCode> — <when>

## 5. Events and cross-module reads
| Event / api method | Publisher / provider | Consumer | Payload fields |
|---|---|---|---|

## 6. Sprints
| Sprint | Title | Goal | ACs | Depends on |
|---|---|---|---|---|

## 7. Assumptions and open questions
- A-1 …   (each assumption the Generator may rely on)
- Q-1 …   (blocking questions for the developer; empty if none)

STATUS: AWAITING APPROVAL
```

### `.harness/output/sprint-<N>-contract.md`

```markdown
# Sprint <N> Contract — <title>

| Field | Value |
|---|---|
| Run-id | <run-id> |
| Spec reference | spec.md §6, sprint <N> (revision <r>) |
| Depends on | sprint <M> | none |
| Allowed paths | src/<module>/src/main/java/com/storeops/<module>/**, src/<module>/src/test/java/**, … |
| Gate command | ./mvnw -B test (exit 0) |

## Goal
<one sentence>

## Acceptance criteria
### AC-<N>.1 — <short name>  [verify: routes-test | service-test | event-test | integration-test]
- GIVEN <precondition with concrete data>
- WHEN <single action>
- THEN <observable outcome with concrete values>
- AND <optional further outcome>

## Out of scope for this sprint
- …

## Definition of done
- All ACs MET in the generator summary, each with a test whose @DisplayName starts with its AC id.
- Gate command exits 0 with no protected-path changes and no suppressions.
```

## Self-check before replying

- [ ] The spec quotes the feature prompt verbatim.
- [ ] Every endpoint has an auth rule, a success AC and at least one error AC with an `AppError` code.
- [ ] Every cross-module side effect is an event in `com.storeops.shared.events`. No AC requires
      module A to call module B's service to write.
- [ ] Every AC is testable by a single test and contains no banned vague words.
- [ ] Allowed paths exclude the protected paths in `CLAUDE.md` §5.2 (O2).
- [ ] `spec.md` ends with `STATUS: AWAITING APPROVAL`.

## Failure handling

- **The feature conflicts with an architecture rule** (e.g. "reports should update task status"):
  do not plan around it silently. Record it as a blocking `Q-n` and propose a compliant
  alternative, e.g. "reports emits an event; activities reacts".
- **The prompt is too vague to produce testable ACs:** produce the best spec you can, and list
  the gaps as `Q-n` items so the developer resolves them before typing `APPROVED`.
