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

import nebula.plugin.dependencylock.DependencyLockReader
import nebula.plugin.dependencylock.DependencyLockTaskConfigurer
import nebula.plugin.dependencylock.DependencyLockWriter
import nebula.plugin.dependencylock.exceptions.DependencyLockException
import nebula.plugin.dependencylock.model.LockKey
import nebula.plugin.dependencylock.model.LockValue
import nebula.plugin.dependencylock.utils.CoreLocking
import nebula.plugin.dependencylock.wayback.WaybackProvider
import org.gradle.api.BuildCancelledException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.tasks.TaskAction

class GenerateLockTask extends AbstractLockTask {
    String description = 'Create a lock file in build/<configured name>'
    Collection<Configuration> configurations = []
    Set<String> configurationNames
    Closure filter = { group, name, version -> true }
    Set<String> skippedDependencies = []
    File dependenciesLock
    Map<String, String> overrides
    boolean includeTransitives = false
    WaybackProvider waybackProvider

    @TaskAction
    void lock() {
        if (CoreLocking.isCoreLockingEnabled()) {
            throw new BuildCancelledException("generateLock is not supported with core locking. Please use `./gradlew dependencies --write-locks`")
        }
        if (DependencyLockTaskConfigurer.shouldIgnoreDependencyLock(project)) {
            throw new DependencyLockException("Dependency locks cannot be generated. The plugin is disabled for this project (dependencyLock.ignore is set to true)")
        }
        Collection<Configuration> confs = getConfigurations() ?: lockableConfigurations(project, project, getConfigurationNames())
        Map dependencyMap = project.hasProperty('waybackTo') ?
                new GenerateLockFromWayback().lock(confs) :
                new GenerateLockFromConfigurations().lock(confs)
        new DependencyLockWriter(getDependenciesLock(), getSkippedDependencies()).writeLock(dependencyMap)
    }

    static Collection<Configuration> lockableConfigurations(Project taskProject, Project project, Set<String> configurationNames) {
        if (configurationNames.empty) {
            if (Configuration.class.declaredMethods.any { it.name == 'isCanBeResolved' }) {
                project.configurations.findAll {
                    if (taskProject == project) {
                        it.canBeResolved
                    } else {
                        it.canBeResolved && it.canBeConsumed
                    }
                }
            } else {
                project.configurations.asList()
            }
        } else {
            configurationNames.collect { project.configurations.getByName(it) }
        }
    }

    class GenerateLockFromConfigurations {
        Map<LockKey, LockValue> lock(Collection<Configuration> confs) {
            Map<LockKey, LockValue> deps = [:].withDefault { new LockValue() }

            // Peers are all the projects in the build to which this plugin has been applied.
            def peers = project.rootProject.allprojects.collect { new LockKey(group: it.group, artifact: it.name) }

            confs.each { Configuration configuration ->
                // Capture the version of each dependency as requested in the build script for reference.
                def externalDependencies = configuration.allDependencies.withType(ExternalDependency)
                def filteredExternalDependencies = externalDependencies.findAll { Dependency dependency ->
                    filter(dependency.group, dependency.name, dependency.version)
                }
                filteredExternalDependencies.each { ExternalDependency dependency ->
                    def key = new LockKey(group: dependency.group, artifact: dependency.name, configuration: configuration.name)
                    deps[key].requested = dependency.version
                }

                // Lock the version of each dependency specified in the build script as resolved by Gradle.
                def resolvedDependencies = configuration.resolvedConfiguration.firstLevelModuleDependencies
                def filteredResolvedDependencies = resolvedDependencies.findAll { ResolvedDependency resolved ->
                    filter(resolved.moduleGroup, resolved.moduleName, resolved.moduleVersion)
                }

                filteredResolvedDependencies.each { ResolvedDependency resolved ->
                    def key = new LockKey(group: resolved.moduleGroup, artifact: resolved.moduleName, configuration: configuration.name)

                    // If this dependency does not exist in our list of peers, it is a standard dependency. Otherwise, it is
                    // a project dependency.
                    if (!isKeyInPeerList(key, peers)) {
                        deps[key].locked = resolved.moduleVersion
                    } else {
                        // Project dependencies don't have a version so they must be treated differently. Record the project
                        // as an explicit dependency, but do not lock it to a version.
                        deps[key].project = true

                        // If we don't include transitive dependencies, then we must lock the first-level "transitive"
                        // dependencies of each project dependency.
                        if (!getIncludeTransitives()) {
                            handleSiblingTransitives(resolved, configuration.name, deps, peers)
                        }
                    }

                    // If requested, lock all the transitive dependencies of the declared top-level dependencies.
                    if (getIncludeTransitives()) {
                        deps[key].childrenVisited = true
                        resolved.children.each { handleTransitive(it, configuration.name, deps, peers, key) }
                    }
                }
            }

            // Add all the overrides to the locked dependencies and record whether a specified override modified a
            // preexisting dependency.
            getOverrides().each { String k, String overrideVersion ->
                def (overrideGroup, overrideArtifact) = k.tokenize(':')
                deps.each { depLockKey, depValue ->
                    if (depLockKey.group == overrideGroup && depLockKey.artifact == overrideArtifact) {
                        depValue.viaOverride = overrideVersion
                    }
                }
            }

            return deps
        }

        private void handleSiblingTransitives(ResolvedDependency sibling, String configName, Map<LockKey, LockValue> deps, List peers) {
            def parent = new LockKey(group: sibling.moduleGroup, artifact: sibling.moduleName, configuration: sibling.configuration)
            sibling.children.each { ResolvedDependency dependency ->
                def key = new LockKey(group: dependency.moduleGroup, artifact: dependency.moduleName, configuration: configName)

                // Record the project[s] from which this dependency originated.
                deps[key].firstLevelTransitive << parent

                // Lock the transitive dependencies of each project dependency, recursively.
                if (isKeyInPeerList(key, peers)) {
                    deps[key].project = true

                    // Multiple configurations may specify dependencies on the same project, and multiple projects might
                    // also be dependent on the same project. We only need to record the top-level transitive dependencies
                    // once for each project. Flag a project as visited as soon as we encounter it.
                    if ((dependency.children.size() > 0) && !deps[key].childrenVisited) {
                        deps[key].childrenVisited = true
                        handleSiblingTransitives(dependency, configName, deps, peers)
                    }
                } else {
                    deps[key].locked = dependency.moduleVersion
                }
            }
        }

        private void handleTransitive(ResolvedDependency transitive, String configName, Map<LockKey, LockValue> deps, List peers, LockKey parent) {
            def key = new LockKey(group: transitive.moduleGroup, artifact: transitive.moduleName, configuration: configName)

            // Multiple dependencies may share any subset of their transitive dependencies. Each dependency only needs to be
            // visited once so flag it once we visit it.
            if (!deps[key].childrenVisited) {

                // Lock each dependency and its children, recursively. Don't forget transitive project dependencies.
                if (!isKeyInPeerList(key, peers)) {
                    deps[key].locked = transitive.moduleVersion
                } else {
                    deps[key].project = true
                }
                if (transitive.children.size() > 0) {
                    deps[key].childrenVisited = true
                }
                transitive.children.each { handleTransitive(it, configName, deps, peers, key) }
            }

            // Record the dependencies from which this artifact originated transitively.
            deps[key].transitive << parent
        }

        private static def isKeyInPeerList(LockKey lockKey, List peers) {
            return peers.any {
                it.group == lockKey.group && it.artifact == lockKey.artifact
            }
        }
    }

    class GenerateLockFromWayback {
        Map<LockKey, LockValue> lock(Collection<Configuration> confs) {
            if (!waybackProvider) {
                throw new DependencyLockException("In order to use wayback, you must configure a provider")
            }

            DependencyLockReader reader = new DependencyLockReader(project)

            def updateLocks = confs.collectEntries { conf ->
                [(conf.name): reader.readLocks(conf, getDependenciesLock(), [])?.collectEntries { k, v ->
                    // parse the string representation returned by readLocks into (LockKey, LockValue) entries
                    def (group, artifact) = (k as String).split(':')
                    [(new LockKey(group, artifact, conf.name)): v as LockValue]
                }]
            }

            confs.each { conf ->
                def wayback = waybackProvider.wayback(project.property('waybackTo') as String, conf)
                // we only override or create a new configuration to lock if wayback has some advice about it
                if (!wayback.isEmpty())
                    updateLocks[conf.name] = wayback.collectEntries { dep -> [(new LockKey(dep.group, dep.name, conf.name)): new LockValue(locked: dep.version)] }
            }

            return updateLocks.values().findAll { it }.sum() as Map<LockKey, LockValue>
        }
    }
}

