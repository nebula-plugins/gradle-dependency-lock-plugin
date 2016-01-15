/*
 * Copyright 2014-2016 Netflix, Inc.
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

import groovy.json.JsonOutput
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.tasks.TaskAction

import static nebula.plugin.dependencylock.DependencyLockPlugin.*

class GenerateLockTask extends AbstractLockTask {
    String description = 'Create a lock file in build/<configured name>'
    Collection<Configuration> configurations = []
    Set<String> configurationNames
    Closure filter = { group, name, version -> true }
    Set<String> skippedDependencies = []
    File dependenciesLock
    Map overrides
    boolean includeTransitives = false

    @TaskAction
    void lock() {
        Collection<Configuration> confs = getConfigurations() ?: getConfigurationsFromConfigurationNames(project, getConfigurationNames())
        def dependencyMap = readDependenciesFromConfigurations(confs)
        writeLock(dependencyMap)
    }

    public static Collection<Configuration> getConfigurationsFromConfigurationNames(Project project, Set<String> configurationNames) {
        configurationNames.collect { project.configurations.getByName(it) }
    }

    Map readDependenciesFromConfigurations(Collection<Configuration> confs) {
        def deps = [:].withDefault { [transitive: [] as Set, firstLevelTransitive: [] as Set, childrenVisited: false] }

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

    void handleSiblingTransitives(ResolvedDependency sibling, String configName, Map deps, List peers) {
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

    void handleTransitive(ResolvedDependency transitive, String configName, Map deps, List peers, LockKey parent) {
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

    def isKeyInPeerList(LockKey lockKey, List peers) {
        return peers.any {
            it.group == lockKey.group && it.artifact == lockKey.artifact
        }
    }

    void writeLock(deps) {

        // The result map maps configuration -> map of group:artifact -> map of dep properties -> values. The result
        // would then be transformed into Json. For example:
        // {
        //    "runtime": {
        //       "test.example:foo": { locked: "2.0.0", "transitive: ["test:sub1", "test:sub2"] }
        //       "test:sub1": { "project: true" }
        //       "test:sub2": { "project: true" }
        //    }
        //    "default": {
        //       "test.example:foo": { locked: "2.0.0", "transitive: ["test:sub1", "test:sub2"] }
        //       "test:sub1": { "project: true" }
        //       "test:sub2": { "project: true" }
        //    }
        // }

        def result = new TreeMap().withDefault { new TreeMap().withDefault { new TreeMap() } }

        def filteredSkippedDeps = deps.findAll {
            LockKey k, v -> !getSkippedDependencies().contains("${k.group}:${k.artifact}" as String)
        }

        filteredSkippedDeps.each { key, lock ->
            def configuration = key.configuration.startsWith('detachedConfiguration') ? GLOBAL_LOCK_CONFIG : key.configuration
            def depMap = result[configuration]["${key.group}:${key.artifact}"]
            if (lock.locked) {
                depMap['locked'] = lock.locked
            } else {
                depMap['project'] = true
            }
            if (lock.requested) {
                depMap['requested'] = lock.requested
            }
            if (lock.viaOverride) {
                depMap['viaOverride'] = lock.viaOverride
            }
            if (lock.transitive) {
                def transitiveFrom = lock.transitive.collect { "${it.group}:${it.artifact}" }.sort()
                depMap['transitive'] = transitiveFrom
            }
            if (lock.firstLevelTransitive) {
                def transitiveFrom = lock.firstLevelTransitive.collect { "${it.group}:${it.artifact}" }.sort()
                depMap['firstLevelTransitive'] = transitiveFrom
            }
        }

        project.buildDir.mkdirs()
        getDependenciesLock().text = JsonOutput.prettyPrint(JsonOutput.toJson(result))
    }
}
