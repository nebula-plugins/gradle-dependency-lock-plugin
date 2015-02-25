package nebula.plugin.dependencylock.tasks

import groovy.json.JsonSlurper
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

import java.lang.Override
import org.gradle.api.GradleException
import org.gradle.api.internal.tasks.options.Option

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
        def pruned = pruneDeps(locked)
        super.writeLock(pruned + (updated as Map))
    }

    private pruneDeps(locked) {
        Set keys = []
        Set visited = []

        // Visit all nodes in a list of dependency trees once and record the lock key.
        Closure addKeysFrom
        addKeysFrom = { Set<ResolvedDependency> nodes ->
            keys += nodes.collect { ResolvedDependency dep ->
                visited += dep
                new LockKey(group: dep.moduleGroup, artifact: dep.moduleName)
            }
            Set unvisited = nodes*.children.flatten() - visited
            if (unvisited.size() > 0) {
                addKeysFrom.trampoline(unvisited)
            }  // bounce..
        }.trampoline()

        // Recursively generate keys for the entire dependency tree.
        Set dependencies = project.configurations*.resolvedConfiguration.firstLevelModuleDependencies.flatten()
        addKeysFrom(dependencies)

        // Prune dependencies from the lock file that are not needed by dependencies in the current build script.
        locked.findAll {
            keys.contains(it.key)
        }
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
        json.each { key, value ->
            def (group, artifact) = key.tokenize(':')
            lockKeyMap.put(new LockKey(group: group, artifact: artifact), value)
        }
        lockKeyMap
    }
}
