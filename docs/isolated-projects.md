# Experimental Isolated Projects support

This experimental increment supports normal per-project JSON lock workflows on
Gradle 9.2.1 and 9.7.1. The initial JSON-locking release passed full repository CI
on JDK 17, 21, and 25; the [implementation plan](isolated-projects-plan.md) records
validation for each follow-up candidate. Native core-locking coexistence is a
separate follow-up.

## Enable the implementation

Add this to root `gradle.properties`:

```properties
systemProp.nebula.dependency-lock.isolatedProjectsSupport=true
```

For a single invocation, use
`-Dnebula.dependency-lock.isolatedProjectsSupport=true`. Only case-insensitive
`true` enables the mode; it defaults to false. There is no `-P` alias.

This flag selects the plugin implementation. Enable Gradle's Isolated Projects
separately with `--isolated-projects` on 9.7.1 or
`-Dorg.gradle.unsafe.isolated-projects=true` on 9.2.1. The new implementation can
also be tried with ordinary configuration caching before enabling isolation.

The flag does not change the locking engine. For a build already using this
plugin's normal JSON locks, a strict 9.7.1 invocation is:

```shell
./gradlew generateLock saveLock \
  -Dnebula.features.coreLockingSupport=false \
  -Dnebula.dependency-lock.isolatedProjectsSupport=true \
  --isolated-projects --configuration-cache --configuration-cache-problems=fail
```

Builds using `nebula.features.coreLockingSupport=true` must keep the new
`nebula.dependency-lock.isolatedProjectsSupport` flag absent or false. Setting
both to true fails during plugin application, including for `help`, before any
lock files are read, written, or deleted. To keep using native Gradle locking,
disable the new flag; this mode does not switch engines or convert formats.
Native coexistence and the independent
[parallel-write investigation](reproducers/native-lock-writes/README.md) are
tracked separately from this JSON-locking increment.

## Configure participants locally

Apply `com.netflix.nebula.dependency-lock` explicitly to each participating
project, directly or through a convention plugin applied there. Applying it to
root does not enroll children. Participating projects in included builds must
also configure their plugin application and flag explicitly; see the composite
workflows below.

Configure each project's lock and verifier extensions locally. Enabled lookup
uses local extra properties before Gradle properties and does not inherit parent
extras. Move inherited root extras into a convention plugin or appropriate
Gradle properties before opting in. Override-file paths retain their root-relative
base. Project-local filenames, selected configurations, declarative exclusions,
overrides, update selection, and verifier policies remain available.

Normal `generateLock`, `updateLock`, `saveLock`, `diffLock`, `deleteLock`, and
verification workflows are covered. Generation/update and save remain explicit
requests: save does not generate locks automatically. Enabled verification does
not publish state through the legacy public static lock/override maps; consumers
that read those maps must adapt before adopting this mode.

## Spring Boot integration

Spring Boot 4.0.9 is exercised with a multi-project application and library on
both target Gradle versions. With Gradle's native BOM support (`platform`), JSON
generation, selective updates, diff/save, and dependency reports store and reuse
strict Isolated Projects configuration-cache entries. Project dependencies keep
their `project: true` metadata; external dependencies retain version locks.

Executable `bootJar` packaging and application tests are covered with ordinary
configuration caching, including switching the plugin flag off/on/off without
rewriting saved locks. Strict isolation for those tasks is blocked by Spring
Boot 4.0.9: its `BootJar` constructor reads other projects' group/version. A test
reproduces that failure without the dependency-lock plugin applied. Task
dependencies can realize `bootJar` even when requesting application tests.

The `io.spring.dependency-management` 1.1.7 integration uses Boot's BOM import and
is covered with ordinary configuration caching. That plugin also reads other
projects' mutable state and parent properties, so strict isolation is not a
supported combination. Prefer Gradle's native BOM support when adopting
Isolated Projects, and keep isolation off for workflows blocked by the Spring
plugins. These checks do not establish support for other framework versions.

## Composite builds

Normal JSON locking is covered with two included Java builds, each containing a
`:lib` project, on Gradle 9.2.1 and 9.7.1. Apply the plugin and configure the lock
filename, selected configurations, and verifier policy locally in each project.
Main-build generation does not generate or update included-build locks.

Request included-build lock tasks using their build-qualified paths, for example:

```shell
./gradlew :first-build:lib:updateLock :first-build:lib:diffLock :first-build:lib:saveLock \
  -PdependencyLock.updateDependencies=example:library \
  -Dnebula.features.coreLockingSupport=false \
  -Dnebula.dependency-lock.isolatedProjectsSupport=true \
  --isolated-projects --configuration-cache --configuration-cache-problems=fail
```

Exact task names and unambiguous task abbreviations are covered, including
selective updates that preserve the other build's saved bytes and unselected
dependency versions. Dependency substitution records included projects as
`project: true`, while their external dependencies retain normal version locks.
Compilation consumes the substituted project artifacts.

Included projects have independent verifier policies even when their local paths
are identical. Parallel dependency reports, failures, warnings, repairs, and lock
updates are checked with strict isolation and configuration-cache reuse. The
coverage does not establish cross-build commit ordering.

## Included build-logic conventions

With the feature flag enabled, an included `build-logic` build can supply the
locally applied convention plugin through `pluginManagement { includeBuild(...) }`.
Coverage on Gradle 9.2.1 and 9.7.1 includes Java binary convention plugins and
precompiled Groovy and Kotlin script plugins. The convention applies the Java and
dependency-lock plugins and configures their extensions on each consumer project.

Declare the dependency-lock implementation as an `implementation` dependency of
`build-logic`, so its plugin ID and extension types are available when compiling
and applying the convention. Each participating project applies the convention
explicitly. Root, projects without the convention, and the build-logic build
itself are not enrolled automatically. Configure the feature flag for the
invocation; the convention does not enable it implicitly.

The tests cover conventional lock filenames with project-local overrides,
generation/save, selective updates/diff, project dependencies and compilation,
and verifier failures and repair. Editing and recompiling the convention
invalidates cached consumers and updates their verifier guidance. These workflows
run in parallel with strict isolation and configuration-cache reuse. The fixture
uses an explicit build-logic dependency and disables TestKit plugin injection,
exercising the convention's compilation and runtime classpath.

## Coordinate commits from root

Apply the plugin to root to get `commitDependencyLock` and `commitLock`. Child
application alone does not create or configure them on root. Declare child save
paths on the root extension when combining child saves with commit:

```groovy
commitDependencyLock {
    saveTaskPaths = [':libraries:lib:saveLock', ':tools:lib:saveLock']
    message = 'Update dependency locks'
    // Optional explicit annotated tag:
    // shouldCreateTag = true
    // tag = 'dependency-lock-update'
}
tasks.named('commitLock') {
    patternsToCommit = ['dependencies.lock', 'custom.lock']
}
```

Paths must identify existing tasks; omit projects without the plugin. Root save
ordering is automatic. The child list adds ordering without selecting saves or
generation, including when local lifecycle/publication tasks select commit.
For example, request `generateLock saveLock commitLock` explicitly.

Default staging matches root's normal-lock basename recursively. For different
child filenames, configure root `patternsToCommit` as above. Git ignore rules
exclude generated files under ignored build directories. Existing message/tag
CLI settings retain precedence; explicit tags need no editor. No staged changes
creates no commit. Git failures and duplicate tags fail the build. Commit does
not pull or push remote repositories.

## Unsupported workflows and rollback

Global locks are not recommended. Enabled mode rejects global locking and
migration tasks, global-lock consumption, and automatic JSON-to-native rewrites.
Remove or migrate such usage through the existing implementation before opting
in. The new mode does not convert lock formats.
The combination with `nebula.features.coreLockingSupport=true` is rejected for
all task requests. Native locking requires leaving the new flag off.

Custom `dependencyFilter` and raw generation-task `filter` callbacks are rejected
before changing lock outputs, including on cache reuse. Use `skippedDependencies`
when it expresses equivalent exclusions. Arbitrary callback behavior requires
leaving the flag off. Explicit custom `commitDependencyLock.tagClosure` usage is
rejected before Git effects; configure the `tag` property instead.

To roll back, remove the system property or set it to false and turn off Gradle
Isolated Projects if using the legacy implementation. Lock files keep their
existing formats. Configuration-cache entries invalidate when the implementation
mode changes.
