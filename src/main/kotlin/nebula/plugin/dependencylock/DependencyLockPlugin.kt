/*
 * Copyright 2017 Netflix, Inc.
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
package nebula.plugin.dependencylock

import com.netflix.nebula.dependencybase.DependencyBasePlugin
import com.netflix.nebula.dependencybase.DependencyManagement
import nebula.plugin.dependencylock.exceptions.DependencyLockException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.internal.artifacts.DefaultModuleVersionSelector
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.util.NameMatcher
import java.io.File

class DependencyLockPlugin : Plugin<Project> {
    companion object {
        const val EXTENSION_NAME = "dependencyLock"
        const val COMMIT_EXTENSION_NAME = "commitDependencyLock"
        const val GLOBAL_LOCK_FILE = "dependencyLock.globalLockFile"
        const val LOCK_AFTER_EVALUATING = "dependencyLock.lockAfterEvaluating"
        const val UPDATE_DEPENDENCIES = "dependencyLock.updateDependencies"
        const val OVERRIDE = "dependencyLock.override"
        const val OVERRIDE_FILE = "dependencyLock.overrideFile"
        const val GENERATE_GLOBAL_LOCK_TASK_NAME = "generateGlobalLock"
        const val UPDATE_GLOBAL_LOCK_TASK_NAME = "updateGlobalLock"
        const val GENERATE_LOCK_TASK_NAME = "generateLock"
        const val UPDATE_LOCK_TASK_NAME = "updateLock"

        val GENERATION_TASK_NAMES = setOf(GENERATE_LOCK_TASK_NAME, GENERATE_GLOBAL_LOCK_TASK_NAME, UPDATE_LOCK_TASK_NAME, UPDATE_GLOBAL_LOCK_TASK_NAME)
        val UPDATE_TASK_NAMES = setOf(UPDATE_LOCK_TASK_NAME, UPDATE_GLOBAL_LOCK_TASK_NAME)
    }

    val LOGGER: Logger = Logging.getLogger(DependencyLockPlugin::class.java)

    lateinit var project: Project
    lateinit var lockReader: DependencyLockReader
    lateinit var insight: DependencyManagement
    lateinit var lockUsed: String

    override fun apply(project: Project) {
        this.project = project
        this.lockReader = DependencyLockReader(project)

        project.plugins.apply(DependencyBasePlugin::class.java)
        this.insight = project.extensions.extraProperties.get("nebulaDependencyBase") as DependencyManagement

        val extension = project.extensions.create(EXTENSION_NAME, DependencyLockExtension::class.java)
        var commitExtension = project.rootProject.extensions.findByType(DependencyLockCommitExtension::class.java)
        if (commitExtension == null) {
            commitExtension = project.rootProject.extensions.create(COMMIT_EXTENSION_NAME, DependencyLockCommitExtension::class.java)
        }

        val overrides = lockReader.readOverrides()
        val globalLockFilename = project.findStringProperty(GLOBAL_LOCK_FILE)
        val lockFilename = DependencyLockTaskConfigurer(project).configureTasks(globalLockFilename, extension, commitExtension, overrides)

        val lockAfterEvaluating = if (project.hasProperty(LOCK_AFTER_EVALUATING)) project.property(LOCK_AFTER_EVALUATING).toString().toBoolean() else extension.lockAfterEvaluating
        if (lockAfterEvaluating) {
            LOGGER.info("Delaying dependency lock apply until beforeResolve ($LOCK_AFTER_EVALUATING set to true)")
        } else {
            LOGGER.info("Applying dependency lock during plugin apply ($LOCK_AFTER_EVALUATING set to false)")
        }

        // We do this twice to catch resolves that happen during build evaluation, and ensure that we clobber configurations made during evaluation
        disableCachingForGenerateLock()
        project.gradle.taskGraph.whenReady(groovyClosure {
            disableCachingForGenerateLock()
        })

        project.configurations.all({ conf ->
            if (lockAfterEvaluating) {
                conf.incoming.beforeResolve {
                    maybeApplyLock(conf, extension, overrides, globalLockFilename, lockFilename)
                }
            } else {
                maybeApplyLock(conf, extension, overrides, globalLockFilename, lockFilename)
            }
        })
    }

    private fun disableCachingForGenerateLock() {
        if (hasGenerationTask(project.gradle.startParameter.taskNames)) {
            project.configurations.all({ configuration ->
                if (configuration.state == Configuration.State.UNRESOLVED) {
                    with(configuration.resolutionStrategy) {
                        cacheDynamicVersionsFor(0, "seconds")
                        cacheChangingModulesFor(0, "seconds")
                    }
                }
            })
        }
    }

    private fun maybeApplyLock(conf: Configuration, extension: DependencyLockExtension, overrides: Map<*, *>, globalLockFileName: String?, lockFilename: String?) {
        val globalLock = File(project.rootProject.projectDir, globalLockFileName ?: extension.globalLockFile)
        val dependenciesLock = if (globalLock.exists()) {
            globalLock
        } else {
            File(project.projectDir, lockFilename ?: extension.lockFile)
        }

        lockUsed = dependenciesLock.name
        insight.addPluginMessage("nebula.dependency-lock locked with: $lockUsed")

        if (!DependencyLockTaskConfigurer.shouldIgnoreDependencyLock(project)) {
            val taskNames = project.gradle.startParameter.taskNames
            val hasUpdateTask = hasUpdateTask(taskNames)

            val updates = if (project.hasProperty(UPDATE_DEPENDENCIES)) parseUpdates(project.property(UPDATE_DEPENDENCIES) as String) else extension.updateDependencies
            val projectCoord = "${project.group}:${project.name}"
            if (hasUpdateTask && updates.any { it == projectCoord }) {
                throw DependencyLockException("Dependency locks cannot be updated. An update was requested for a project dependency ($projectCoord)")
            }

            val hasGenerateTask = hasGenerationTask(taskNames)
            if (dependenciesLock.exists()) {
                if (!hasGenerateTask) {
                    applyLock(conf, dependenciesLock)
                } else if (hasUpdateTask) {
                    applyLock(conf, dependenciesLock, updates)
                }
            }
            applyOverrides(conf, overrides)
        }
    }

    private fun hasGenerationTask(cliTasks: Collection<String>): Boolean =
            hasTask(cliTasks, GENERATION_TASK_NAMES)

    private fun hasUpdateTask(cliTasks: Collection<String>): Boolean =
            hasTask(cliTasks, UPDATE_TASK_NAMES)

    private fun hasTask(cliTasks: Collection<String>, taskNames: Collection<String>): Boolean {
        val matcher = NameMatcher()
        val found = cliTasks.find { cliTaskName ->
            val tokens = cliTaskName.split(":")
            val taskName = tokens.last()
            val generatesPresent = matcher.find(taskName, taskNames)
            generatesPresent != null && taskRunOnThisProject(tokens)
        }

        return found != null
    }

    private fun taskRunOnThisProject(tokens: List<String>): Boolean {
        if (tokens.size == 1) { // task run globally
            return true
        } else if (tokens.size == 2 && tokens[0] == "") { // running fully qualified on root project
            return project == project.rootProject
        } else { // the task is being run on a specific project
            return project.name == tokens[tokens.size - 2]
        }
    }

    private fun parseUpdates(updates: String): Set<String> =
            updates.split(",").toSet()

    private fun applyLock(conf: Configuration, dependenciesLock: File, updates: Set<String> = emptySet()) {
        LOGGER.info("Using ${dependenciesLock.name} to lock dependencies in $conf")
        val locks = lockReader.readLocks(conf, dependenciesLock, updates)
        if (locks != null) {
            // Non-project locks are the top-level dependencies, and possibly transitive thereof, of this project which are
            // locked by the lock file. There may also be dependencies on other projects. These are not captured here.
            val locked = locks.filter {
                (it.value as Map<*, *>).containsKey("locked")
            }.map {
                val locked = (it.value as Map<*, *>)["locked"] as String
                "${it.key}:$locked"
            }
            LOGGER.debug("locked: {}", locked)
            lockConfiguration(conf, locked)
        }
    }

    private fun applyOverrides(conf: Configuration, overrides: Map<*, *>) {
        if (project.hasProperty(OVERRIDE_FILE)) {
            LOGGER.info("Using override file ${project.property(OVERRIDE_FILE)} to lock dependencies")
            insight.addPluginMessage("nebula.dependency-lock using override file: ${project.property(OVERRIDE_FILE)}")
        }
        if (project.hasProperty(OVERRIDE)) {
            LOGGER.info("Using command line overrides ${project.property(OVERRIDE)}")
            insight.addPluginMessage("nebula.dependency-lock using override: ${project.property(OVERRIDE)}")
        }

        val overrideDeps = overrides.map { "${it.key}:${it.value}" }
        LOGGER.debug("overrides: {}", overrideDeps)
        lockConfiguration(conf, overrideDeps)
    }

    private fun lockConfiguration(conf: Configuration, dependencyNotations: List<String>) {
        val dependencies = dependencyNotations.map { project.dependencies.create(it) }
        conf.resolutionStrategy.eachDependency { details ->
            dependencies.forEach { dep ->
                if (details.requested.group == dep.group && details.requested.name == dep.name) {
                    val module = DefaultModuleVersionSelector(details.requested.group, details.requested.name, dep.version)
                    details.useTarget(module)
                    insight.addLock(conf.name, "${dep.group}:${dep.name}", dep.version, lockUsed, "nebula.dependency-lock")
                }
            }
        }
    }
}
