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

import groovy.json.JsonBuilder
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

class GenerateLockTask extends AbstractLockTask {
    String description = 'Create a lock file in build/<configured name>'

    @Input
    Set<String> configurationNames

    @OutputFile
    File dependenciesLock

    @TaskAction
    void lock() {
        def dependencyMap = readDependenciesFromConfigurations()
        writeLock(dependencyMap)
    }

    private readDependenciesFromConfigurations() {
        Set<LockedDependency> dependencies = []
        def confs = getConfigurationNames().collect { project.configurations.getByName(it) }
        confs.each { Configuration configuration ->
            Set<ProjectDependency> peers = configuration.allDependencies.withType(ProjectDependency)
            configuration.resolvedConfiguration.firstLevelModuleDependencies.each { ResolvedDependency resolved ->
                DependencyCollector.collect(resolved, dependencies, peers)
            }
        }

        return dependencies
    }

    private void writeLock(deps) {
        def builder = new JsonBuilder()
        builder deps.sort({ "${it.moduleGroup}:${it.moduleName}" }).collectEntries { d ->
            def key = "${d.moduleGroup}:${d.moduleName}"
            def val = [locked: d.moduleVersion]
            [key, val]
        }
        getDependenciesLock().text = builder.toString()
    }

    private static class LockedDependency implements ResolvedDependency {
        @Delegate ResolvedDependency resolved
        boolean transitive
    }

    private static class DependencyCollector {
        public static void collect(ResolvedDependency resolved, Set<ResolvedDependency> deps, Set<ProjectDependency> peers, boolean transitive = false) {
            if (null == peers.find { it.group == resolved.moduleGroup && it.name == resolved.moduleName}) {
                if (null == deps.find { it.module.id == resolved.module.id }) {
                    deps.add([resolved: resolved, transitive: transitive] as LockedDependency)
                }
                resolved.getChildren().each {
                    collect(it, deps, peers, true)
                }
            }
        }
    }
}
