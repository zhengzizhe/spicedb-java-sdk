# Unify typed and dynamic fluent internals

## Goal

Reduce duplication between typed and dynamic API implementations while
preserving their public ergonomics. Typed and dynamic paths should share
internal conversion, validation, and terminal execution where practical.

## Scope

* Review `TypedHandle` vs `DynamicHandle`, `TypedFinder` vs `DynamicFinder`,
  and `TypedResourceEntry` vs `DynamicResourceEntry`.
* Extract internal common behavior only when it improves clarity and does not
  make generic signatures harder to understand.
* Keep typed call sites strongly typed.

## Acceptance Criteria

* [x] Shared internal behavior is extracted where duplication is meaningful.
* [x] Typed public methods remain readable and strongly typed.
* [x] Dynamic public methods remain straightforward for runtime strings.
* [x] Tests cover both typed and dynamic paths after refactor.
* [x] `./gradlew test` passes.

## Notes

Do this after conversion helpers and write planning exist, otherwise the shared
layer will become too broad.
