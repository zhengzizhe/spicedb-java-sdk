# Write Terminal Listener API

**Date**: 2026-04-18
**Status**: Draft
**Scope**: Typed grant / revoke chain terminal methods only

## Background

Today the terminal write methods on the typed fluent chain return `void`:

```java
doc.select("d1").grant(R.EDITOR).toUser("bob");   // void
```

Callers who want to react to the completion of a write (log the
`zedToken`, push an audit event, increment a counter) must write the side
effect as a separate statement:

```java
doc.select("d1").grant(R.EDITOR).toUser("bob");
audit.log("granted editor to bob");    // separate statement
```

This works but decouples the reaction from the call site, and for heavy
side effects there's no in-SDK mechanism to offload the work to a
background executor without every caller wrapping their own
`CompletableFuture.runAsync(...)` boilerplate.

We want the typed chain to natively support appending a completion
listener, with explicit sync vs. async execution of the listener body.

## Goals

- Allow callers to attach one or more completion listeners to a typed
  write via the fluent chain:
  `... .toUser("bob").listener(cb)` / `.listenerAsync(cb, executor)`.
- Two distinct methods distinguished by name:
  - `listener(cb)` — runs the callback synchronously on the current thread
    before returning.
  - `listenerAsync(cb, executor)` — dispatches the callback to the
    provided executor and returns immediately.
- Multiple listeners on one chain are supported.
- Zero breakage for existing callers using the terminal methods in
  statement form.

## Non-goals

- **Async writes.** The write itself remains synchronous; the SpiceDB RPC
  still blocks the calling thread until ack. Only the listener's
  execution mode is configurable.
- **`CompletableFuture`-based API.** No `*Async` write variants returning
  `CompletableFuture<GrantResult>`. Deferred to a separate proposal.
- **Netty-style `WriteListener` interface.** Use
  `java.util.function.Consumer<GrantResult>` /
  `Consumer<RevokeResult>` directly; no new functional interface.
- **`onFailure` listener.** Write failures continue to propagate as
  `AuthxException` from the terminal call (before any listener can fire).
  No separate failure-listener channel in this change.
- **Untyped chain** (`com.authx.sdk.action.GrantAction.toSubjects(...)`
  and `RevokeAction.fromSubjects(...)`) — these already return
  `GrantResult` / `RevokeResult` non-void; leaving them as-is preserves
  their existing semantics. Users on the untyped surface can write
  listeners trivially with a normal statement.
- **Check / Lookup / Expand / Read / Schema chains.** Unchanged.
- **Batch builders** (`CrossResourceBatchBuilder`, `BatchGrantAction`,
  `BatchRevokeAction`). Follow-up spec if needed.

## Requirements

Each requirement has an ID (`req-N`) so that tasks.md can trace coverage.

### Public types

**req-1** Introduce public interface `com.authx.sdk.action.GrantCompletion`
with three methods: `result()`, `listener(...)`, `listenerAsync(...)`. The
interface is returned by every typed grant terminal method
(see req-3).

**req-2** Introduce public interface `com.authx.sdk.action.RevokeCompletion`
with symmetrical methods over `RevokeResult`.

### Typed chain terminal return types

**req-3** Every terminal method on `TypedGrantAction` that today returns
`void` changes to return `GrantCompletion` (non-breaking for
statement-form callers — see req-12). Covered terminals:

- `toUser(String...)`
- `toUser(Collection<String>)`
- `toGroupMember(String...)`
- `toGroupMember(Collection<String>)`
- `toUserAll()`
- `to(SubjectRef...)`
- `to(Collection<SubjectRef>)`
- `toSubjectRefs(String...)`
- `toSubjectRefs(Collection<String>)`

**req-4** Every terminal method on `TypedRevokeAction` that today returns
`void` changes to return `RevokeCompletion`. Mirrored set:
`fromUser(String...)`, `fromUser(Collection<String>)`,
`fromGroupMember(...)`, `from(SubjectRef...)`,
`from(Collection<SubjectRef>)`, `fromSubjectRefs(...)`.

**req-5** A typed chain call may span multiple internal SpiceDB RPCs
(the nested `for id × rel × subjects` loop inside
`TypedGrantAction#write`). `GrantCompletion#result()` returns a single
aggregated `GrantResult` with:

- `zedToken` = the token from the **last** internal write (SpiceDB's
  monotonically-increasing revision, so the latest covers all prior writes
  for consistency purposes).
- `count` = the **sum** of counts across all internal writes.

If all internal writes succeed but produced no tokens (in-memory
transport), `zedToken` is `null`.

**req-6** `RevokeCompletion#result()` aggregates in the same way for
`RevokeResult`.

### Listener registration

**req-7** `GrantCompletion#listener(Consumer<GrantResult> callback)`:

- Invokes `callback.accept(result())` on the calling thread, synchronously,
  before returning.
- Returns `this`.
- A `null` callback throws `NullPointerException` with message
  `"callback"`.

**req-8** `GrantCompletion#listenerAsync(Consumer<GrantResult> callback,
Executor executor)`:

- Submits a `Runnable` that calls `callback.accept(result())` to
  `executor`.
- Returns `this` immediately; does not wait for the task to complete.
- A `null` callback or `null` executor throws `NullPointerException` with
  message `"callback"` / `"executor"`.
- If `executor.execute(...)` throws `RejectedExecutionException`, the
  rejection propagates unchanged to the caller of `listenerAsync`.

**req-9** Symmetrical rules (req-7, req-8) apply to
`RevokeCompletion#listener(...)` and
`RevokeCompletion#listenerAsync(...)`.

**req-10** Multiple listeners can be chained on the same completion.
Sync listeners run in registration (chain) order. Async listener
submission order matches chain order; actual task execution order is
governed by the supplied executor.

**req-11** Exception behavior:

- A sync listener that throws a `RuntimeException` propagates the
  exception to the caller of `listener(...)`. The handle remains valid;
  the caller may re-invoke `listener(...)` / `listenerAsync(...)` on it
  in a later statement. Within one chained expression, the throw
  naturally skips any `.listener(...)` calls that follow it (standard
  Java statement semantics).
- An exception thrown inside an async listener task is caught by the SDK,
  logged at `System.Logger.Level.WARNING` under logger name
  `com.authx.sdk.action.GrantCompletion`, with the listener's source
  class name when derivable, and otherwise swallowed — it does NOT reach
  the caller, does NOT affect the write outcome, and does NOT cancel
  other already-submitted async listeners.

### Failure semantics

**req-12** If the underlying write fails (any `AuthxException` subclass),
the terminal method (e.g. `toUser`) throws the exception AS TODAY. No
`GrantCompletion` is returned. Any `.listener(...)` / `.listenerAsync(...)`
calls chained AFTER the terminal in the same statement are therefore
never reached.

**req-13** The exception rethrown in req-12 is the exact same type and
message produced today; no wrapping, no new exception type introduced.

### Backward compatibility

**req-14** Existing callers that use terminal methods in statement form
continue to compile and run unchanged:

```java
doc.select("d1").grant(R.EDITOR).toUser("bob");   // still OK
```

This works because Java allows ignoring a non-`void` return value.

**req-15** No changes to `AuthxClient`, `ResourceFactory`,
`ResourceHandle`, `GrantAction`, `RevokeAction`, batch builders, or any
class outside of `TypedGrantAction` / `TypedRevokeAction` and the two new
completion interfaces.

## Acceptance Tests

Each requirement MUST have a test that directly verifies it. Tests live
under `src/test/java/com/authx/sdk/action/`.

| Test class . method | Requirement | What it asserts |
|---|---|---|
| `GrantCompletionTest.listener_runsInline` | req-7 | `Thread.currentThread()` observed in the callback equals the test thread; the assertion after `.listener(...)` passes only if the listener already ran. |
| `GrantCompletionTest.listener_returnsThisForChaining` | req-7 | `handle.listener(cb1).listener(cb2)` fires both. |
| `GrantCompletionTest.listener_nullCallbackThrows` | req-7 | `NPE` with message `"callback"`. |
| `GrantCompletionTest.listenerAsync_dispatchesToExecutor` | req-8 | Callback thread != caller thread; `listenerAsync(...)` returns before callback finishes (verified with a `CountDownLatch` that the test releases). |
| `GrantCompletionTest.listenerAsync_nullCallbackOrExecutorThrows` | req-8 | Both null-parameter cases. |
| `GrantCompletionTest.listenerAsync_rejectionPropagates` | req-8 | Saturated executor's `RejectedExecutionException` reaches the caller. |
| `GrantCompletionTest.multipleListenersRunInOrder` | req-10 | Ordering for sync listeners. |
| `GrantCompletionTest.syncListenerExceptionDoesNotInvalidateHandle` | req-11 | Caller catches the first listener's RuntimeException; then registers another listener on the same handle; second listener fires. |
| `GrantCompletionTest.asyncListenerExceptionIsSwallowedAndLogged` | req-11 | Async listener throws; caller is unaffected; log capture asserts a WARNING. |
| `GrantCompletionTest.resultAggregatesAcrossInternalWrites` | req-5 | A chain touching 2 resources × 2 relations × 2 subjects produces a single `GrantResult` with `count == 8` (or equivalent for the InMemoryTransport's granularity) and a non-null `zedToken` equal to the last internal write's token. |
| `GrantCompletionTest.writeFailureThrowsBeforeListenerRegistration` | req-12, req-13 | Write that fails throws the canonical `AuthxException`; a `.listener(...)` chained after the terminal is unreachable (verified by pre-flight flag not set). |
| `RevokeCompletionTest.*` (mirror of above) | req-2, req-6, req-9 | Symmetry on revoke. |
| `GrantCompletionTest.statementFormStillCompiles` | req-14 | Compiles and runs; the terminal call produces no visible side effect beyond the write itself. |

## File-level change list (informative, not normative)

- NEW: `src/main/java/com/authx/sdk/action/GrantCompletion.java`
- NEW: `src/main/java/com/authx/sdk/action/RevokeCompletion.java`
- NEW: internal package-private impl class(es) for the two completions
  (one each; the aggregated `GrantResult` / `RevokeResult` is stored as a
  final field).
- MODIFIED: `src/main/java/com/authx/sdk/TypedGrantAction.java` — change
  terminal method return types and have `write(...)` build and return a
  `GrantCompletion`.
- MODIFIED: `src/main/java/com/authx/sdk/TypedRevokeAction.java` —
  symmetrical.
- NEW: `src/test/java/com/authx/sdk/action/GrantCompletionTest.java`
- NEW: `src/test/java/com/authx/sdk/action/RevokeCompletionTest.java`

## Migration & Release Notes

- No downstream code changes required.
- README "Fluent API" section gains a small "Listeners" subsection with
  one sync example and one async example.
- Changelog entry noting the new `GrantCompletion` / `RevokeCompletion`
  types as **additive** API surface.

## Open Questions

None. Design is locked per the brainstorming dialogue on 2026-04-18.
