# Native parallel lock persistence investigation

Status: a Gradle listener-registration race has been traced to missing native
lock files on Gradle 9.2.1 and 9.7.1 with JDK 21. A successful isolated parallel
build can omit a project's `gradle.lockfile` without applying this plugin.
The [investigation and upstream report draft](investigation.md) contains the
mechanism, measured controls, and a candidate Gradle-side patch experiment.
An upstream release containing a reviewed fix has not been established.

This remains a native core-locking compatibility blocker. The plugin continues
to reject the combination of its experimental flag with
`nebula.features.coreLockingSupport=true`.

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
tools report also resolved both modules. An earlier full workspace build included
six exploratory TestKit core cases that passed without retries. Those cases are
not part of the current JSON-locking acceptance suite. Both observations are
retained: one passing suite does not close this intermittent persistence gate.

## Repeat writes and preserve evidence

Use the verifier to check the actual persisted contents, rather than the build
exit status. It creates its own disposable fixtures and snapshots each attempt's
log and existing lock files before repeating:

```shell
sh docs/reproducers/native-lock-writes/verify.sh "$PWD/gradlew" 30 repeat \
  --parallel --isolated-projects \
  --configuration-cache --configuration-cache-problems=fail --no-watch-fs
```

The second argument is the attempt count. Use `fresh` instead of `repeat` to
prepare a new fixture for every write. Repeated mode removes only the two
generated lock files in its own fixture; project/cache state is retained.
All supplied Gradle options are recorded in the evidence directory. To select
the daemon JDK explicitly, add `-Dorg.gradle.java.home=/path/to/jdk-21`.

Exit codes are 0 for complete writes, 1 for successful builds with missing or
invalid locks, and 2 for build/invocation failures. A zero result in a finite
intermittent run is not proof of compatibility. `results.tsv` records all
attempts; `attempt-N` holds file snapshots and the build log. A lock is valid for
this fixture when it contains both `fixture:widget:1.1` and `fixture:other:1.1`.

For controls, omit `--isolated-projects` to test ordinary configuration caching,
or retain isolation and use `--no-parallel --max-workers=1` to test one worker.
These are investigation controls, not supported workarounds.

## Deterministic listener probe

With a JDK 17 or newer and an unpacked Gradle distribution:

```shell
sh docs/reproducers/native-lock-writes/check-listener.sh /path/to/gradle-9.7.1
```

This JVM-only probe deliberately holds Gradle's broadcaster lock while another
thread registers a `BuildModelLifecycleListener`. Registration finishes before
the first event. Stock 9.2.1 and 9.7.1 incorrectly omit that notification and
exit 1; the uncontended control receives it. A second event shows that the
listener was deferred, not absent. The actual model-discard notification is
guarded to fire once, so persistence has no second opportunity.

The probe uses reflection solely to force a diagnostic interleaving. The trace
below also inspects internal state. Neither is plugin implementation code or a
consumer workaround.

## Correlate the listener queue with missing files

Add the diagnostic init script to the write verifier:

```shell
sh docs/reproducers/native-lock-writes/verify.sh "$PWD/gradlew" 30 repeat \
  --parallel --isolated-projects \
  --configuration-cache --configuration-cache-problems=fail --no-watch-fs \
  --init-script "$PWD/docs/reproducers/native-lock-writes/listener-trace.init.gradle"
```

On an affected attempt, `NATIVE_LISTENER_TRACE` lists both projects as registered,
one as active, and the missing project's listener as deferred with `queued=1`.
The trace only reads the queue; it does not flush listeners or persist files.
The diagnostic field names target Gradle 9.2.1 and 9.7.1.

The project-local core configuration timing adjustment did not eliminate the
symptom. Keep plugin code free of eager-resolution, internal-API, manual native
file-writing, and relaxed-isolation workarounds. Native acceptance requires an
upstream fix and separate consume/write/update/cache-reuse validation before
removing the combined-flags rejection.
