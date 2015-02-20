/*
 * Copyright 2014 Netflix, Inc.
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

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.tasks.TaskAction

class GenerateLockTask extends AbstractLockTask {
    String description = 'Create a lock file in build/<specified name>'
    Closure filter = { group, name, version -> true }
    Set<String> configurationNames
    Set<String> skippedDependencies = []
    File dependenciesLock
    Map overrides
    boolean includeTransitives = false

    @TaskAction
    void lock() {
        def dependencyMap = readDependenciesFromConfigurations()
        writeLock(dependencyMap)
    }

    private readDependenciesFromConfigurations() {
        def deps = [:].withDefault { [transitive: [] as Set, firstLevelTransitive: [] as Set, childrenVisited: false] }
        def confs = getConfigurationNames().collect { project.configurations.getByName(it) }

        // Peers are all the projects in the build to which this plugin has been applied.
        def peers = project.rootProject.allprojects.collect { new LockKey(group: it.group, artifact: it.name) }

        confs.each { Configuration configuration ->

            // Capture the version of each dependency as requested in the build script for reference.
            def externalDependencies = configuration.allDependencies.withType(ExternalDependency)
            def filteredExternalDependencies = externalDependencies.findAll { Dependency dependency ->
                filter(dependency.group, dependency.name, dependency.version)
            }
            filteredExternalDependencies.each { ExternalDependency dependency ->
                def key = new LockKey(group: dependency.group, artifact: dependency.name)
                deps[key].requested = dependency.version
            }

            // Lock the version of each dependency specified in the build script as resolved by Gradle.
            def resolvedDependencies = configuration.resolvedConfiguration.firstLevelModuleDependencies
            def filteredResolvedDependencies = resolvedDependencies.findAll { ResolvedDependency resolved ->
                filter(resolved.moduleGroup, resolved.moduleName, resolved.moduleVersion)
            }
            filteredResolvedDependencies.each { ResolvedDependency resolved ->
                def key = new LockKey(group: resolved.moduleGroup, artifact: resolved.moduleName)

                // If this dependency does not exist in our list of peers, it is a standard dependency. Otherwise, it is
                // a project dependency.
                if (!peers.contains(key)) {
                    deps[key].locked = resolved.moduleVersion
                } else {
                    // Project dependencies don't have a version so they must be treated differently. Record the project
                    // as an explicit dependency, but do not lock it to a version.
                    deps[key].project = true

                    // If we don't include transitive dependencies, then we must lock the first-level "transitive"
                    // dependencies of each project dependency.
                    if (!getIncludeTransitives()) {
                        handleSiblingTransitives(resolved, deps, peers)
                    }
                }

                // If requested, lock all the transitive dependencies of the declared top-level dependencies.
                if (getIncludeTransitives()) {
                    deps[key].childrenVisited = true
                    resolved.children.each { handleTransitive(it, deps, peers, key) }
                }
            }
        }

        // Add all the overrides to the locked dependencies and record whether a specified override modified a
        // preexisting dependency.
        getOverrides().each { String k, String overrideVersion ->
            def tokens = k.tokenize(':')
            LockKey key = new LockKey(group: tokens[0], artifact: tokens[1] )
            if (deps.containsKey(key)) {
                deps[key].viaOverride = overrideVersion
            }
        }

        return deps
    }

    void handleSiblingTransitives(ResolvedDependency sibling, Map deps, List peers) {
        def parent = new LockKey(group: sibling.moduleGroup, artifact: sibling.moduleName)
        sibling.children.each { ResolvedDependency dependency ->
            def key = new LockKey(group: dependency.moduleGroup, artifact: dependency.moduleName)

            // Record the project[s] from which this dependency originated.
            deps[key].firstLevelTransitive << parent

            // Lock the transitive dependencies of each project dependency, recursively.
            if (peers.contains(key)) {
                deps[key].project = true

                // Multiple configurations may specify dependencies on the same project, and multiple projects might
                // also be dependent on the same project. We only need to record the top-level transitive dependencies
                // once for each project. Flag a project as visited as soon as we encounter it.
                if ((dependency.children.size() > 0) && !deps[key].childrenVisited) {
                    deps[key].childrenVisited = true
                    handleSiblingTransitives(dependency, deps, peers)
                }
            } else {
                deps[key].locked = dependency.moduleVersion
            }
        }
    }

    void handleTransitive(ResolvedDependency transitive, Map deps, List peers, LockKey parent) {
        def key = new LockKey(group: transitive.moduleGroup, artifact: transitive.moduleName)

        // Multiple dependencies may share any subset of their transitive dependencies. Each dependency only needs to be
        // visited once so flag it once we visit it.
        if (!deps[key].childrenVisited) {

            // Lock each dependency and its children, recursively. Don't forget transitive project dependencies.
            if (!peers.contains(key)) {
                deps[key].locked = transitive.moduleVersion
            } else {
                deps[key].project = true
            }
            if (transitive.children.size() > 0) {
                deps[key].childrenVisited = true
            }
            transitive.children.each { handleTransitive(it, deps, peers, key) }
        }

        // Record the dependencies from which this artifact originated transitively.
        deps[key].transitive << parent
    }

    void writeLock(deps) {
        def strings = deps.findAll { !getSkippedDependencies().contains(it.key.toString()) }
                .collect { LockKey k, Map v -> stringifyLock(k, v) }
        strings = strings.sort()
        project.buildDir.mkdirs()
        getDependenciesLock().withPrintWriter { out ->
            out.println '{'
            out.println strings.join(',\n')
            out.println '}'
        }
    }

    String stringifyLock(LockKey key, Map lock) {
        def lockLine = new StringBuilder("  \"${key}\": { ")
        if (lock.locked) {
            lockLine << "\"locked\": \"${lock.locked}\""
        } else {
            lockLine << '"project": true'
        }
        if (lock.requested) {
            lockLine << ", \"requested\": \"${lock.requested}\""
        }
        if (lock.viaOverride) {
            lockLine << ", \"viaOverride\": \"${lock.viaOverride}\""
        }
        if (lock.transitive) {
            def transitiveFrom = lock.transitive.sort().collect { "\"${it}\""}.join(', ')
            lockLine << ", \"transitive\": [ ${transitiveFrom} ]"
        }
        if (lock.firstLevelTransitive) {
            def transitiveFrom = lock.firstLevelTransitive.sort().collect { "\"${it}\""}.join(', ')
            lockLine << ", \"firstLevelTransitive\": [ ${transitiveFrom} ]"
        }
        lockLine << ' }'

        return lockLine.toString()
    }
}
