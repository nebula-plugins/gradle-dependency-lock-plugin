/*
 * Copyright 2015 Netflix, Inc.
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

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.internal.tasks.options.Option
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

/**
 * The update task is a generate task, it simply reads in the old locked dependencies and then overwrites the desired
 * dependencies per user request.
 */
class UpdateLockTask extends GenerateLockTask {
    private static Logger logger = Logging.getLogger(UpdateLockTask)

    String description = 'Apply updates to a preexisting lock file and write to build/<specified name>'
    Set<String> dependencies

    @Option(option = "dependencies", description = "Specify which dependencies to update via a comma-separated list")
    void setDependencies(String depsFromOption) {
        setDependencies(depsFromOption.tokenize(',') as Set)
    }

    void setDependencies(Set<String> dependencyList) {
        this.dependencies = dependencyList
    }

    @Override
    void lock() {
        // If the user specifies dependencies to update, ignore any filter specified by the build file and use our
        // own generated from the list of dependencies.
        def updates = getDependencies()

        if (updates) {
            filter = { group, artifact, version ->
                updates.contains("${group}:${artifact}".toString())
            }
        }
        super.lock()
    }

    @Override
    void writeLock(updated) {
        File currentLock = new File(project.projectDir, dependenciesLock.name)
        def locked = loadLock(currentLock)
        // pruneDeps calculates all dependencies in current build file and cleans the irrelevant ones from the locked file
        def pruned = prune(locked)
        super.writeLock(pruned + (updated as Map))
    }

    private prune(locked) {
        Set visited = []
        List stack = []

        project.configurations.each({ Configuration config ->
            def deps = config.resolvedConfiguration.firstLevelModuleDependencies.flatten()
            stack.addAll(deps)
            while (stack.size() > 0) {
                def resolvedDep = stack.pop()
                def lockKey = new LockKey(group: resolvedDep.moduleGroup, artifact: resolvedDep.moduleName, configuration: config.name)
                if (!visited.contains(lockKey)) {
                    visited.add(lockKey)
                    stack.addAll(resolvedDep.children)
                }
            }
        })

        return locked.findAll { visited.contains(it.key) }
    }


    private static loadLock(File lock) {
        def json
        try {
            json = new JsonSlurper().parseText(lock.text)
        } catch (ex) {
            logger.debug('Unreadable json file: ' + lock.text)
            logger.error('JSON unreadable')
            throw new GradleException("${lock.name} is unreadable or invalid json, terminating run", ex)
        }
        // Load the dependencies in the same form as they are read from the configurations.
        def lockKeyMap = [:].withDefault {
            [transitive: [] as Set, firstLevelTransitive: [] as Set, childrenVisited: false]
        }
        json.each { configuration, depMap ->
            depMap.each { key, value ->
                def (group, artifact) = key.tokenize(':')
                lockKeyMap.put(new LockKey(group: group, artifact: artifact, configuration: configuration), value)
            }
        }
        lockKeyMap
    }
}
