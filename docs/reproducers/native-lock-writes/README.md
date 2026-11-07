# Native parallel lock persistence investigation

Status: unresolved compatibility blocker, observed on Gradle 9.2.1 and 9.7.1
with JDK 21. A successful isolated parallel build intermittently leaves one
project's `gradle.lockfile` absent. The same symptom occurs without applying this
plugin. This is evidence of a native Gradle interaction; its exact cause and an
upstream fix have not been established.

This fixture uses only the Java plugin, native dependency locking, and a generated
local Maven repository. It does not apply or load this project's plugin.
Preparing the fixture requires a JDK with `jar` on `PATH`:

```shell
sh docs/reproducers/native-lock-writes/prepare.sh
```

The script prints a new temporary directory. From this repository, use that
directory with the wrapper (9.7.1):

```shell
./gradlew -p /absolute/path/printed/by/prepare \
  :libraries:lib:dependencies :tools:lib:dependencies \
  --write-locks --parallel --isolated-projects \
  --configuration-cache --configuration-cache-problems=fail --no-watch-fs
```

For Gradle 9.2.1, run that Gradle distribution against the same fixture and use
`-Dorg.gradle.unsafe.isolated-projects=true` in place of `--isolated-projects`.
Check that **both** files exist after each successful write:

```text
libraries/lib/gradle.lockfile
tools/lib/gradle.lockfile
```

Repeat with fresh fixtures. Successful attempts and test-runner retries are not
proof of reliable persistence. The original TestKit probe also reproduced the
failure when deleting the two fixture lock files between repeated writes. Native
write/update options prevent configuration-cache reuse; stable read-only builds
must establish reuse separately.

In failing output, a `Persisted dependency lock state` line can appear for only
one project even though both dependency reports succeed. No project lock file
should be inferred from the overall build exit status.

The standalone command above reproduced this on 9.7.1 / JDK 21 on its first run:
only `libraries/lib/gradle.lockfile` existed after `BUILD SUCCESSFUL`, while the
tools report also resolved both modules. In contrast, all six exploratory
TestKit core cases passed without retries in the latest full build. Both results
are retained: one passing suite does not close this intermittent persistence gate.

The project-local core configuration timing adjustment did not eliminate this
symptom. Do not work around it with eager resolution, internal Gradle APIs,
manual native-file writing, or relaxed isolation/cache settings. Native write
compatibility remains a release gate in the specification until resolved or
explicitly moved to a separate scope.
