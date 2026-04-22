# Migration: Schema Flat Descriptors (2026-04)

## tl;dr

| Before | After |
|---|---|
| `client.on(Document.TYPE)` | `client.on(Document)` |
| `.to(User.TYPE, "alice")` | `.to(User, "alice")` |
| `.to(Group.TYPE, "eng", "member")` | `.to(Group, "eng", Group.Rel.MEMBER)` |
| `.toWildcard(User.TYPE)` | `.toWildcard(User)` |
| `checkAll(Document.Perm.class)` | `checkAll(Document.Perm)` |
| `.who("user", Document.Perm.EDIT)` | `.who(User, Document.Perm.EDIT)` |
| `client.batchCheck().add(Document.TYPE, ...)` | `client.batchCheck().add(Document, ...)` |
| `m.allowed(Document.TYPE + ":" + id, ...)` | `m.allowed(Document + ":" + id, ...)` |

Add one import per file that calls the SDK:

```java
import static com.your.app.schema.Schema.*;
```

## What changed

`AuthxCodegen` now emits a single `Schema.java` aggregator alongside the
per-type enum files. The aggregator contains:

- One `XxxDescriptor extends ResourceType<Rel, Perm>` per resource type.
- `public static final XxxDescriptor Xxx = new XxxDescriptor()` fields so
  `import static Schema.*` brings the descriptor names into scope.
- `XxxRelProxy` / `XxxPermProxy` nested classes exposing every enum
  constant as a `public final` field (`Document.Rel.EDITOR`,
  `Document.Perm.VIEW`, …). Proxy fields use **fully-qualified** enum
  references so the class-initialization order is safe.
- `XxxPermProxy implements PermissionProxy<Xxx.Perm>` so
  `checkAll(Document.Perm)` can recover the enum `Class` token at runtime.

The generated `Xxx.java` files no longer carry a `TYPE` constant —
descriptor lookup goes through `Schema.Xxx` instead. `ResourceTypes.java`
is deleted; the old string constants (`ResourceTypes.DOCUMENT`) were
redundant with the descriptor's `name()` method and every `ResourceType`
overrides `toString()` to return its name, so any `Document + ":" + id`
concatenation continues to work.

## Step by step

1. Pull and rebuild the SDK:
   ```bash
   ./gradlew build
   ```
2. Rerun `AuthxCodegen` against your SpiceDB: the generator overwrites
   `Xxx.java` (now slim), creates `Schema.java`, and removes any stale
   `ResourceTypes.java`. Delete any leftover hand-patches to `Xxx.java`
   — there is no longer a `TYPE` field to customize.
3. In every business file that uses the SDK, add:
   ```diff
   + import static com.your.app.schema.Schema.*;
   ```
4. Find-and-replace `Xxx.TYPE` with `Xxx`, for every generated type.
5. Find-and-replace `checkAll(Xxx.Perm.class)` with `checkAll(Xxx.Perm)`.
6. Where you had `.to(Group.TYPE, id, "member")`, optionally tighten to
   `.to(Group, id, Group.Rel.MEMBER)` — the string form still works and
   is useful when the sub-relation is dynamic.

## Caveat: `Xxx.Rel.class` in expression context

Inside a file with `import static Schema.*`, the bare name `Document`
resolves to the **field** (descriptor instance), which obscures the
`Document` enum container class in expression context per JLS §6.4.2.
So `Document.Rel` returns the `RelProxy` object — not the enum class.

That means `Document.Rel.VIEWER` still works (the proxy has a `VIEWER`
field), but `Document.Rel.valueOf("VIEWER")` does not (the proxy has no
`valueOf`). If you need the enum's `Class` token for reflection or
dynamic `valueOf`, use the fully-qualified name:

```java
var perm = com.your.app.schema.Document.Perm.valueOf(name);
Class<?> c = com.your.app.schema.Document.Rel.class;
```

Most code doesn't need this — pass the descriptor (`Document`) to the
SDK instead, and let the SDK recover the class token from it via
`ResourceType.permClass()` / `ResourceType.relClass()`.

## No runtime overhead

`Schema.Xxx` is a zero-state singleton that re-exposes already-existing
enum values. No reflection at the call site. The only cost is the
first-time class initialization, which happens once per JVM.

## Backward compatibility

The old call-path stays valid for a release — you can migrate one file
at a time:

| Path | Status |
|---|---|
| `client.on("document")` (raw string) | Still supported |
| `client.on(Document)` (descriptor) | Preferred |
| `.to(User, id, "member")` (string sub-rel) | Still supported |
| `.to(User, id, Group.Rel.MEMBER)` (typed sub-rel) | Preferred |
| `.checkAll(Document.Perm.class)` | Still supported |
| `.checkAll(Document.Perm)` (proxy) | Preferred |

The `TYPE` constant is **removed** from generated files — it was not a
public runtime API, so keeping a deprecated shim was out of scope. If
you had code that read `Document.TYPE` outside of SDK call sites
(e.g. for logging, or passing to a non-SDK function), replace with
`Document` (the descriptor) or `Document.name()` (the raw string).
