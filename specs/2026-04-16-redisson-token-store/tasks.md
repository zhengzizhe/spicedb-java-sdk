# Redisson Token Store Task Checklist

Reference: [`spec.md`](spec.md), [`plan.md`](plan.md)

## Phase 0: Setup
- [ ] T001 [SR:req-1] Scaffold `sdk-redisson/build.gradle` + add `include "sdk-redisson"` to `settings.gradle`

## Phase 1: TDD round (test before impl)
- [ ] T002 [SR:req-8][SR:t-1..t-5] Write `RedissonTokenStoreIT` with 5 cases and confirm it fails to compile — `sdk-redisson/src/test/java/com/authx/sdk/redisson/RedissonTokenStoreIT.java`

## Phase 2: Core implementation
- [ ] T003 [SR:req-2][SR:req-3][SR:req-4][SR:req-5][SR:req-6][SR:req-7] Implement `RedissonTokenStore`, run tests until all 5 pass, commit — `sdk-redisson/src/main/java/com/authx/sdk/redisson/RedissonTokenStore.java`

## Phase 3: Documentation
- [ ] T004 [SR:req-9] Write `sdk-redisson/README.md`
- [ ] T005 Register module in root `CLAUDE.md` Project structure section

## Phase 4: Verification
- [ ] T006 Run main SDK tests + full suite + sdk-redisson javadoc; confirm no regression and 804 passing

## Dependencies

```
T001 → T002 → T003 → T004 → T005 → T006
```

All tasks are sequential — no `[P]` parallelism. T002 must precede T003 (TDD).
T004 and T005 could run in parallel after T003, but they are small and ordering keeps history clean.

## Spec requirement traceability

| Req ID | Tasks |
|---|---|
| req-1 | T001 |
| req-2 | T003 |
| req-3 | T003 |
| req-4 | T003 |
| req-5 | T003, T002 (t-4) |
| req-6 | T003, T002 (t-2, t-3) |
| req-7 | T003 |
| req-8 | T002 |
| req-9 | T004 |
| t-1 | T002 |
| t-2 | T002 |
| t-3 | T002 |
| t-4 | T002 |
| t-5 | T002 |
