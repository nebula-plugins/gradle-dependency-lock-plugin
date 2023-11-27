/*
 * Copyright 2014-2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nebula.plugin.dependencylock.tasks

import nebula.plugin.dependencylock.DependencyLockWriter
import nebula.plugin.dependencylock.exceptions.DependencyLockException
import nebula.plugin.dependencylock.model.ConfigurationResolutionData
import nebula.plugin.dependencylock.model.LockKey
import nebula.plugin.dependencylock.model.LockValue
import nebula.plugin.dependencylock.utils.DependencyLockingFeatureFlags
import org.gradle.api.BuildCancelledException
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault
abstract class AbstractGenerateLockTask extends AbstractLockTask {
    private String WRITE_CORE_LOCK_TASK_TO_RUN = "`./gradlew dependencies --write-locks`"
    private String MIGRATE_TO_CORE_LOCK_TASK_NAME = "migrateToCoreLocks"

    @Internal
    String description = 'Create a lock file in build/<configured name>'

    @Internal
    abstract SetProperty<String> getConfigurationNames()

    @Internal
    abstract SetProperty<String> getSkippedConfigurationNames()

    @Input
    @Optional
    abstract SetProperty<String> getSkippedDependencies()

    @Internal
    abstract Property<File> getDependenciesLock()

    @Internal
    abstract MapProperty<String, String> getOverrides()

    @Input
    @Optional
    abstract Property<Boolean> getIncludeTransitives()

    @Input
    @Optional
    abstract Property<String> getGlobalLockFileName()

    @Internal
    abstract DirectoryProperty getProjectDirectory()

    @Internal
    abstract Property<Closure> getFilter()

    @Input
    abstract Property<Boolean> getShouldIgnoreDependencyLock()

    @Input
    abstract ListProperty<LockKey> getPeers()


    AbstractGenerateLockTask() {
        includeTransitives.convention(false)
        skippedDependencies.convention([])
        filter.convention({ group, name, version -> true })
        shouldIgnoreDependencyLock.convention(false)
        peers.convention([])
    }

    abstract List<ConfigurationResolutionData> resolveConfigurations()

    @TaskAction
    void lock() {
        if (DependencyLockingFeatureFlags.isCoreLockingEnabled()) {
            def globalLockFile = projectDirectory.file(globalLockFileName).get().asFile
            if (globalLockFile.exists()) {
                throw new BuildCancelledException("Legacy global locks are not supported with core locking.\n" +
                        "Please remove global locks.\n" +
                        " - Global locks: ${globalLockFile.absolutePath}")
            }

            throw new BuildCancelledException("generateLock is not supported with core locking.\n" +
                    "Please use $WRITE_CORE_LOCK_TASK_TO_RUN\n" +
                    "or do a one-time migration with `./gradlew $MIGRATE_TO_CORE_LOCK_TASK_NAME` to preserve the current lock state")
        }
        if (shouldIgnoreDependencyLock.isPresent() && shouldIgnoreDependencyLock.get()) {
            throw new DependencyLockException("Dependency locks cannot be generated. The plugin is disabled for this project (dependencyLock.ignore is set to true)")
        }
        Map dependencyMap = new GenerateLockFromConfigurations(peers.get()).lock(resolveConfigurations())
        new DependencyLockWriter(dependenciesLock.get(), skippedDependencies.get()).writeLock(dependencyMap)
    }


    class GenerateLockFromConfigurations {

        private final List<LockKey> peers

        GenerateLockFromConfigurations(List<LockKey> peers) {
            this.peers = peers
        }

        Map<LockKey, LockValue> lock(Collection<ConfigurationResolutionData> confs) {
            Map<LockKey, LockValue> deps = [:].withDefault { new LockValue() }
            confs.each { configurationResolutionData ->
                def resolvedComponent = configurationResolutionData.resolvedComponentResult.get()
                def resolvedDirectDependencies = resolvedComponent.dependencies
                def filteredResolvedDirectDependencies = resolvedDirectDependencies.findAll { it instanceof ResolvedDependencyResult}.findAll { ResolvedDependencyResult resolved ->
                    filter.get().call(resolved.selected.moduleVersion.group, resolved.selected.moduleVersion.name, resolved.selected.moduleVersion.version)
                }
                filteredResolvedDirectDependencies.each { ResolvedDependencyResult resolved ->
                    def key = new LockKey(group: resolved.selected.moduleVersion.group, artifact: resolved.selected.moduleVersion.name, configuration: configurationResolutionData.configurationName)

                    // If this dependency does not exist in our list of peers, it is a standard dependency. Otherwise, it is
                    // a project dependency.
                    if (!isKeyInPeerList(key, peers)) {
                        deps[key].locked = resolved.selected.moduleVersion.version
                    } else {
                        // Project dependencies don't have a version so they must be treated differently. Record the project
                        // as an explicit dependency, but do not lock it to a version.
                        deps[key].project = true
                    }

                    // If requested, lock all the transitive dependencies of the declared top-level dependencies.
                    def resolvedTransitiveDependencies = configurationResolutionData.allDependencies.findAll {
                        DependencyResult dependencyResult ->
                            dependencyResult instanceof ResolvedDependencyResult &&
                                    dependencyResult.from != resolvedComponent &&
                                    dependencyResult.from != dependencyResult &&
                                    dependencyResult.from == resolved.selected
                    }
                    deps[key].childrenVisited = true
                    resolvedTransitiveDependencies.each {
                        handleTransitive(it, configurationResolutionData, deps, peers, key)
                    }
                }

                // Add all the overrides to the locked dependencies and record whether a specified override modified a
                // preexisting dependency.
                overrides.get().each { String k, String overrideVersion ->
                    def (overrideGroup, overrideArtifact) = k.tokenize(':')
                    deps.each { depLockKey, depValue ->
                        if (depLockKey.group == overrideGroup && depLockKey.artifact == overrideArtifact) {
                            depValue.viaOverride = overrideVersion
                        }
                    }
                }
            }
            return deps
        }

        private void handleTransitive(ResolvedDependencyResult transitive, ConfigurationResolutionData configurationResolutionData, Map<LockKey, LockValue> deps, List peers, LockKey parent) {
            def key = new LockKey(group: transitive.selected.moduleVersion.group, artifact: transitive.selected.moduleVersion.name, configuration: configurationResolutionData.configurationName)

            // Multiple dependencies may share any subset of their transitive dependencies. Each dependency only needs to be
            // visited once so flag it once we visit it.
            if (!deps[key].childrenVisited) {
                // Lock each dependency and its children, recursively. Don't forget transitive project dependencies.
                if (!isKeyInPeerList(key, peers)) {
                    deps[key].locked = transitive.selected.moduleVersion.version
                } else {
                    deps[key].project = true
                }
                def resolvedTransitiveDependencies = configurationResolutionData.allDependencies.findAll {
                    DependencyResult dependencyResult ->
                        dependencyResult instanceof ResolvedDependencyResult &&
                                dependencyResult.from == transitive.selected
                } as List<ResolvedDependencyResult>
                deps[key].childrenVisited = true
                resolvedTransitiveDependencies.each { ResolvedDependencyResult resolved ->
                    handleTransitive(resolved, configurationResolutionData, deps, peers, key)
                }
            }

            // Record the dependencies from which this artifact originated transitively.
            deps[key].transitive << parent
        }

        private static def isKeyInPeerList(LockKey lockKey, List<LockKey> peers) {
            return peers.any {
                it.group == lockKey.group && it.artifact == lockKey.artifact
            }
        }
    }
}

