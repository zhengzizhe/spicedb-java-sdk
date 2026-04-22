# Known Mistakes

<!-- Record anti-patterns and past mistakes here so Claude never repeats them. -->
<!-- Format: **What went wrong** — why it's wrong, what to do instead. -->

### Client-side decision cache on top of Zanzibar

**What went wrong:** The SDK shipped with a Caffeine L1 cache for `check()` results plus a Watch-stream invalidation subsystem. Two weeks of operation revealed that resource-key-based invalidation cannot correctly track SpiceDB inheritance (`permission view = parent->editor`): revoking `folder:f-1#editor` left `document:d-1#view` cache entries stale for up to ~10 s.

**Why it's wrong:** Client-side resource-key invalidation doesn't know the schema dependency graph. Workarounds (subject-keyed index over-invalidates; schema-aware reverse index is a half-copy of SpiceDB's state) each bring their own correctness or memory costs. Industry consensus (Zanzibar, OpenFGA, Cerbos, SpiceDB docs) is to cache decisions server-side only.

**What to do instead:** Let SpiceDB's server-side dispatch cache handle decision caching — it's schema-aware and inheritance-correct. On the client, use `Consistency.minimizeLatency()` to hit that cache. For write-after-read, use SESSION consistency via `DistributedTokenStore` (`sdk-redisson`). See ADR 2026-04-18.

### Parallel agents create nested package directories

**What went wrong:** When dispatching multiple parallel agents to write Java files into the same module (e.g., T006 + T008 of cluster-test), agents created nested directories like `correctness/correctness/C1.java` and `resilience/resilience/R2.java`. Both agents had to do follow-up cleanup commits.

**Why it's wrong:** Each agent is spawned in the project root but Write tool resolves relative paths from the agent's *current working directory*, which can drift. When the agent later edits or creates a "sibling" file, the path is computed relative to the previous file rather than the package root.

**What to do instead:** When briefing parallel agents, give **absolute file paths** for every Write target. Don't say "create files under cluster-test/src/main/java/com/authx/clustertest/correctness/" — say "create `/Users/.../cluster-test/src/main/java/com/authx/clustertest/correctness/C1DirectGrant.java`". Tell the agent to verify the directory layout with `find` or `tree` before committing.

### Parallel agent makes out-of-scope SDK changes

**What went wrong:** T009 (cluster-test stress + soak tests) needed `client.cache().stats()` — a method that didn't exist on `CacheHandle`. The agent silently added it to the SDK and left the change uncommitted, mixing it into the cluster-test commit.

**Why it's wrong:** Agents shouldn't modify code outside their stated scope without flagging it. Cross-module SDK enhancements need to be reviewed and committed separately so they show up in the SDK change log, not buried inside a benchmarking module commit.

**What to do instead:** When briefing an agent that *might* discover a missing API, explicitly say: "If you find the SDK is missing a method, STOP and report it — don't add it yourself." Then either expand the agent's scope explicitly or split the SDK enabler into its own task before retrying.

### Code-review agent findings are claims, not facts — verify before planning

**What went wrong:** In the 2026-04-16 review round, one agent claimed cache invalidation happened AFTER listener dispatch in `WatchCacheInvalidator.processResponse` (citing lines 610-624). The plan's SR:C2 required flipping the order. During execution, reading those exact lines showed the code already invalidates at 617-621 BEFORE enqueuing at 624 — the finding was simply wrong. The same agent also named two non-existent classes (`AuthxSchemaException`, `AuthxConstraintViolationException`) that looked plausible but weren't in the hierarchy.

**Why it's wrong:** Review agents hallucinate specifics — off-by-N line numbers, non-existent types, misread sequential code as concurrent. Acting on the narrative without opening the file produces plans that either fix a non-bug or can't compile.

**What to do instead:** Before writing a spec entry from a review finding, Read the cited file+lines. Grep for any claimed class name. If the claim doesn't survive verification, either reclassify the SR (current code already satisfies the invariant → regression test only, no impl) or drop it entirely. Never let a plan task reach execution with an unverified premise.

### `.to(bareId)` inference is silently absent on the batch chain

**What went wrong:** Wrote `client.batch().on(Space.TYPE, spaceId).grant(Space.Rel.MEMBER).to(userId)` expecting the same single-type inference that works on `client.on(Document.TYPE).select(id).grant(...).to(userId)`. The batch call crashed at runtime with `IllegalArgumentException: Invalid subject ref: u-ceo` from inside `SubjectRef.parse`.

**Why it's wrong:** The non-batch typed chain (`TypedGrantAction`) holds a `SchemaCache` reference and calls `SubjectType.inferSingleType(...)` when you pass a bare id. The batch chain (`CrossResourceBatchBuilder.GrantScope` etc.) has no schema-cache scope — its `to(String...)` overload goes straight to `SubjectRef.parse`, which requires `type:id` format. Same-looking API, different wiring.

**What to do instead:** On the batch chain always use the typed overloads — `.to(User.TYPE, id)`, `.to(Space.TYPE, id)`, `.toWildcard(User.TYPE)`, `.to(User.TYPE, iterableIds)`, symmetric `.from*` for revoke. Reserve bare-id `.to(userId)` inference for the non-batch typed chain, and only when the client was built with a populated `SchemaCache` (in tests: use `AuthxClient.inMemory(schemaCache)`, not the zero-arg `inMemory()`).

### Testing "Redis is down" with bad-from-start config

**What went wrong:** To test that `RedissonTokenStore.set/get` swallow errors when Redis is unreachable, the first attempt built a `RedissonClient` pointed at `127.0.0.1:1` with `retryAttempts=0`. `Redisson.create()` itself threw `RedisConnectionException` before any test code ran.

**Why it's wrong:** Redisson eagerly verifies the connection at `create()` when retries are disabled. A "broken from birth" client never reaches the SPI methods you want to test.

**What to do instead:** Start a real Redis container, build the client against it, prove `set/get` work once, then `container.stop()` mid-test and verify the SPI swallows errors. This is also a more honest simulation of "Redis went away during runtime."
