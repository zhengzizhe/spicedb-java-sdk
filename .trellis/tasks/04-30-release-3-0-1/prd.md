# Release 3.0.1

## Goal

Prepare and publish SDK version `3.0.1` with the post-3.0.0 improvements:
schema management API, typed caveat builders, documentation polish, and real
SpiceDB e2e coverage.

## Requirements

* Bump SDK version from `3.0.0` to `3.0.1`.
* Update README/GUIDE installation snippets to `3.0.1`.
* Move unreleased changelog entries under `3.0.1 - 2026-04-30`.
* Update release notes/checklist to reference `3.0.1`.
* Run release verification before publishing.
* Publish to Maven Central using existing local credentials/signing setup.

## Acceptance Criteria

* [x] `gradle.properties` uses `sdkVersion=3.0.1`.
* [x] Documentation snippets use `3.0.1`.
* [x] CHANGELOG has a `3.0.1 - 2026-04-30` section.
* [x] Full verification passes.
* [x] Maven Central publish command completes.

## Publish Result

`./gradlew publishAndReleaseToMavenCentral --no-daemon` completed successfully.
Deployment id: `58d39a4a-5948-41cf-8b9b-9a7ca3694d72`.

## Out of Scope

* Adding new runtime features.
* Reworking unrelated dirty workspace changes.
* Publishing any version other than `3.0.1`.

## Technical Notes

Relevant files:

* `gradle.properties`
* `README.md`
* `src/main/resources/META-INF/authx-sdk/GUIDE.md`
* `CHANGELOG.md`
* `RELEASE.md`
