# Isolated Projects implementation plan

Contract: [Specification](isolated-projects-spec.md).

Status: implementation in progress. Baseline coverage is established; the new
mode remains experimental while isolation changes are implemented. Each numbered
chunk is intended as one small PR or reviewable commit. Split a chunk further
when it changes unrelated behavior.
Dependencies below determine order; independent chunks may be reordered without
changing the contract.

## Rules for every chunk

- Keep the feature flag off by default and leave the flag-off implementation's
  observable behavior intact. Keep deliberate new behavior behind the flag.
- Test only the behavior introduced by that chunk, plus relevant flag-off
  regressions. Run the full suite at the final gate.
- Use real local TestKit fixtures for resolution, cache, task, and Git behavior.
  Use smaller tests for parsing/precedence when no Gradle lifecycle is involved.
- Preserve public signatures. Add internal wiring or compatible overloads rather
  than removing task properties or existing entry points.
- Label remaining blockers honestly. Convert an expected-failure assessment case
  into a positive case only after its actual workflow stores/reuses successfully.
- Do not run simultaneous outer Gradle test invocations: they share test output
  directories. Keep nested fixture directories isolated.
- Update this checklist and record validation with each implementation PR. No
  chunk changes the release/default-on decision.

## 0. Establish the compatibility baseline

- [x] Add or review characterization coverage in
  `src/test/kotlin/com/netflix/nebula/dependencylock/IsolatedProjectsAssessmentTest.kt`.
- [x] Keep passing single-project checks separate from deliberate multi-project
  blocker characterizations. Record the baseline revision and target versions.
- [x] Preserve local repositories, local plugin application, strict cache errors,
  and reproducible file invalidation checks.

Validation: run the characterization tests and
`DependencyLockConfigurationAvoidanceSpec` in one invocation and record the
results for the tested revision. Separate positive checks from cases that expect
isolation failures. This chunk makes no production changes.

Depends on: none.

Completed validation: 37 tests passed on JDK 21, covering Gradle 9.2.1 and 9.7.1.
Six cases deliberately assert existing isolation failures; these remain blockers.

## 1. Add the opt-in flag and internal mode plumbing

- [x] Extend `DependencyLockingFeatureFlags` with the spec's constant and additive
  project-aware helper. Read the system property with Gradle providers.
- [x] Resolve the mode at the start of `apply`; pass it through internal/additive
  paths before incompatible operations. Do not initialize it statically.
- [x] Keep absent/false on the legacy path. Leave existing flag helpers unchanged.
- [x] Introduce the opt-in path with explicit remaining limitations; do not claim
  compatibility until subsequent chunks complete.

Validation: absence/false/true parsing, a single-project enabled fixture, and
off/on/off cache invalidation/reuse in consecutive builds using the same daemon.
Verify existing core/path-aware flags remain independent. The enabled path may
still hit characterized blockers at this stage; do not hide them.

Depends on: 0.

Completed validation: the six parameterized `DependencyLockingFeatureFlagsTest`
cases passed on JDK 21 with Gradle 9.2.1 and 9.7.1. They cover default/false/true
and other values, same-daemon off/on/off invalidation and reuse, unchanged generated
locks, and root `gradle.properties` opt-in with strict single-project isolation.
The combined baseline and flag suite passed all 43 tests. The mode is read once
and passed to the private application entry point; root access and other legacy
wiring remain unchanged until subsequent chunks. Existing core/path-aware flag
helpers are unchanged. This chunk does not establish multi-project compatibility.

## 2. Make property and override lookup project-local

- [x] Characterize local-extra/Gradle-property precedence and type/empty handling.
- [x] Add internal enabled-mode lookup; keep parent-searching legacy entry points.
- [x] Wire lock/override filenames, overrides, ignore settings, generation,
  verifier, and commit options through the appropriate mode-aware path.
- [x] Preserve file path bases; explicitly reconcile reader/verifier override-file
  interpretation in enabled mode using a child-project fixture.
- [x] Track relevant file contents and absence/presence during configuration.

Validation: missing optional properties never search parents; local extras and
`-P` values retain characterized precedence. Verify override file/CLI priority,
relative paths, changes/removal, and cache invalidation. Verify flag-off inherited
extras still work. Do not convert all property lookups repository-wide.

Depends on: 1.

Completed implementation: enabled lookup preserves local-extra precedence and
types, tracks Gradle properties, and excludes parent extras. Compatible overloads
keep existing entry points on legacy lookup. Enabled resolution and verification
share the existing root-relative override parser, including deprecated files and
inline overrides; flag-off verifier file interpretation is unchanged.

Validation covers both Gradle versions on JDK 21: 31 focused tests plus 24
assessment cases and the existing verifier override-file regression. New tests
exercise strict-isolation lookup, inherited/local settings, verifier policy,
cache reuse and property invalidation, override edits/deletion/restoration, and
inline-over-file precedence. Full plugin multi-project isolation remains blocked
by root ownership and peer-coordinate scanning until the next chunks.

## 3. Isolate plugin application and reject excluded workflows

- [ ] Create a verifier extension in each enabled participant.
- [ ] Give root sole ownership of commit extension/task registration; child
  application never reads/writes root extensions or task containers.
- [ ] Bypass legacy global/migration setup in enabled mode. Register lazy rejecting
  task paths with actionable messages and preserve names/types where feasible.
- [ ] Reject global-lock consumption and automatic legacy-to-core rewriting before
  changing resolution/files. Keep the flag-off engines intact.
- [ ] Preserve lazy `help` and late plugin application behavior.

Validation: enabled child-only and flat/nested application succeeds under strict
IP, root stays untouched, and root-only application does not enroll children.
Excluded requests fail for the stated reason without side effects; unrelated
`help` stores/reuses and leaves generation tasks unrealized. Existing global and
migration tests still pass with the flag off. Generation may still hit its
separate peer-scan blocker until chunk 4.

Depends on: 1, 2.

## 4. Make normal generation and saving isolated

- [ ] Omit peer-coordinate scanning on the enabled ResolutionResult path; keep a
  safe convention for the public property and retain legacy configuration callers.
- [ ] Replace normal save/global-conflict path wiring with immutable root-directory
  identity supported on both target Gradle versions.
- [ ] Preserve project-dependency/transitive metadata, configuration selection,
  output locations, current generation rerun behavior, and explicit save ordering.
- [ ] Make conflict-file presence affect save correctness even when normal outputs
  would otherwise be up to date.

Validation: root-only, child-only, flat and nested generate/save workflows store
and reuse under strict IP; changing a sibling dependency changes the saved lock.
Verify `project:true`, include-transitives, overrides, custom filenames, selected
configurations, and creation/deletion of a conflicting global file across cache
hits. Convert the corresponding assessment blockers to positive enabled cases;
retain disabled-mode characterization separately.

Depends on: 2, 3.

## 5. Isolate verification state and preserve failures

- [ ] Stop enabled-mode initialization/writes/reads of static lock/override maps;
  retain their signatures and legacy behavior.
- [ ] Use full project paths for enabled build-service keys and immutable local
  lock/override parameters.
- [ ] Move service initialization into owning task execution; keep FlowAction
  state/service wiring valid on cache reuse.
- [ ] Preserve compile/lifecycle/report finalizers, report scoping, exclusions,
  custom messages, and failure policy.

Validation: distinct `:libraries:lib` and `:tools:lib` outcomes, parallel projects,
same-daemon consecutive builds, missing modules, lock mismatches, overridden
versions, report-only builds, and `shouldFailTheBuild=false`. Successful cases
store/reuse; expected validation failures keep their messages/exit status on
repeat execution. Audit downstream map usage before promising opt-in semantics
to consumers that directly read those public maps.

Depends on: 3, 4.

## 6. Complete update, diff, delete, and task-selection behavior

- [ ] Replace enabled-mode task-graph cache mutation with local rules that preserve
  changing/dynamic dependency freshness for generate/update.
- [ ] Fix enabled requested-task scope using full project paths; preserve Gradle
  selection semantics for simple, qualified, relative, and abbreviated selectors.
- [ ] Validate update selection, overrides, diff output/options, and delete inputs.
- [ ] Preserve generate/update-before-diff-before-save ordering without new implicit
  generation dependencies.

Validation: local-repository version changes prove update freshness; nested
duplicate leaf names prove task intent is applied to the correct project.
Exercise mixed task requests, save-without-generation behavior, path-aware diff
flag off/on, and repeated delete workflows. Verify late configurations and plugin
order without eager resolution or generation-task realization on `help`.

Depends on: 4, 5.

## 7. Verify coexistence with normal core Gradle locking

- [ ] Exercise the enabled project-local setup with the existing core-lock flag.
- [ ] Adapt only enabled-path wiring exposed as incompatible by those tests;
  retain the locking engine, defaults, and existing core helper API.
- [ ] Preserve configuration selection/additional configurations and update-cache
  behavior; keep migration/automatic rewrite requests explicitly unsupported.

Validation: local and multi-project `gradle.lockfile` consumption, `--write-locks`,
`--update-locks`, normal resolution verification, selected/additional
configurations, and reuse after inputs stabilize. Use flag-off equivalents as the
behavior reference. Prove invalid combinations still fail without deleting or
rewriting legacy locks. Do not treat the legacy-lock-only assessment as evidence
for this chunk.

Depends on: 3, 5, 6.

## 8. Finish root commit coordination for normal locks

- [ ] Complete root-owned configuration through existing properties/ExecOperations.
- [ ] Preserve ordering after selected participant saves using immutable identities
  or explicit task paths; verify the chosen API on 9.2.1 before extending the DSL.
- [ ] Preserve recursive normal-lock staging; document root patterns for custom
  child filenames without reading child extensions.
- [ ] Preserve explicit message/tag/retry settings and consumer task integrations.

Validation: disposable local Git repositories prove actual staged files, commit
contents, explicit tags, and ordering in combined save/commit invocations. Child
application alone creates no root task. Commit does not implicitly run generation
or saves. Verify cache storage/reuse, configured custom filename patterns, a
representative local publication/lifecycle integration, and existing flag-off
commit behavior. No Git side effects in the working repository or external hosts.

Depends on: 3, 5, 6.

## 9. Make unsupported customizations explicit

- [ ] Reject unsupported nondefault extension/task filter closures in enabled
  generation before lock writes; default and declarative behavior still work.
- [ ] Reject explicit unsupported `tagClosure` usage for enabled commits before
  Git side effects; preserve explicit tag Property behavior.
- [ ] Keep flag-off API/behavior and actionable migration guidance; do not add an
  unrelated callback DSL or silently substitute nonequivalent filtering.

Validation: default/custom filters, raw task customization, declarative exclusions,
explicit tags, and unsupported callbacks across first and reused builds. Assert
errors identify the unsupported option and leave output files/Git state intact.
Add Groovy/Kotlin DSL checks for bridge accessors touched by the migration.

Depends on: 4, 8. This guard may land earlier if customization testing exposes a
silent-correctness problem before those chunks complete.

## 10. Run the complete matrix and document rollout

- [ ] Run all enabled normal-lock/core/commit acceptance cases on 9.2.1 and the
  wrapper, with ordinary CC and strict IP.
- [ ] Run absent/false regression coverage and repeat off/on/off transitions after
  the full wiring exists; compare deterministic output bytes and failures.
- [ ] Exercise default filesystem watching, supported JDK selection, DSL variants,
  parallel configuration, and repeated same-daemon builds.
- [ ] Run the full repository suite/check tasks and existing CI JDK matrix.
- [ ] Add README/wiki-facing opt-in, local-convention, root-commit, exclusion, and
  rollback guidance. No format conversion or default-on change.
- [ ] Replace only resolved blocker characterizations; retain explicit rejection
  tests for global/migration/customization boundaries.
- [ ] Record precisely which workflows and versions are supported. Keep included
  builds and untested framework combinations outside the release claim.

Validation: all required spec rows have evidence; no strict-mode warning downgrade,
cache discard, ignored problem, or expected blocker failure is counted as success.
Run the full suite once per final candidate, repeating only for new changes or
failures. Release is gated on this chunk, with the flag still off by default.

Depends on: 0–9.

## Commands and evidence

Initial baseline, from the repository root:

```shell
./gradlew test \
  --tests '*IsolatedProjectsAssessmentTest' \
  --tests '*DependencyLockConfigurationAvoidanceSpec' \
  --console=plain
```

For implementation chunks, select the new focused tests and relevant existing
specs. Nested TestKit invocations must explicitly set the plugin mode and locking
engine; the repository's own `gradle.properties` enables core locking and must not
accidentally determine the fixture engine. For deterministic legacy-lock fixtures:

```text
--configuration-cache --configuration-cache-problems=fail --no-watch-fs
-Dnebula.features.coreLockingSupport=false
-Dnebula.dependency-lock.isolatedProjectsSupport=true
```

Add `--isolated-projects` on 9.7.1, or
`-Dorg.gradle.unsafe.isolated-projects=true` on 9.2.1. Core-lock fixtures explicitly
set `-Dnebula.features.coreLockingSupport=true` instead. Put the new flag in root
fixture `gradle.properties` for persistent-adoption tests and use `-D` for toggle
tests. Include flag-off controls; no extra property alias is implied.

Record the tested revision, Gradle/JDK versions, assertions exercised, store/reuse
results, changed-input results, and remaining exclusions in each PR. Final checks:

```shell
./gradlew build --stacktrace
git diff --check
```

## Decisions to settle inside the relevant chunk

Resolve these implementation details within the relevant chunk while preserving
the specification's compatibility requirements:

| Decision | Chunk | Required outcome |
| --- | --- | --- |
| Local extras versus Gradle-property precedence and override-file base | 2 (resolved) | Local extras retain precedence and types; enabled reader and verifier use root-relative override files |
| 9.2.1-compatible immutable root directory and save presence input | 4 | Strict IP success and conflict correctness across reuse |
| Public static-map downstream adoption | 5 | APIs retained; enabled execution does not communicate through static maps |
| Qualified/abbreviated task scope | 6 | Correct participant, including nested duplicate names, without foreign mutable reads |
| Root commit ordering mechanism | 8 | Selected saves finish first; no foreign task inspection or forced generation |
| Detecting direct task filter/tag callback customization | 9 | Clear rejection before side effects; no silent cache-hit behavior change |
