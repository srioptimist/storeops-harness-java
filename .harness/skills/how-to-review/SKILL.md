# Skill: how-to-review

**Purpose.** The mechanics of a StoreOps review: how to run and parse the gates, how to read the
diff, where violations hide, and how to write findings the Generator can fix without asking a
human. Read by the **Evaluator** only. *What* to check is in `grading-criteria`. This file is
*how* to check it.

---

## HR-1 Preparation (≤ 5 minutes of context)

1. From the contract, list the AC ids, the error cases, the allowed paths and the `verify:` type
   of each AC.
2. From the Generator summary, list its claims: AC → file:line → test.
3. Treat claims as **hypotheses to check**, never as evidence. The Generator's explanations
   aren't evidence either.

## HR-2 Run the gate

```
./mvnw -B clean test > .harness/output/sprint-<N>-checks.log 2>&1 ; echo "GATE_EXIT_CODE=$?" >> .harness/output/sprint-<N>-checks.log
```

On Windows PowerShell:

```
.\mvnw.cmd -B clean test *> .harness\output\sprint-<N>-checks.log; "GATE_EXIT_CODE=$LASTEXITCODE" | Add-Content .harness\output\sprint-<N>-checks.log
```

- If the build hasn't finished within 10 minutes, kill it. All tool gates are then `NOT_RUN`.
- Maven stops at the first failing module. In the *Reactor Summary*, modules marked `SKIPPED` had
  their gates `NOT_RUN`.

## HR-3 Log-parsing cookbook (gate → pattern → where the file:line is)

| Gate | Look for | Extract the evidence from |
|---|---|---|
| `HG-COMPILE` | `COMPILATION ERROR` / `maven-compiler-plugin` … `FAILURE` | `[ERROR] /…/File.java:[<line>,<col>] <message>` |
| `HG-CHECKSTYLE` | `You have <n> Checkstyle violations` with n > 0 | `[ERROR] <path>:<line>[:<col>]: <message> [<CheckName>]`. A `RegexpSingleline` hit with "Raw exception throw" is a rule AP-5 violation |
| `HG-SPOTBUGS` | `BugInstance size is <n>` with n > 0 | `[ERROR] <Priority>: <description> [<class>] At <File>.java:[line <n>] <PATTERN>` |
| `HG-ARCH` | A failure in `ArchitectureRulesTest` | `Architecture Violation … Rule '<rule text>' was violated (<n> times):` then lines ending `in (<File>.java:<line>)`. Map the rule to an AP id: boundary → AP-1, cycles → AP-2, alerts/reports → AP-4/AP-7, layers → AP-6, raw throws → AP-5 |
| `HG-TESTS` | `Tests run: <a>, Failures: <x>, Errors: <y>` with x + y > 0 | `[ERROR]   <TestClass>.<method>:<line> <message>`. Full detail is in `src/<module>/target/surefire-reports/<TestClass>.txt` |
| `HG-COVERAGE` | `Rule violated for (bundle\|package) <name>: lines covered ratio is <r>, but expected minimum is <m>` | Name the package, then the classes with the most `LINE_MISSED` in `src/<module>/target/site/jacoco/jacoco.csv` |

## HR-4 Git-based gates (exact commands)

```
git diff --name-only HEAD                       # tracked changes in this sprint
git ls-files --others --exclude-standard        # new files in this sprint
git diff --name-only <base-sha>                 # cumulative, for HG-PROTECTED
git diff HEAD -U0 | grep '^+'                   # added lines, for HG-SUPPRESS (new files: read them whole)
grep -rhoE '@DisplayName\("AC-[0-9]+\.[0-9]+' src/*/src/test   # for HG-AC-TRACE
```

- **HG-PROTECTED** matches: `^CLAUDE\.md$`, `^\.harness/(agents|skills)/`, `^config/`,
  `^pom\.xml$`, `^src/app/src/test/java/com/storeops/architecture/`.
- **HG-SCOPE** compares every changed path against the contract's *Allowed paths* globs.
  `.harness/output/**` is ignored, since the agents write there.

## HR-5 Reading the diff

- `git diff HEAD --stat` first. Then read **new files in full** and **modified files hunk by
  hunk** (`git diff HEAD -- <path>`).
- Review in risk order: events and module boundaries → AppError usage → test assertions → layer
  placement → AC behaviour → conventions.
- For each AC, trace WHEN → controller → service → repository / emit, then the tagged test.
  The check passes only if both the path and the assertion exist.

## HR-6 StoreOps red-flag searches (fast and cheap; each hit must be read in context)

| Search (in the sprint's changed files) | Suggests |
|---|---|
| `import com\.storeops\.(\w+)\.(repository\|service\|routes\|events)\.` in a file of a **different** module | A1 / AP-1 (ArchUnit normally catches it; if it didn't, check whether the gate ran) |
| `ApplicationEventPublisher\|SpringEventBus` outside `shared/events` | A6 |
| `alertService\|AlertService\|ReportService` referenced outside their own module | A1, FM4 |
| In an `api` interface diff: a method named `save\|update\|create\|delete\|add\|set` | A2 |
| `if (\|for (\|stream()` inside a `routes` controller method | A3 |
| `eventBus.emit` appearing **before** `repository.save` in the same method | A5 |
| A test method whose only matcher is `status()` (no `jsonPath`, `assertThat`, `verify`) | T1, FM3 |
| `Thread.sleep\|Instant.now()` in test code asserting time; `@SpringBootTest` without `@DirtiesContext` | T5 |
| Removed lines (`^-`) in a pre-existing test file containing `andExpect\|assertThat\|verify` | C5 |
| `catch (` in routes or services | AP-5 (errors must reach `GlobalExceptionHandler`), C2 |

## HR-7 Review budget

- If the sprint diff exceeds **1500 changed lines**, or a changed file can't be read in full,
  review in HR-5 risk order until the budget is reached.
- Every check not reviewed by then fails with evidence `REVIEW_BUDGET_EXCEEDED`, and you add a
  finding recommending a smaller sprint (signal for `sprint-decomposition`).

## HR-8 Writing findings

- **One finding per root cause.** If a missing `@DirtiesContext` fails three tests, write one
  finding.
- **Every finding has four lines:**
  - *Where* `path:line`;
  - *Rule*: the check id plus its AP/HT id and a one-line statement;
  - *Problem*: what the code does, in one sentence;
  - *Required fix*: an imperative instruction specific enough to apply without asking, e.g.
    "Move the `eventBus.emit(...)` call at `ActivityService.java:131` below
    `repository.save(updated)` at line 134".
- The `blocking` flag of a finding equals the blocking flag of its check, or is `true` for a
  hard gate.
- List blocking findings first, at most 15 findings in all. No praise, no style opinions outside
  the checklist, no restating the code.

## HR-9 Consistency rules

- **The same evidence gives the same result.** Decide each check only from the evidence you cite.
- **Each iteration is reviewed from scratch.** Don't carry over the previous iteration's verdict
  or scores; only use its findings list to confirm whether each one was fixed (C4 / *Response
  to previous feedback*).
- **Don't fix code, don't suggest scope beyond the contract, and don't relax a check because the
  Generator explained why it failed.**
