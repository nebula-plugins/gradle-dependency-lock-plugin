# Opt-in Isolated Projects support

Status: implementation in progress. See the implementation plan for completed
chunks and remaining compatibility work.

Source baseline: `6e103615c61def10d0fa116c4c67cdd76fd79be5`.

Implementation sequence: [Implementation plan](isolated-projects-plan.md).

## Objective

Make normal, per-project dependency locking compatible with Gradle Isolated
Projects behind an explicit feature flag. Keep the existing implementation as the
default so consumers can adopt the new behavior incrementally and roll back by
disabling the flag.

Global locks are not recommended and are outside this migration. Root ownership
of `commitLock` remains in scope because it commits ordinary per-project locks.

## Feature flag contract

Add the flag to the existing
`src/main/groovy/nebula/plugin/dependencylock/utils/DependencyLockingFeatureFlags.groovy`.
Do not introduce a separate feature-flag framework.

Canonical system property:

```text
nebula.dependency-lock.isolatedProjectsSupport
```

Enable it in the root `gradle.properties`, following the system-property mechanism
used by this plugin's existing flags:

```properties
systemProp.nebula.dependency-lock.isolatedProjectsSupport=true
```

Or enable it for one invocation:

```shell
./gradlew generateLock saveLock -Dnebula.dependency-lock.isolatedProjectsSupport=true
```

The proposed additive helper is
`isIsolatedProjectsSupportEnabled(Project project)`, with a named constant for the
property key. Existing feature-flag helpers and signatures remain intact.

- Default to `false`. Use case-insensitive `true` as the enabling value, matching
  `Boolean.parseBoolean`; other values are false.
- Read through `project.providers.systemProperty(...)` during project
  configuration. Do not cache the boolean in a static field or search project or
  parent extras. No `-P` alias is part of this contract.
- Resolve the flag once per plugin application and pass immutable mode information
  to internal collaborators. Keep legacy public entry points callable.
- A change between absent, false, and true must not reuse a configuration cache
  entry produced for a different effective mode. Reuse is allowed within one mode.
- The system property selects one mode for participants within a build. Do not
  provide a project-extension override or promise mixed modes within that build.
- Included builds must configure the flag and apply the plugin explicitly; do not
  promise automatic propagation or composite-build support in this release.
- This flag selects the plugin implementation; it does not enable Gradle Isolated
  Projects. Do not automatically opt in when Gradle isolation is enabled.

| Plugin flag | Gradle isolation | Required behavior |
| --- | --- | --- |
| Absent or false | Off | Existing behavior and compatibility |
| Absent or false | On | Existing behavior; no automatic fallback or compatibility promise |
| True | Off | New project-local behavior, allowing rollout before isolation |
| True | On | New behavior with strict isolation and configuration-cache support |

The flag stays disabled by default throughout the implementation sequence and
release. Changing the default is a separate decision.

## Supported workflows and boundaries

The enabled mode targets the existing plugin ID,
`com.netflix.nebula.dependency-lock`, on Gradle 9.2.1 and the current wrapper
(9.7.1 at this baseline). It does not raise the supported Gradle floor.

In scope:

- Consuming normal `dependencies.lock` files, including overrides and validation.
- `generateLock`, `updateLock`, `saveLock`, `diffLock`, and `deleteLock`.
- Local `verifyDependencyResolution`, its finalizers, and reporting-build failure
  handling through the existing FlowAction.
- Root-owned `commitLock` for normal lock files, including existing message,
  explicit tag, retry, file-pattern, and ordering behavior.
- Coexistence with existing per-project core Gradle locking: consume, write, and
  update `gradle.lockfile` when `nebula.features.coreLockingSupport` is enabled.
  The new flag must not switch locking engines or turn off that existing flag.
- Property precedence, custom lock filenames, configuration selection, skipped
  dependencies, transitive handling, late configurations, and lazy task wiring.

Out of scope:

- Global-lock generation, update, save, deletion, and consumption.
- `migrateToCoreLocks` and the existing `migrateLockeDepsToCoreLocks` task, including
  automatic rewriting/deletion of legacy files during migration.
- A new settings plugin, automatic participant discovery, or root-to-child plugin
  application.
- A replacement arbitrary callback language, unrelated dependency upgrades,
  plugin-publication changes, or a default-on rollout.

Enabled mode must fail explicitly when an excluded workflow is selected. It must
not enter the old global or migration engine, disable isolation, downgrade cache
errors, silently skip work, or discard a required lock file. Register root-owned
global rejection tasks and project-owned migration rejection tasks lazily so
ordinary tasks and `help` still work. Preserve their public names and task types
where possible without wiring the legacy implementation. Preserve existing names
and behavior unchanged when the flag is off.

Enabled excluded-task names use `UnsupportedIsolatedLockingTask`, whose sole action
reports the unsupported workflow. Their legacy task types are retained with the
flag off; configuring those legacy types is outside the enabled contract.

If resolution would otherwise consume a global lock file, enabled mode must stop
with a message identifying the file and explaining that the rollout supports
per-project locks. Do not silently prefer individual locks. File presence checks
must not read foreign mutable project state. Disabling the new flag restores the
existing workflows for migration/removal. `dependencyLock.disableGlobalLock` must
not be used to silently disregard an existing global lock during resolution.

Core-lock coexistence is a release gate, not an assumption established by the
existing assessment. Explicit migration requests and automatic legacy-lock
rewrites remain rejected in enabled mode; existing diagnostics for conflicting
legacy/core files must remain meaningful.

## Project ownership and consumer adoption

Each participant applies the same plugin locally, directly or through a convention
plugin. Root-only application continues to lock only root. Child-only application
in enabled mode must leave root extensions and tasks untouched.

- Each project owns its `dependencyLock` and
  `dependencyResolutionVerifierExtension` configuration.
- Shared verifier settings come from a convention applied locally or explicit
  Gradle properties. Root verifier configuration no longer broadcasts in enabled
  mode. This is an intentional opt-in adoption change.
- Root owns `commitDependencyLock` and `commitLock`. Consumers wanting commit
  coordination apply the plugin at root as well as in participating children.
- Children must never fetch root extension objects or root task containers.
- Root/peer directory identity may be used through documented immutable APIs
  supported on 9.2.1. Do not read `rootProject.layout` or defer foreign reads inside
  provider lambdas. Verify the chosen API against both target Gradle versions.
- Consumer `allprojects`/`subprojects` configuration can itself violate isolation;
  fixtures and adoption examples must apply/configure locally.

No public classes, task names, scalar/Set bridge accessors, `*Property` accessors,
parser/model APIs, or public static map signatures are removed by this work.
Legacy public entry points delegate to legacy behavior unless an additive,
explicit mode-aware path is selected internally.

## Implementation requirements

### Branch before incompatible work

Select the mode before any root extension/task access, dynamic parent lookup,
static-map initialization, or global/migration setup in `DependencyLockPlugin.apply`.
Wrapping only task execution is insufficient: child application already fails
during configuration. Use private/internal mode-specific wiring and share pure
resolution and writing logic where practical. Avoid duplicating the whole plugin
or scattering property reads through every helper.

All intentional behavior changes below apply only with the flag enabled. Keep
the default path's observable behavior, defaults, and public API intact.

### Property lookup and tracked files

Provide an internal mode-aware lookup that checks only the owning project's
explicit extras and tracked Gradle properties. Before replacing `findProperty`,
characterize and preserve the current local-extra/`-P` precedence, conversion,
empty-value, and absent-value behavior. Parent extras are deliberately excluded
from enabled mode; common settings must be supplied explicitly.

Apply the lookup consistently to lock filenames, overrides, ignore settings,
generation options, verifier settings, and commit settings. Preserve each setting's
current default and extension precedence. In enabled mode, resolve override files
relative to the build root for both resolution and verification, matching the
existing lock reader. Use the same parser and file-then-inline override precedence
for both. Preserve the legacy verifier's project-relative interpretation when
the flag is off.

Track configuration-time file reads/presence through supported Gradle mechanisms.
Wire execution-time inputs through task properties. Lock and override edits,
creation, deletion, and renamed paths must affect the appropriate results after
cache reuse. Distinguish graph invalidation from task reruns.

### Generation, saving, and task intent

Normal generation already uses `ResolutionResult` providers. Remove its unused
all-project peer-coordinate scan in enabled mode. Keep the public
`peerProjectCoordinates` property with a safe convention, and retain the legacy
Configuration-based path for flag-off callers. Preserve `project:true` and
external dependencies reached through project dependencies.

Preserve selected configurations, skipped prefixes, transitive behavior, override
metadata, and output locations. Generation must keep its current rerun behavior
until a separate complete task-input model justifies a change.

`saveLock` still requires an explicitly requested generate/update workflow;
`mustRunAfter` must not become `dependsOn`. Preserve diff-before-save ordering and
delete behavior. Do not let up-to-date skipping bypass an existing-global-lock
conflict check. Represent optional file existence safely; merely adding
`@Optional @InputFile` to a non-existing configured file is not a solution.

Replace the enabled path's build-wide `taskGraph.whenReady` mutation with local
configuration rules and immutable requested-task intent. Test simple, qualified,
abbreviated, nested, and mixed task requests, including duplicate project leaf
names. Avoid selecting projects by leaf name alone. Keep working local
`afterEvaluate`/`beforeResolve` hooks where their state and timing are safe; do not
remove them solely because they are callbacks. Avoid introducing eager resolution.

### Verification state and reporting

Enabled mode must not initialize, read, or write the public static lock/override
maps. Retain those maps and legacy behavior for flag-off consumers. Use local
immutable lock/override snapshots as task parameters.

Use full project paths for state identities within the build-scoped verification
service. If a future design shares state across builds, include build identity.
Replacing a map with `ConcurrentHashMap` alone does not establish isolation.

Keep the existing BuildService as an execution-time collector, with no
configuration-time realization, reset, or project-to-project communication.
Initialize execution state in the owning task, preserving the service reference
for FlowAction reporting on cache hits. Distinguish duplicate leaf names and
consecutive daemon builds, and test concurrent verification.

Preserve finalizers, reporting-task configuration scoping, exclusions, custom
messages, unresolved-dependency failures, lock-mismatch failures, and
`shouldFailTheBuild=false`. Reporting builds must retain their final exit status
and messages on both cache misses and hits. No resolution or verification failure
may be swallowed to make compatibility tests pass.

### Root commit coordination

Register/configure `commitLock` only from root in enabled mode. Continue using its
task properties and `ExecOperations`; no Git commands run during configuration.

Preserve recursive staging of the configured normal-lock basename. Default
root patterns come from root configuration; custom child basenames require an
explicit root `patternsToCommit` list or a shared filename convention. Root must
not inspect child extensions to discover filenames.

Express ordering against selected participant save tasks through supported task
paths/immutable identities, without reading foreign task containers. If an explicit
root task-path list is needed, document it and make it additive. Validate this
approach on 9.2.1 before finalizing it. Commit must run after selected saves without
implicitly requesting generation or saving. Preserve user-supplied local
`dependsOn`/`finalizedBy` integrations, including consumer publication wiring.

Tests use disposable local Git repositories and local remotes where necessary;
they must not commit, tag, pull, or push the working repository.

### Customization safety

Retain default filters and existing supported declarative options. Custom
`dependencyFilter`/raw task `filter` closures that cannot preserve results across
cache reuse must fail with an actionable message in enabled mode, before writing
files. Do not silently drop a filter or relax strict cache checks. Recommend
`skippedDependencies` only when it expresses equivalent behavior. Preserve legacy
callback behavior and API with the flag off.

The existing `tagClosure` is retained but not invoked by current task wiring. This
migration must not start invoking it or claim callback compatibility. Reject an
explicit unsupported callback for an enabled commit workflow before Git side
effects; retain explicit `tag` Property support.

## Validation and release criteria

Use realistic TestKit fixtures with local Maven modules and local plugin
application. Establish characterization coverage for existing isolation failures
before changing the implementation. Expected failures document blockers; only
positive compatibility tests establish support.

| Dimension | Required coverage |
| --- | --- |
| Flag | Absent, false, true; absent/false equivalence; same-daemon off/on/off transitions |
| Gradle | 9.2.1 and current wrapper; no minimum-version increase |
| Cache/isolation | Flag on with ordinary CC and strict IP; flag off with ordinary CC |
| Projects | Single, root-only, child-only, selective participation, flat and nested duplicate leaf names |
| Normal locks | Consume/generate/update/save/diff/delete, overrides, custom filenames/configurations, project transitives |
| Verification | Success, missing module, lock mismatch, exclusions, warning-only policy, report/build/compile finalizers |
| Core coexistence | Existing per-project consume/write/update with core flag on; existing errors for invalid combinations |
| Commit | Root ownership, selected-save ordering, patterns, explicit tag, local Git behavior, consumer task wiring |
| Inputs | File edits/create/delete, relevant properties, extension values, dependency scripts, configuration selection |
| Lifecycle | Plugin order, late configurations, help laziness, qualified/abbreviated selectors, parallel projects |
| Compatibility | Groovy and Kotlin DSL fixtures; retain public bridges/task property accessors; flag-off regressions |

Successful enabled workflows must store and reuse the configuration cache with
`--configuration-cache-problems=fail`. Use `--isolated-projects` on 9.7.1 and
`-Dorg.gradle.unsafe.isolated-projects=true` on 9.2.1. No warnings/ignored-problems
mode, incompatible-task cache discard, or expected-failure assertion counts as a
successful compatibility test. Expected failures remain appropriate for excluded
workflows and supported validation errors.

Start with `--no-watch-fs` for deterministic external fixture edits; add coverage
with default watching before claiming production invalidation behavior. Use a
matching supported JDK for each Gradle version. Run the repository's existing CI
JDK matrix for the final gate, preserving its nested-TestKit JDK selection rules.
Do not assume every tested Gradle supports running on every installed JDK.

Compare observable versions, lock/diff contents, project and override metadata,
failure messages/status, task ordering, and cache reuse. Require equivalent bytes
for deterministic lock files and semantic equivalence where ordering is not part
of the format contract. Preserve normal-mode errors such as calling legacy
generation with core locking enabled.

Release only after all in-scope workflows pass. Document consumer adoption,
exclusions, the system-property syntax, version limits, and rollback. Disabling
the flag must restore legacy behavior without a lock-format conversion or
automatic file deletion. Partial chunks remain experimental; they do not advertise
complete Isolated Projects support or change plugin-portal compatibility claims.

## Source anchors for implementation

All paths below are relative to `src/main/` and describe the baseline, not
permanent line numbers.

| Concern | Source |
| --- | --- |
| Existing flag infrastructure | `groovy/nebula/plugin/dependencylock/utils/DependencyLockingFeatureFlags.groovy` |
| Root extensions, static maps, locking callbacks/selectors | `kotlin/nebula/plugin/dependencylock/DependencyLockPlugin.kt` |
| Root commit/save wiring, peer scan, excluded workflows | `groovy/nebula/plugin/dependencylock/DependencyLockTaskConfigurer.groovy` |
| Override lookup and path bases | `groovy/nebula/plugin/dependencylock/DependencyLockReader.groovy`, `kotlin/nebula/plugin/dependencylock/gradleInterop.kt` |
| Public DSL bridges and deprecated filter | `groovy/nebula/plugin/dependencylock/DependencyLockExtension.groovy` |
| Generation, save guard, commit execution | `groovy/nebula/plugin/dependencylock/tasks/{GenerateLockTask,SaveLockTask,CommitLockTask}.groovy` |
| Verification parameters, state, finalizers, reporting | `kotlin/nebula/plugin/dependencyverifier/{DependencyResolutionVerifier,DependencyVerificationTask,DependencyVerificationBuildService,DependencyResolutionFlowAction}.kt` |
| Existing core-lock engine | `groovy/nebula/plugin/dependencylock/utils/CoreLockingHelper.groovy` |
