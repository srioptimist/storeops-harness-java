# Skill: grading-criteria

**Purpose.** The StoreOps evaluation framework: dimensions, weights, hard gates, and a checklist of
binary checks. Together they let the Evaluator turn variable Generator output into the same
verdict every time for the same evidence. Read by the **Evaluator** only. The verdict algorithm
itself is in `evaluator.agent.md` §5.

---

## GC-1 Dimensions and weights (sum = 100%)

| Dim | Name | Weight | Why it carries this weight for StoreOps | Hard gates (automated) |
|---|---|---|---|---|
| D1 | Architecture compliance | **35%** | FM1 and FM4 (boundary bypass, missing event bus) were the standards team's main objection to AI code. A violation compounds across the squad's 8 developers | `HG-ARCH`, `HG-PROTECTED`, `HG-SUPPRESS`, `HG-SCOPE` |
| D2 | Contract correctness | **30%** | The sprint contract is the developer-approved intent. Behaviour that doesn't match its ACs and error codes is wrong however clean it is | `HG-COMPILE`, `HG-TESTS` |
| D3 | Test quality | **25%** | FM3: status-only tests let business-rule regressions reach `main` unnoticed | `HG-COVERAGE`, `HG-AC-TRACE` |
| D4 | Code quality and conventions | **10%** | Mostly automated already, so the review only adds what the tools can't see | `HG-CHECKSTYLE`, `HG-SPOTBUGS` |

`HG-BUILD` (gate command exit code ≠ 0) spans all dimensions. **Any failed or `NOT_RUN` hard
gate means FAIL**, whatever the scores.

## GC-2 Checklist

Every check is **pass** or **fail** with cited evidence (`file:line` or a log line). `Blocking =
yes` means a fail produces a FAIL verdict even if the score is ≥ 85. The *N/A when* column is the
**only** condition under which a check is not applicable. N/A checks are removed from the
dimension's denominator. A dimension whose checks are all N/A scores its full weight.

### D1 — Architecture compliance (35%)

| Id | Check — passes when… | Blocking | N/A when |
|---|---|---|---|
| A1 | Every cross-module side effect in the diff is done by `eventBus.emit(<record in com.storeops.shared.events>)`. No module calls another module's write method, directly or through an `api` interface | yes | The contract has no `event-test` AC **and** the diff writes only to the owning module's repository |
| A2 | Every method added to an `api` interface (`StaffDirectory`, `ProgrammeDirectory`, `ActivityDirectory`, or a new one) is a query (`find*`, `exists*`, `list*`) and its implementation changes no state | yes | No `api` interface changed |
| A3 | Every new or changed controller method only maps request → command, calls **one** service method and returns. No `if` or loop on domain data, no repository access | yes | No `routes` class changed |
| A4 | Every new event is a `record` in `com.storeops.shared.events` implementing `DomainEvent`, with only `String`, primitive, `Instant` or `LocalDate` fields (enums passed as `name()`) | no | No event added |
| A5 | Events are emitted **after** the repository save. Listeners are `@Component`s in `<module>.events` that call only their own module's service and don't throw on expected business conditions | no | No emit and no listener added or changed |
| A6 | No class outside `com.storeops.shared.events` injects `ApplicationEventPublisher` or `SpringEventBus`. `reports` writes only to `ReportRepository` | yes | never |

### D2 — Contract correctness (30%)

| Id | Check — passes when… | Blocking | N/A when |
|---|---|---|---|
| C1 | For **every** AC, the GIVEN/WHEN/THEN outcome is implemented as written, with exact status codes, field names and values. One AC failing fails the check | yes | never |
| C2 | Every error case in the contract is raised as the specified `AppError` subclass with the specified `code` and `statusCode`. No other error type is used for it | yes | The contract lists no error cases |
| C3 | Auth and scoping match the contract: authenticated endpoints declare `AuthenticatedUser`, data is filtered by `caller.storeId()`, another store's resource gives `NOT_FOUND`, and role rules give `FORBIDDEN` | yes | The sprint adds no endpoint or service operation taking a caller |
| C4 | The Generator summary is accurate: every `MET` row points to an existing `file:line` and an existing AC-tagged test, and the *Files changed* table equals the actual changed-file set | yes | never |
| C5 | Existing behaviour is preserved: no assertion removed or weakened in pre-existing test files, and the base endpoints in `app-context` §3 are unchanged unless an AC says otherwise | yes | never |
| C6 | Request validation rules in the contract are expressed as Bean Validation annotations on the request record, or as a service `ValidationError`, with the stated limits | no | The contract states no validation rules |
| C7 | Response JSON field names and nesting match the contract's API section exactly | no | The sprint changes no response shape |

### D3 — Test quality (25%)

| Id | Check — passes when… | Blocking | N/A when |
|---|---|---|---|
| T1 | Every AC-tagged test asserts a **business outcome** (response field, stored state, captured event payload, or error `code`), not only `status()` | yes | never |
| T2 | Every `event-test` AC captures the emitted event (`ArgumentCaptor`) and asserts its payload fields. Failure paths assert `verify(eventBus, never()).emit(...)` | no | The contract has no `event-test` AC |
| T3 | Every contract error code has at least one test asserting that `code` | no | The contract lists no error cases |
| T4 | Where applicable: a missing token is tested to give 401, another store to give 404, and a forbidden role to give 403 | no | C3 is N/A |
| T5 | Tests are deterministic: no `Thread.sleep`, time-based logic uses a fixed `Clock`, and integration tests use `@DirtiesContext` | no | never |

### D4 — Code quality and conventions (10%)

| Id | Check — passes when… | Blocking | N/A when |
|---|---|---|---|
| Q1 | New classes sit in the right package (`api`, `model`, `repository`, `service`, `routes`, `events`) with the StoreOps names: `<Module>Controller`, `<Verb><Noun>Request`, `<Module>Service`, `<Entity>Repository` / `InMemory<Entity>Repository`, `<Subject><PastTense>Event` | no | never |
| Q2 | Models, commands, requests and events are records; collections are copied in compact constructors; lookups return `Optional` | no | No such type added |
| Q3 | Constructor injection only (no field `@Autowired`). Dependencies on other modules use `api` interfaces. Time-dependent code takes a `Clock` | no | never |
| Q4 | No `TODO`/`FIXME`, commented-out code or unused classes. Each new public type has a one-line Javadoc | no | never |

## GC-3 Scoring

The formula below is implemented in `.harness/tools/HarnessGates.java` (`computeVerdict`). The
Evaluator never calculates it by hand. `HarnessGates.java selftest` does three things:
- It parses the GC-1 and GC-2 tables of **this file** and fails if the weights, check ids,
  dimensions, blocking flags or N/A permissions differ from the tool's registry.
- It re-runs the GC-4 worked examples.
- It recomputes the same inputs 200 times to prove the output is byte-identical.

**Change this file and the tool together.**

```
dimension_score(D) = weight(D) × passed(D) / applicable(D)        (full weight if applicable(D) = 0)
score              = floor( Σ dimension_score )                    (never round up)
```

| Outcome | Rule (evaluated top to bottom; first match wins) |
|---|---|
| **FAIL** | any hard gate FAIL / NOT_RUN |
| **FAIL** | any blocking check failed |
| **PASS** | score ≥ 85 |
| **CONDITIONAL_PASS** | 70 ≤ score < 85. The failed non-blocking checks become *Conditions* |
| **FAIL** | score < 70 |

## GC-4 Worked example (shows the determinism)

The Generator delivers the bulk-status endpoint.
- The gate command exits 0, so all 11 hard gates PASS.
- Review results:
  - **T5 fails:** the integration test lacks `@DirtiesContext` (`BulkStatusIntegrationTest.java:21`).
  - **Q4 fails:** a `// TODO audit retention` at `ActivityService.java:142`.
  - **A2 is N/A:** no `api` change.
  - **C6 is N/A:** the contract states no validation rule. (This is hypothetical; a real
    contract would state one.)
  - Every other check passes.
- Scores:
  - D1 = 35 × 5/5 = 35
  - D2 = 30 × 6/6 = 30
  - D3 = 25 × 4/5 = 20
  - D4 = 10 × 3/4 = 7.5
  - Total = floor(92.5) = **92**
- No blocking check failed and 92 ≥ 85, so the verdict is **PASS**. T5 and Q4 are still listed as
  non-blocking findings.

Same delivery, but the routes test for AC-1.2 asserts only `status().isOk()`: **T1 fails**, which
is blocking, so the verdict is **FAIL**, even though the score (D3 = 25 × 3/5 = 15, total 87) is
≥ 85.

## GC-5 Ambiguity fallback

- **No evidence means a fail.** If the Evaluator can't cite a `file:line` or log line proving a
  check passes, the check fails with evidence `NO_EVIDENCE`.
- **Over budget means a fail.** If the diff exceeds the review budget (`how-to-review` HR-7),
  every check not yet reviewed fails with evidence `REVIEW_BUDGET_EXCEEDED`.
- **No partial credit**, no "mostly passes", no verdicts outside PASS / CONDITIONAL_PASS / FAIL.
- **The tool enforces the fallback per row.** In the checklist table, each of these counts as FAIL:
  - a missing row (`NO_EVIDENCE`);
  - `PASS` with an empty evidence cell;
  - `N/A` on a check whose *N/A when* is "never";
  - an unrecognised result;
  - conflicting duplicate rows.
- **Whole-checklist ambiguity.** If the checklist has no recognisable rows at all, routing returns
  `REEVALUATE` once, then FAIL `EVALUATOR_OUTPUT_AMBIGUOUS` (`CLAUDE.md` §5.2).
- **The header is advisory.** A feedback header that disagrees with the computed verdict is
  overridden, and the mismatch is recorded as evaluator drift.
