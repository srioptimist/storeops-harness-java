# Skill: how-to-test

**Purpose.** How to write StoreOps tests that prove **business rules**, not just HTTP plumbing,
and that meet the coverage gates. This skill exists because of failure mode FM3: in the earlier
AI experiment, tests asserted status codes and nothing else. Read by the **Generator** only.

---

## HT-1 Thresholds (JaCoCo, enforced per module by `./mvnw -B test`)

| Scope | Line coverage minimum |
|---|---|
| `com.storeops.*.service` | 80% |
| `com.storeops.*.routes` | 70% |
| Module overall (bundle) | 70% |

To find uncovered lines after a run, read `src/<module>/target/site/jacoco/jacoco.csv`
(columns `LINE_MISSED`, `LINE_COVERED` per class). The `app` module skips the coverage check;
the coverage of domain modules must come from their **own** tests.

## HT-2 Test placement and the four test types

| Type (contract `verify:`) | Location | Pattern |
|---|---|---|
| `service-test` | `src/<module>/src/test/java/com/storeops/<module>/service/<X>ServiceTest.java` | Plain JUnit 5. Real `InMemory<X>Repository`. Mockito `mock()` for `EventBus` and other modules' `api` interfaces. No Spring context |
| `routes-test` | `…/<module>/routes/<X>ControllerTest.java` | `@WebMvcTest(<X>Controller.class)` + `@Import({GlobalExceptionHandler.class, StaffWebConfig.class})` + `@MockitoBean` for the service **and** for `StaffDirectory` |
| `event-test` | Emission: the publisher's `<X>ServiceTest`. Reaction: the consumer's service test, calling the listener directly (precedent `AlertServiceTest`) | `ArgumentCaptor<EventType>` on `eventBus.emit`, or `new <Module>EventListener(service).on<Event>(new <Event>(...))` then assert the consumer's state |
| `integration-test` | `src/app/src/test/java/com/storeops/<Feature>IntegrationTest.java` | `@SpringBootTest @AutoConfigureMockMvc @DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)`, seeded dev tokens, real bus |

Each domain module's test root has a `<Module>TestApplication` (`@SpringBootApplication`) so
`@WebMvcTest` can bootstrap. Reuse it; don't add another.

## HT-3 AC tagging (`HG-AC-TRACE`, a hard gate)

Every AC gets **at least one** test whose `@DisplayName` **starts with the AC id**:

```java
@Test
@DisplayName("AC-1.3 bulk update over 50 ids returns VALIDATION_ERROR and changes nothing")
void rejectsOversizedBatch() throws Exception { ... }
```

The Evaluator greps for `@DisplayName("AC-<N>.<k>`. A missing tag fails the gate, even when the
behaviour is tested.

## HT-4 Assert the business outcome (FM3), not just the status

A test that checks only `status()` is a **blocking** finding. Every AC test asserts at least one
of the following:

| Outcome | How |
|---|---|
| Response content | `jsonPath("$.results[0].outcome").value("UPDATED")`, `jsonPath("$.code").value("FORBIDDEN")` |
| Stored state | Read it back: `service.get(id).status()`, or a follow-up `GET` in an integration test |
| Event emitted | `ArgumentCaptor<ActivityStatusChangedEvent> c = ArgumentCaptor.forClass(...); verify(eventBus).emit(c.capture()); assertThat(c.getValue().newStatus()).isEqualTo("DONE");` |
| Event **not** emitted | `verify(eventBus, never()).emit(any());` on failure paths |
| Error type and code | `assertThatThrownBy(...).isInstanceOf(NotFoundError.class).hasFieldOrPropertyWithValue("code", "NOT_FOUND")` |
| No side effect on failure | Re-read the state after a rejected call and assert it is unchanged |

```java
// ❌ FM3: proves nothing about the rule
mockMvc.perform(patch("/api/activities/bulk-status")...).andExpect(status().isOk());

// ✅ proves the partial-failure rule
mockMvc.perform(patch("/api/activities/bulk-status").header("Authorization", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"t1\",\"missing\"],\"status\":\"DONE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].outcome").value("UPDATED"))
        .andExpect(jsonPath("$.results[1].outcome").value("FAILED"))
        .andExpect(jsonPath("$.results[1].reason").value("NOT_FOUND"));
```

## HT-5 Required cases per new behaviour

For every new endpoint or service operation:
1. The success path, with its business outcome (HT-4).
2. Each `AppError` case listed in the contract, asserting the `code`.
3. Auth: in routes tests, no `Authorization` header gives 401 `UNAUTHORIZED` if the endpoint
   requires auth.
4. Store scoping: another store's data gives 404 `NOT_FOUND` (in service tests, use a second
   `AuthenticatedUser` with `store-002`).
5. A role restriction, if the contract defines one: 403 `FORBIDDEN`.
6. Event emitted on success and **not** emitted on failure.

## HT-6 Determinism

- No `Thread.sleep`, no real time comparisons. Time-based rules (SLA, grace periods) inject a
  `Clock`; tests use `Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)`.
- Don't assert on iteration order of maps or sets. Repositories sort explicitly, so tests may
  rely on that documented order.
- Each test builds its own data. Routes tests stub with `when(...)`. Integration tests use
  `@DirtiesContext` so in-memory state doesn't leak between tests.
- `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`), never the
  deprecated `@MockBean`, which `-Werror` rejects.

## HT-7 Don'ts

- Don't modify or weaken existing tests to make a change pass. The Evaluator compares the diff of
  every changed test file; removed assertions are a blocking finding.
- Don't touch `src/app/src/test/java/com/storeops/architecture/ArchitectureRulesTest.java`
  (protected path).
- Don't use `@Disabled`, assumptions that skip tests, or empty test bodies.
- Don't test private methods or bare record accessors. Test through the service or route API.
