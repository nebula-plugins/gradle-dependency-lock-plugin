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

import groovy.transform.EqualsAndHashCode
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.tasks.TaskAction

class GenerateLockTask extends AbstractLockTask {
    String description = 'Create a lock file in build/<configured name>'
    Set<String> configurationNames
    File dependenciesLock
    Map overrides
    Boolean includeTransitives

    @TaskAction
    void lock() {
        def dependencyMap = readDependenciesFromConfigurations()
        writeLock(dependencyMap)
    }

    private readDependenciesFromConfigurations() {
        def deps = [:].withDefault { [via: [] as Set, childrenVisited: false] }
        def confs = getConfigurationNames().collect { project.configurations.getByName(it) }

        def peers = project.rootProject.allprojects.collect { new LockKey(group: it.group, artifact: it.name) }

        confs.each { Configuration configuration ->
            configuration.allDependencies.withType(ExternalDependency).each { Dependency dependency ->
                def key = new LockKey(group: dependency.group, artifact: dependency.name)
                deps[key].requested = dependency.version
            }
            configuration.resolvedConfiguration.firstLevelModuleDependencies.each { ResolvedDependency resolved ->
                def key = new LockKey(group: resolved.moduleGroup, artifact: resolved.moduleName)
                if (!peers.contains(key)) {
                    deps[key].locked = resolved.moduleVersion
                }
                if (getIncludeTransitives()) {
                    deps[key].childrenVisited = true
                    resolved.children.each { handleTransitive(it, deps, peers, key) }
                }
            }
        }

        getOverrides().each { String k, String overrideVersion ->
            def tokens = k.tokenize(':')
            LockKey key = new LockKey(group: tokens[0], artifact: tokens[1] )
            if (deps.containsKey(key)) {
                deps[key].viaOverride = overrideVersion
            }
        }

        deps = deps.findAll { LockKey k, Map v -> !peers.contains(k) }

        return deps
    }

    private static handleTransitive(ResolvedDependency transitive, Map deps, List peers, LockKey parent) {
        def key = new LockKey(group: transitive.moduleGroup, artifact: transitive.moduleName)

        if (!deps[key].childrenVisited) {
            if (!peers.contains(key)) {
                deps[key].locked = transitive.moduleVersion
            }
            deps[key].transitive = true
            if (transitive.children.size() > 0) {
                deps[key].childrenVisited = true
            }
            transitive.children.each { handleTransitive(it, deps, peers, key) }
        }
        deps[key].via << parent
    }

    private void writeLock(deps) {
        def strings = deps.collect { LockKey k, Map v -> stringifyLock(k, v) }
        strings = strings.sort()
        project.buildDir.mkdirs()
        getDependenciesLock().withPrintWriter { out ->
            out.println '{'
            out.println strings.join(',\n')
            out.println '}'
        }
    }

    private static String stringifyLock(LockKey key, Map lock) {
        def lockLine = new StringBuilder("  \"${key}\": { \"locked\": \"${lock.locked}\"")
        if (lock.requested) {
            lockLine << ", \"requested\": \"${lock.requested}\""
        }
        if (lock.transitive) {
            lockLine << ", \"transitive\": true"
        }
        if (lock.viaOverride) {
            lockLine << ", \"viaOverride\": \"${lock.viaOverride}\""
        }
        if (lock.via) {
            def transitiveFrom = lock.via.sort().collect { "\"${it}\""}.join(', ')
            lockLine << ", \"via\": [ ${transitiveFrom} ]"
        }
        lockLine << ' }'

        return lockLine.toString()
    }

    @EqualsAndHashCode
    private static class LockKey {
        String group
        String artifact

        @Override
        String toString() {
            "${group}:${artifact}"
        }
    }
}
