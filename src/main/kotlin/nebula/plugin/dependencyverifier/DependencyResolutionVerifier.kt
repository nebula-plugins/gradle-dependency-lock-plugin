/**
 *
 *  Copyright 2020 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package nebula.plugin.dependencyverifier

import nebula.plugin.dependencylock.DependencyLockPlugin
import nebula.plugin.dependencylock.DependencyLockTaskConfigurer
import nebula.plugin.dependencylock.utils.ConfigurationFilters
import nebula.plugin.dependencylock.utils.DependencyLockingFeatureFlags
import nebula.plugin.dependencyverifier.exceptions.DependencyResolutionException
import org.gradle.BuildResult
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolveException
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.TaskState
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.diagnostics.DependencyInsightReportTask
import org.gradle.api.tasks.diagnostics.DependencyReportTask
import org.gradle.internal.exceptions.DefaultMultiCauseException
import org.gradle.internal.locking.LockOutOfDateException
import org.gradle.internal.resolve.ModuleVersionNotFoundException
import java.lang.Boolean.getBoolean

const val UNRESOLVED_DEPENDENCIES_FAIL_THE_BUILD: String = "dependencyResolutionVerifier.unresolvedDependenciesFailTheBuild"
const val CONFIGURATIONS_TO_EXCLUDE: String = "dependencyResolutionVerifier.configurationsToExclude"

class DependencyResolutionVerifier {

    companion object {
        var failedDependenciesPerProjectForConfigurations: MutableMap<String, MutableMap<String, MutableSet<Configuration>>> = mutableMapOf()
        var lockedDepsOutOfDatePerProject: MutableMap<String, MutableSet<String>> = mutableMapOf()
        var depsWhereResolvedVersionIsNotTheLockedVersionPerProjectForConfigurations: MutableMap<String, MutableMap<String, MutableSet<Configuration>>> = mutableMapOf()
    }

    private val logger by lazy { Logging.getLogger(DependencyResolutionVerifier::class.java) }
    private var providedErrorMessageForThisProject = false

    lateinit var project: Project
    lateinit var extension: DependencyResolutionVerifierExtension
    lateinit var configurationsToExcludeOverride: MutableSet<String>

    fun verifySuccessfulResolution(project: Project) {
        this.project = project
        this.extension = project.rootProject.extensions.findByType(DependencyResolutionVerifierExtension::class.java)!!
        this.configurationsToExcludeOverride = mutableSetOf()
        if (project.hasProperty(CONFIGURATIONS_TO_EXCLUDE)) {
            configurationsToExcludeOverride.addAll((project.property(CONFIGURATIONS_TO_EXCLUDE) as String).split(","))
        }
        val uniqueProjectKey = uniqueProjectKey(project)
        failedDependenciesPerProjectForConfigurations[uniqueProjectKey] = mutableMapOf()
        lockedDepsOutOfDatePerProject[uniqueProjectKey] = mutableSetOf()
        depsWhereResolvedVersionIsNotTheLockedVersionPerProjectForConfigurations[uniqueProjectKey] = mutableMapOf()

        verifyResolution(project)
    }

    private fun verifyResolution(project: Project) {
        project.gradle.buildFinished { buildResult ->
            val buildFailed: Boolean = buildResult.failure != null
            if (buildFailed && !providedErrorMessageForThisProject) {
                collectDependencyResolutionErrorsAfterBuildFailure(buildResult)
            }
            if (!providedErrorMessageForThisProject) {
                logOrThrowOnFailedDependencies()
            }
        }

        project.gradle.taskGraph.whenReady { taskGraph ->
            val tasks: List<Task> = taskGraph.allTasks.filter { it.project == project }
            if (tasks.isEmpty()) {
                return@whenReady
            }

            taskGraph.addTaskExecutionListener(object : TaskExecutionListener {
                override fun beforeExecute(task: Task) {
                    //DO NOTHING
                }

                override fun afterExecute(task: Task, taskState: TaskState) {
                    if (task.project != project) {
                        return
                    }
                    if (extension.tasksToExclude.contains(task.name)) {
                        return
                    }
                    if (providedErrorMessageForThisProject) {
                        return
                    }
                    if (task !is DependencyReportTask && task !is DependencyInsightReportTask && task !is AbstractCompile) {
                        return
                    }
                    collectDependencyResolutionErrorsAfterExecute(task)
                    logOrThrowOnFailedDependencies()
                }
            })
        }
    }

    private fun collectDependencyResolutionErrorsAfterBuildFailure(buildResult: BuildResult) {
        val failureCause = buildResult.failure?.cause?.cause
        if (failureCause == null || failureCause !is DefaultMultiCauseException) {
            return
        }
        val moduleVersionNotFoundCauses: List<Throwable> = failureCause.causes.filterIsInstance<ModuleVersionNotFoundException>()
        if (moduleVersionNotFoundCauses.isEmpty()) {
            return
        }
        val buildResultFailureMessage = failureCause.message
        val split = buildResultFailureMessage!!.split(":")
        val projectNameFromFailure: String
        projectNameFromFailure = if (split.size == 3) {
            split[1]
        } else {
            project.rootProject.name
        }
        if (project.name == projectNameFromFailure) {
            logger.debug("Starting dependency resolution verification after the build has completed: $buildResultFailureMessage")

            val conf: Configuration
            try {
                val confName: String = buildResultFailureMessage.replace(".", "").split("for ")[1]
                conf = project.configurations.first { it.toString() == confName }
                logger.debug("Found $conf from $confName")
            } catch (e: Exception) {
                logger.warn("Error finding configuration associated with build failure from '${buildResultFailureMessage}'", e)
                return
            }

            val failedDepsByConf = failedDependenciesPerProjectForConfigurations[uniqueProjectKey(project)]
            moduleVersionNotFoundCauses.forEach {
                require(it is ModuleVersionNotFoundException)

                val dep: String = it.selector.toString()
                if (failedDepsByConf!!.containsKey(dep)) {
                    failedDepsByConf[dep]!!.add(conf)
                } else {
                    failedDepsByConf[dep] = mutableSetOf(conf)
                }
            }
        }
    }

    private fun collectDependencyResolutionErrorsAfterExecute(task: Task) {
        val failedDepsByConf = failedDependenciesPerProjectForConfigurations[uniqueProjectKey(project)]
        val lockedDepsOutOfDate = lockedDepsOutOfDatePerProject[uniqueProjectKey(project)]
        val configurationsToExclude = if (configurationsToExcludeOverride.isNotEmpty()) configurationsToExcludeOverride else extension.configurationsToExclude

        task.project.configurations.matching { // returns a live collection
            configurationIsResolvedAndMatches(it, configurationsToExclude)
        }.all { conf ->
            logger.debug("$conf in ${project.name} has state ${conf.state}. Starting dependency resolution verification after task '${task.name}'.")
            try {
                conf.resolvedConfiguration.rethrowFailure()
            } catch (e: ResolveException) {
                e.causes.forEach { cause ->
                    when (cause) {
                        is ModuleVersionNotFoundException -> {
                            val dep: String = cause.selector.toString()
                            if (failedDepsByConf!!.containsKey(dep)) {
                                failedDepsByConf[dep]!!.add(conf)
                            } else {
                                failedDepsByConf[dep] = mutableSetOf(conf)
                            }
                        }
                        is LockOutOfDateException -> {
                            lockedDepsOutOfDate!!.add(cause.message.toString())
                        }
                    }
                }
                return@all
            } catch (e : Exception) {
                logger.warn("Received an unhandled exception: {}", e.message)
                return@all
            }

            validateThatResolvedVersionIsLockedVersion(conf)
        }
    }

    private fun validateThatResolvedVersionIsLockedVersion(conf: Configuration) {
        val usesNebulaAlignment = !getBoolean("nebula.features.coreAlignmentSupport")
        val usesDependencyLockingFeatureFlags = DependencyLockingFeatureFlags.isCoreLockingEnabled()
        if (usesDependencyLockingFeatureFlags || usesNebulaAlignment) {
            // short-circuit unless using Nebula locking & core alignment
            return
        }
        if (project.gradle.startParameter.taskNames.contains(DependencyLockTaskConfigurer.UPDATE_LOCK_TASK_NAME) ||
            project.gradle.startParameter.taskNames.contains(DependencyLockTaskConfigurer.UPDATE_GLOBAL_LOCK_TASK_NAME)
        ) {
            // short-circuit when selectively updating locks. The lock-reading for dependencies that are updated
            // transitively or from a recommendation BOM or from aligned dependencies getting updated can provide false-positives
            return
        }
        val depsWhereResolvedVersionIsNotTheLockedVersionByConf =
            depsWhereResolvedVersionIsNotTheLockedVersionPerProjectForConfigurations[uniqueProjectKey(project)]
        val lockedDepsByConf = DependencyLockPlugin.lockedDepsPerProjectForConfigurations[uniqueProjectKey(project)]
        val overrideDepsByConf = DependencyLockPlugin.overrideDepsPerProjectForConfigurations[uniqueProjectKey(project)]
        val lockedDependencies: Map<String, String> = lockedDepsByConf!![conf.name]
            ?.associateBy({ "${it.group}:${it.name}" }, { "${it.version}" })
            ?: emptyMap()
        val overrideDependencies: Map<String, String> = overrideDepsByConf!![conf.name]
            ?.associateBy({ "${it.group}:${it.name}" }, { "${it.version}" })
            ?: emptyMap()
        if (lockedDependencies.isEmpty() && overrideDependencies.isEmpty()) {
            // short-circuit when locks are empty
            return
        }

        conf.resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id }.forEach { dep ->
            val lockedVersion: String = lockedDependencies["${dep.group}:${dep.name}"] ?: ""
            val overrideVersion: String = overrideDependencies["${dep.group}:${dep.name}"] ?: ""

            val expectedVersion = when {
                overrideVersion.isNotEmpty() -> overrideVersion
                lockedVersion.isNotEmpty() -> lockedVersion
                else -> ""
            }

            if (expectedVersion.isNotEmpty() && expectedVersion != dep.version) {
                val depAsString = "${dep.group}:${dep.name}:${dep.version}"
                val key = "'$depAsString' instead of locked version '$expectedVersion'"
                if (depsWhereResolvedVersionIsNotTheLockedVersionByConf!!.containsKey(dep.toString())) {
                    depsWhereResolvedVersionIsNotTheLockedVersionByConf[key]!!.add(conf)
                } else {
                    depsWhereResolvedVersionIsNotTheLockedVersionByConf[key] = mutableSetOf(conf)
                }
            }
        }
    }

    private fun logOrThrowOnFailedDependencies() {
        val messages: MutableList<String> = mutableListOf()

        val failedDepsByConf = failedDependenciesPerProjectForConfigurations[uniqueProjectKey(project)]!!
        val lockedDepsOutOfDate = lockedDepsOutOfDatePerProject[uniqueProjectKey(project)]!!
        val depsWhereResolvedVersionIsNotTheLockedVersionByConf = depsWhereResolvedVersionIsNotTheLockedVersionPerProjectForConfigurations[uniqueProjectKey(project)]!!

        if (failedDepsByConf.isNotEmpty() || lockedDepsOutOfDate.isNotEmpty() || depsWhereResolvedVersionIsNotTheLockedVersionByConf.isNotEmpty()) {
            try {
                messages.addAll(createMessagesForFailedDeps(failedDepsByConf))
                messages.addAll(createMessagesForLockedDepsOutOfDate(lockedDepsOutOfDate))
                messages.addAll(createMessagesForDepsWhereResolvedVersionIsNotTheLockedVersion(depsWhereResolvedVersionIsNotTheLockedVersionByConf))
            } catch (e: Exception) {
                logger.warn("Error creating message regarding failed dependencies", e)
                return
            }

            providedErrorMessageForThisProject = true
            if (unresolvedDependenciesShouldFailTheBuild()) {
                throw DependencyResolutionException(messages.joinToString("\n"))
            } else {
                logger.warn(messages.joinToString("\n"))
            }
        }
    }

    private fun createMessagesForFailedDeps(failedDepsForConfs: MutableMap<String, MutableSet<Configuration>>): MutableList<String> {
        val messages: MutableList<String> = mutableListOf()
        val depsMissingVersions: MutableList<String> = mutableListOf()

        if (failedDepsForConfs.isNotEmpty()) {
            messages.add("Failed to resolve the following dependencies:")
        }
        var failureMessageCounter = 0
        failedDepsForConfs.toSortedMap().forEach { (dep, _) ->
            messages.add("  ${failureMessageCounter + 1}. Failed to resolve '$dep' for project '${project.name}'")

            if (dep.split(':').size < 3) {
                depsMissingVersions.add(dep)
            }

            failureMessageCounter += 1
        }

        if (depsMissingVersions.size > 0) {
            messages.add("The following dependencies are missing a version: ${depsMissingVersions.joinToString()}\n" +
                    "Please add a version to fix this. If you have been using a BOM, perhaps these dependencies are no longer managed. \n"
                    + extension.missingVersionsMessageAddition)
        }
        return messages
    }

    private fun createMessagesForLockedDepsOutOfDate(lockedDepsOutOfDate: MutableSet<String>): MutableList<String> {
        val messages: MutableList<String> = mutableListOf()
        if (lockedDepsOutOfDate.isNotEmpty()) {
            messages.add("Resolved dependencies were missing from the lock state:")
        }

        var locksOutOfDateCounter = 0
        lockedDepsOutOfDate
                .sorted()
                .forEach { outOfDateMessage ->
                    messages.add("  ${locksOutOfDateCounter + 1}. $outOfDateMessage for project '${project.name}'")
                    locksOutOfDateCounter += 1
                }
        return messages
    }

    private fun createMessagesForDepsWhereResolvedVersionIsNotTheLockedVersion(depsWhereResolvedVersionIsNotTheLockedVersionByConf: MutableMap<String, MutableSet<Configuration>>): MutableList<String> {
        val messages: MutableList<String> = mutableListOf()
        if (depsWhereResolvedVersionIsNotTheLockedVersionByConf.isNotEmpty()) {
            messages.add("Dependency lock state is out of date:")
        }
        var failureMessageCounter = 0
        depsWhereResolvedVersionIsNotTheLockedVersionByConf.toSortedMap().forEach { (dep, configs) ->
            messages.add("  ${failureMessageCounter + 1}. Resolved $dep for project '${project.name}' for configuration(s): ${configs.joinToString(",") { it.name }}")

            failureMessageCounter += 1
        }

        if (depsWhereResolvedVersionIsNotTheLockedVersionByConf.isNotEmpty()) {
            messages.add("Please update your dependency locks or your build file constraints.\n" + extension.resolvedVersionDoesNotEqualLockedVersionMessageAddition)
        }
        return messages
    }

    private fun configurationIsResolvedAndMatches(conf: Configuration, configurationsToExclude: Set<String>): Boolean {
        return conf.state != Configuration.State.UNRESOLVED &&
                // the configurations `incrementalScalaAnalysisFor_x_` are resolvable only from a scala context
                !conf.name.startsWith("incrementalScala") &&
                !configurationsToExclude.contains(conf.name) &&
                !ConfigurationFilters.safelyHasAResolutionAlternative(conf) &&
                // Always exclude compileOnly to avoid issues with kotlin plugin
                !conf.name.endsWith("CompileOnly") &&
                !conf.name.equals("compileOnly")
    }

    private fun unresolvedDependenciesShouldFailTheBuild(): Boolean {
        return if (project.hasProperty(UNRESOLVED_DEPENDENCIES_FAIL_THE_BUILD)) {
            (project.property(UNRESOLVED_DEPENDENCIES_FAIL_THE_BUILD) as String).toBoolean()
        } else {
            extension.shouldFailTheBuild
        }
    }

    private fun uniqueProjectKey(project: Project): String {
        return "${project.name}-${if (project == project.rootProject) "rootproject" else "subproject"}"
    }
}
