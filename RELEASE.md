# Release Checklist

## Release 3.0.1

Version `3.0.1` includes schema management, typed caveat builders, and
documentation polish after the `3.0.0` release.

Watch/change-stream support is intentionally not part of the next release
unless a separate reliability design is accepted. Do not expose a stable
`client.watch(...)` API as a release shortcut.

## Release Steps

1. Verify the version in `gradle.properties`.
2. Verify installation snippets in `README.md` and
   `src/main/resources/META-INF/authx-sdk/GUIDE.md`.
3. Run the full verification suite:

   ```bash
   ./gradlew clean test spicedbE2eTest
   git diff --check
   rg -n '\bvar\s+[A-Za-z_$]' src test-app --glob '*.java'
   ```

4. Build and publish locally. On a release machine with GPG configured:

   ```bash
   ./gradlew publishToMavenLocal
   ```

   On a machine without the release GPG key, validate unsigned local artifacts:

   ```bash
   ./gradlew publishToMavenLocal -PskipSigning=true
   ```

5. Publish to Maven Central staging:

   ```bash
   ./gradlew publishToMavenCentral
   ```

6. Inspect the Central Portal staging deployment, then release it manually.

The build is configured with `publishToMavenCentral(false)`, so remote publish
uploads a deployment for manual release instead of automatically making it
public.
