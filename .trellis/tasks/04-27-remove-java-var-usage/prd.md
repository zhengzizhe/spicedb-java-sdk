# Remove Java var Usage from SDK

## Goal

Remove Java local variable type inference (`var`) from SDK Java source and test code so all local variables use explicit declared types.

## Requirements

- Replace every Java local variable declaration using `var` under SDK code paths with an explicit type.
- Cover main source, test source, optional modules, and example app Java files:
  - `src/**/*.java`
  - `sdk-redisson/**/*.java`
  - `test-app/**/*.java`
- Preserve behavior exactly; this is a mechanical readability/style refactor.
- Do not modify unrelated dirty worktree changes or revert existing deletions.
- Avoid changing documentation snippets unless they are embedded in Java source and required for compilation style consistency.

## Acceptance Criteria

- [x] `rg -n '\bvar\s+[A-Za-z_$]' src sdk-redisson test-app --glob '*.java'` returns no matches for Java local variable declarations.
- [x] Java compilation passes.
- [x] Relevant tests pass, or any skipped tests are reported with reason.

## Definition of Done

- Explicit Java types are used in place of `var`.
- No behavior changes are introduced.
- Trellis check has reviewed the change.

## Out of Scope

- Renaming variables.
- Reformatting unrelated code.
- Changing APIs or behavior.
- Editing non-Java documentation examples.

## Technical Notes

- The repository uses Java 21, but the requested style forbids `var`.
- This is a broad mechanical change, so implementation should prefer compiler-guided fixes and targeted validation.
