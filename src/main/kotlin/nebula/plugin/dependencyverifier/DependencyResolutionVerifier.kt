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

import nebula.plugin.dependencylock.utils.ConfigurationFilters
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

const val UNRESOLVED_DEPENDENCIES_FAIL_THE_BUILD: String = "dependencyResolutionVerifier.unresolvedDependenciesFailTheBuild"
const val CONFIGURATIONS_TO_EXCLUDE: String = "dependencyResolutionVerifier.configurationsToExclude"

class DependencyResolutionVerifier {

    companion object {
        var failedDependenciesPerProjectForConfigurations: MutableMap<String, MutableMap<String, MutableSet<Configuration>>> = mutableMapOf()
        var lockedDepsOutOfDatePerProject: MutableMap<String, MutableSet<String>> = mutableMapOf()
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
        if(project.hasProperty(CONFIGURATIONS_TO_EXCLUDE)) {
            configurationsToExcludeOverride.addAll((project.property(CONFIGURATIONS_TO_EXCLUDE) as String).split(","))
        }
        failedDependenciesPerProjectForConfigurations[uniqueProjectKey(project)] = mutableMapOf()
        lockedDepsOutOfDatePerProject[uniqueProjectKey(project)] = mutableSetOf()

        verifyResolution(project)
    }

    private fun verifyResolution(project: Project) {
        project.gradle.buildFinished { buildResult ->
            val buildFailed: Boolean = buildResult.failure != null
            if (buildFailed && !providedErrorMessageForThisProject) {
                collectDependencyResolutionErrorsAfterBuildFailure(buildResult)
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
                    if(task !is DependencyReportTask && task !is DependencyInsightReportTask && task !is AbstractCompile) {
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
        if(failureCause == null || failureCause !is DefaultMultiCauseException) {
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
                conf.resolvedConfiguration.resolvedArtifacts
            } catch (e: Exception) {
                when(e) {
                    is ResolveException -> {
                        e.causes.forEach { cause ->
                            when(cause) {
                                is ModuleVersionNotFoundException -> {
                                    val dep: String = cause.selector.toString()
                                    if(failedDepsByConf!!.containsKey(dep)) {
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
                    }

                    else -> {
                        logger.warn("Received an unhandled exception", e.message)
                    }
                }
            }
        }
    }

    private fun logOrThrowOnFailedDependencies() {
        val message: MutableList<String> = mutableListOf()
        val depsMissingVersions: MutableList<String> = mutableListOf()

        val failedDepsForConfs = failedDependenciesPerProjectForConfigurations[uniqueProjectKey(project)]!!
        val lockedDepsOutOfDate = lockedDepsOutOfDatePerProject[uniqueProjectKey(project)]!!
        if (failedDepsForConfs.isNotEmpty() || lockedDepsOutOfDate.isNotEmpty()) {
            try {
                if (failedDepsForConfs.isNotEmpty()) {
                    message.add("Failed to resolve the following dependencies:")
                }
                var failureMessageCounter = 0
                failedDepsForConfs.toSortedMap().forEach { (dep, _) ->
                    message.add("  ${failureMessageCounter + 1}. Failed to resolve '$dep' for project '${project.name}'")

                    if (dep.split(':').size < 3) {
                        depsMissingVersions.add(dep)
                    }

                    failureMessageCounter += 1
                }

                if (lockedDepsOutOfDate.isNotEmpty()) {
                    message.add("Resolved dependencies were missing from the lock state:")
                }

                var locksOutOfDateCounter = 0
                lockedDepsOutOfDate
                        .sorted()
                        .forEach { outOfDateMessage->
                            message.add("  ${locksOutOfDateCounter + 1}. $outOfDateMessage for project '${project.name}'")
                            locksOutOfDateCounter += 1
                        }

                if (depsMissingVersions.size > 0) {
                    message.add("The following dependencies are missing a version: ${depsMissingVersions.joinToString()}\n" +
                            "Please add a version to fix this. If you have been using a BOM, perhaps these dependencies are no longer managed. \n"
                            + extension.missingVersionsMessageAddition)
                }
            } catch (e: Exception) {
                logger.warn("Error creating message regarding failed dependencies", e)
                return
            }

            providedErrorMessageForThisProject = true
            if (unresolvedDependenciesShouldFailTheBuild()) {
                throw DependencyResolutionException(message.joinToString("\n"))
            } else {
                logger.warn(message.joinToString("\n"))
            }
        }
    }

    private fun configurationIsResolvedAndMatches(conf: Configuration, configurationsToExclude: Set<String>) : Boolean {
        return conf.state != Configuration.State.UNRESOLVED &&
                // the configurations `incrementalScalaAnalysisFor_x_` are resolvable only from a scala context
                !conf.name.startsWith("incrementalScala") &&
                !configurationsToExclude.contains(conf.name) &&
                !ConfigurationFilters.safelyHasAResolutionAlternative(conf)
    }

    private fun unresolvedDependenciesShouldFailTheBuild() :Boolean {
        return if (project.hasProperty(UNRESOLVED_DEPENDENCIES_FAIL_THE_BUILD)) {
            (project.property(UNRESOLVED_DEPENDENCIES_FAIL_THE_BUILD) as String).toBoolean()
        } else {
            extension.shouldFailTheBuild
        }
    }

    private fun uniqueProjectKey(project: Project): String {
        return "${project.name}-${if(project == project.rootProject) "rootproject" else "subproject"}"
    }
}