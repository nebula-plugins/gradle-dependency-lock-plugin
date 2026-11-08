# Native lock files omitted after concurrent listener registration

This is an upstream report draft and the record for the first native-locking
investigation chunk. It has not been submitted upstream. No plugin runtime code
or supported behavior changes in this chunk.

## Reproduction

Environment: macOS 15.8, JDK 21.0.2, Gradle 9.2.1 and 9.7.1. The build uses only
the Java library plugin, native dependency locking, and generated local Maven
artifacts. No dependency-lock plugin is applied or loaded. Two nested projects
have distinct paths and the same leaf name: `:libraries:lib` and `:tools:lib`.

From this repository, with the wrapper on Gradle 9.7.1:

```shell
sh docs/reproducers/native-lock-writes/verify.sh "$PWD/gradlew" 30 repeat \
  -Dorg.gradle.java.home=/path/to/jdk-21 \
  --parallel --isolated-projects \
  --configuration-cache --configuration-cache-problems=fail --no-watch-fs
```

For 9.2.1, supply its `bin/gradle` and replace `--isolated-projects` with
`-Dorg.gradle.unsafe.isolated-projects=true`. See the [reproducer guide](README.md)
for fresh-fixture controls and preserved evidence.

Expected: every successful write persists both projects' lock files with both
resolved fixture modules. Observed: an intermittent successful write omits one
project's lock file and emits only one project persistence message.

## Listener race and persistence mechanism

The [Gradle 9.7.1 listener manager](https://github.com/gradle/gradle/blob/v9.7.1/platforms/core-runtime/messaging/src/main/java/org/gradle/internal/event/DefaultListenerManager.java)
uses `ExclusiveEventBroadcast.executeNowOrLater` to mutate listeners. If another
thread owns `broadcasterLock`, registration queues an operation and returns. The
lock can be owned by another registration, even though no event is dispatching.

`startDispatch` takes the listener snapshot without applying queued operations.
Those registrations become active only in `endDispatch`, after the first event.
Consequently, a listener whose registration already completed can miss that
first notification. The existing queue is also not cleared after application.

The [native provider registration](https://github.com/gradle/gradle/blob/v9.7.1/platforms/software/dependency-management/src/main/java/org/gradle/api/internal/artifacts/DefaultDependencyManagementServices.java)
persists lock state through a `BuildModelLifecycleListener.beforeModelDiscarded`
callback. The [build lifecycle controller](https://github.com/gradle/gradle/blob/v9.7.1/subprojects/core/src/main/java/org/gradle/internal/build/DefaultBuildLifecycleController.java)
guards that notification to fire once. If the persistence callback is deferred
past the first event, no later invocation writes that project's file.

The [JVM-only probe](ListenerRegistrationProbe.java) deterministically forces
registration contention against the actual distribution classes. Stock 9.2.1
and 9.7.1 produce:

```text
contended=false first_event_calls=1 second_event_total_calls=2
contended=true first_event_calls=0 second_event_total_calls=1
```

The contended listener has completed registration before the first event. The
second event is diagnostic only; normal lock persistence cannot rely on it.

The [read-only diagnostic trace](listener-trace.init.gradle) correlates this
mechanism with an actual missing file. One captured 9.7.1 write showed:

```text
NATIVE_LISTENER_TRACE all=[project ':libraries:lib', project ':tools:lib'] active=[project ':tools:lib'] queued=1 deferred=[project ':libraries:lib']
Persisted dependency lock state for project ':tools:lib'
BUILD SUCCESSFUL
```

That attempt's snapshot contains `tools.gradle.lockfile` and no library lock.
Other traced failures deferred the tools listener instead and omitted its file.
The instrumentation never changes listeners or writes native lock files.

Related upstream history: [#31537](https://github.com/gradle/gradle/issues/31537)
and its fix [#31692](https://github.com/gradle/gradle/pull/31692) address parallel
registration in `ListenerBroadcast`. This reproduction targets the separate
`DefaultListenerManager.ExclusiveEventBroadcast` queue and still fails in the
stock 9.2.1 and 9.7.1 distributions.

## Measured controls

Each table row is a separate local run. Repeated mode deletes only generated
lock files between writes. Counts describe these runs, not failure probabilities.

| Distribution and mode | Attempts | Successful builds with a missing file | Build failures |
| --- | ---: | ---: | ---: |
| Stock 9.7.1, strict isolation, parallel, fresh fixtures | 10 | 0 | 0 |
| Stock 9.7.1, strict isolation, parallel, repeated fixture | 30 | 1 | 0 |
| Stock 9.7.1, ordinary configuration cache, parallel, repeated fixture | 30 | 0 | 0 |
| Stock 9.7.1, strict isolation, one worker, repeated fixture | 30 | 0 | 0 |
| Stock 9.2.1, strict isolation, parallel, repeated fixture | 30 | 3 | 0 |
| Stock 9.7.1, strict isolation, parallel, diagnostic trace | 30 | 2 | 0 |
| Stock 9.7.1, committed verifier and trace, with file snapshots | 30 | 1 | 0 |
| Candidate-patched 9.7.1, strict isolation, parallel, repeated fixture | 100 | 0 | 0 |

## Candidate upstream fix experiment

The [candidate patch](listener-queue.patch), based on Gradle's `v9.7.1` source,
applies pending operations while holding the broadcaster lock before obtaining
the first dispatch snapshot. It also clears applied operations. Operations queued
during dispatch continue to apply after that dispatch.

For local diagnosis, only `DefaultListenerManager` and its generated nested
classes were recompiled with `--release 17` against stock 9.7.1 libraries. They
replaced those classes in `gradle-messaging-9.7.1.jar` in a disposable copy of the
distribution. The copy used a dedicated Gradle user home to prevent reuse of
stock daemons. Installed distributions and plugin runtime code were unchanged.

The patched distribution receives the first callback in both deterministic
probe cases and completes 100 repeated isolated parallel writes with both files.
This is a causal experiment, not a reviewed upstream fix or an accepted Gradle
release. Gradle's listener-manager regression suite has not been run. In
particular, dispatch-time registration, removals, logger replacement, exceptions,
and concurrent mutation ordering need upstream coverage and review.

## Small follow-up chunks and acceptance gates

1. Prepare the upstream report with the standalone fixture, deterministic
   interleaving, queue trace, and candidate patch. Add upstream listener tests and
   get a reviewed fix. Public submission is a separate action.
2. Establish which released Gradle versions contain that fix and a supported
   version policy for native isolation. Do not enable affected versions merely
   because a finite test run passes.
3. Add plugin TestKit coverage for project-local native consumption, full writes,
   and selective updates. Preserve selected/additional configurations, overrides,
   verifier policies, and explicit migration rejection. Compare flag-off controls
   and verify every participating file and its contents after each successful
   write/update, including parallel nested projects with duplicate leaf names.
4. Establish stable read-only configuration-cache reuse separately. Native
   `--write-locks` and `--update-locks` deliberately prevent cache reuse, so a
   successful write must not be counted as a cache-hit assertion. Verify cached
   reads, lock changes, version changes, and same-daemon flag rollback.
5. Remove the plugin's combined-flags rejection only after those native workflow
   and failure-path checks pass on every claimed Gradle/JDK combination. Retain
   the default-off feature flag and excluded global/migration workflows.

Do not implement the listener patch, queue flushing, eager resolution, or manual
native-file persistence inside this plugin as a workaround.
