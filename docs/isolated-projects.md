# Experimental Isolated Projects support

The opt-in implementation is under development. Normal per-project JSON lock
workflows have focused coverage on Gradle 9.2.1 and 9.7.1 with JDK 17, 21, and 25.
Full release validation remains incomplete, including native Gradle lock-write
compatibility and full-suite CI runs on JDK 17 and 25. See the
[implementation plan](isolated-projects-plan.md)
for the current evidence and remaining gates.

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

Continue using `nebula.features.coreLockingSupport` to select native Gradle
locking where already configured. Parallel native writes currently have an
[unresolved persistence blocker](reproducers/native-lock-writes/README.md), also
reproduced without this plugin. Native compatibility is not established by the
normal JSON-lock test results.

## Configure participants locally

Apply `com.netflix.nebula.dependency-lock` explicitly to each participating
project, directly or through a convention plugin applied there. Applying it to
root does not enroll children. Included builds must configure their own plugin
application and flags; composite-build compatibility is outside this rollout.

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

Custom `dependencyFilter` and raw generation-task `filter` callbacks are rejected
before changing lock outputs, including on cache reuse. Use `skippedDependencies`
when it expresses equivalent exclusions. Arbitrary callback behavior requires
leaving the flag off. Explicit custom `commitDependencyLock.tagClosure` usage is
rejected before Git effects; configure the `tag` property instead.

To roll back, remove the system property or set it to false and turn off Gradle
Isolated Projects if using the legacy implementation. Lock files keep their
existing formats. Configuration-cache entries invalidate when the implementation
mode changes.
