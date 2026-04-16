# Known Mistakes

<!-- Record anti-patterns and past mistakes here so Claude never repeats them. -->
<!-- Format: **What went wrong** — why it's wrong, what to do instead. -->

### Parallel agents create nested package directories

**What went wrong:** When dispatching multiple parallel agents to write Java files into the same module (e.g., T006 + T008 of cluster-test), agents created nested directories like `correctness/correctness/C1.java` and `resilience/resilience/R2.java`. Both agents had to do follow-up cleanup commits.

**Why it's wrong:** Each agent is spawned in the project root but Write tool resolves relative paths from the agent's *current working directory*, which can drift. When the agent later edits or creates a "sibling" file, the path is computed relative to the previous file rather than the package root.

**What to do instead:** When briefing parallel agents, give **absolute file paths** for every Write target. Don't say "create files under cluster-test/src/main/java/com/authx/clustertest/correctness/" — say "create `/Users/.../cluster-test/src/main/java/com/authx/clustertest/correctness/C1DirectGrant.java`". Tell the agent to verify the directory layout with `find` or `tree` before committing.

### Parallel agent makes out-of-scope SDK changes

**What went wrong:** T009 (cluster-test stress + soak tests) needed `client.cache().stats()` — a method that didn't exist on `CacheHandle`. The agent silently added it to the SDK and left the change uncommitted, mixing it into the cluster-test commit.

**Why it's wrong:** Agents shouldn't modify code outside their stated scope without flagging it. Cross-module SDK enhancements need to be reviewed and committed separately so they show up in the SDK change log, not buried inside a benchmarking module commit.

**What to do instead:** When briefing an agent that *might* discover a missing API, explicitly say: "If you find the SDK is missing a method, STOP and report it — don't add it yourself." Then either expand the agent's scope explicitly or split the SDK enabler into its own task before retrying.

### Testing "Redis is down" with bad-from-start config

**What went wrong:** To test that `RedissonTokenStore.set/get` swallow errors when Redis is unreachable, the first attempt built a `RedissonClient` pointed at `127.0.0.1:1` with `retryAttempts=0`. `Redisson.create()` itself threw `RedisConnectionException` before any test code ran.

**Why it's wrong:** Redisson eagerly verifies the connection at `create()` when retries are disabled. A "broken from birth" client never reaches the SPI methods you want to test.

**What to do instead:** Start a real Redis container, build the client against it, prove `set/get` work once, then `container.stop()` mid-test and verify the SPI swallows errors. This is also a more honest simulation of "Redis went away during runtime."
