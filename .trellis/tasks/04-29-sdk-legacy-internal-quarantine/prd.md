# Quarantine or remove legacy internal holders

## Goal

Clean up internal compatibility holders left from the old public API:
`ResourceFactory`, `ResourceHandle`, and `LookupQuery`. They should either be
deleted if dead or clearly isolated as internal implementation details.

## Scope

* Confirm which legacy holders are still referenced.
* Remove dead methods/classes where no longer needed.
* If retained, make their package-private/internal status and naming obvious.
* Do not restore old public APIs.

## Acceptance Criteria

* [x] No public business API exposes `resource(...)`, `lookup(...)`,
  `PermissionResource`, or legacy handles.
* [x] Retained internal holders have minimal responsibility and clear Javadoc.
* [x] Tests and guide examples do not mention legacy entry points as public
  business API examples; package-internal compatibility tests still cover
  retained internal holders.
* [x] Old API scan passes.
* [x] `./gradlew test` passes.

## Notes

Do this after typed/dynamic unification so old holders are not removed before
their remaining responsibilities are understood.
