/**
 *
 *  Copyright 2019 Netflix, Inc.
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
import nebula.plugin.dependencylock.utils.GradleVersionUtils
import nebula.plugin.dependencyverifier.exceptions.DependencyResolutionException
import org.gradle.BuildResult
import org.gradle.api.BuildCancelledException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolveException
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.TaskState
import org.gradle.internal.exceptions.DefaultMultiCauseException
import org.gradle.internal.locking.LockOutOfDateException
import org.gradle.internal.resolve.ArtifactResolveException
import org.gradle.internal.resolve.ModuleVersionNotFoundException
import org.gradle.internal.resolve.ModuleVersionResolveException

class DependencyResolutionVerifier {
    private static final Logger LOGGER = Logging.getLogger(DependencyResolutionVerifier.class)
    private static final String UNRESOLVED_DEPENDENCIES_FAIL_THE_BUILD = 'dependencyResolutionVerifier.unresolvedDependenciesFailTheBuild'
    private static final String CONFIGURATIONS_TO_EXCLUDE = 'dependencyResolutionVerifier.configurationsToExclude'

    static void verifySuccessfulResolution(Project project) {
        def extension = project.rootProject.extensions.findByType(DependencyResolutionVerifierExtension)
        Map<String, Set<Configuration>> failedDepsByConf = new HashMap<String, Set<Configuration>>()
        Set<String> lockedDepsOutOfDate = new HashSet<>()

        boolean parallelProjectExecutionEnabled = project.gradle.startParameter.isParallelProjectExecutionEnabled()

        boolean providedErrorMessageForThisProject = false

        Closure logOrThrowOnFailedDependencies = { from ->
            if (failedDepsByConf.size() != 0 || lockedDepsOutOfDate.size() != 0) {
                List<String> message = new ArrayList<>()
                List<String> debugMessage = new ArrayList<>()
                List<String> depsMissingVersions = new ArrayList<>()
                try {
                    if (failedDepsByConf.size() > 0) {
                        message.add("Failed to resolve the following dependencies:")
                    }
                    failedDepsByConf
                            .sort()
                            .eachWithIndex { kv, index ->
                                def dep = kv.key
                                def failedConfs = kv.value
                                message.add("  ${index + 1}. Failed to resolve '$dep' for project '${project.name}'")
                                debugMessage.add("Failed to resolve $dep on:")
                                failedConfs
                                        .sort { a, b -> a.name <=> b.name }
                                        .each { failedConf ->
                                            debugMessage.add("  - $failedConf")
                                        }
                                if (dep.split(':').size() < 3) {
                                    depsMissingVersions.add(dep)
                                }
                            }

                    if (lockedDepsOutOfDate.size() > 0) {
                        message.add('Resolved dependencies were missing from the lock state:')
                    }
                    lockedDepsOutOfDate
                            .sort()
                            .eachWithIndex { outOfDateMessage, index ->
                                message.add("  ${index + 1}. $outOfDateMessage for project '${project.name}'")
                            }

                    if (depsMissingVersions.size() > 0) {
                        message.add("The following dependencies are missing a version: ${depsMissingVersions.join(', ')}\n" +
                                "Please add a version to fix this. If you have been using a BOM, perhaps these dependencies are no longer managed. \n"
                                + extension.missingVersionsMessageAddition)
                    }

                } catch (Exception e) {
                    throw new BuildCancelledException("Error creating message regarding failed dependencies", e)
                }

                providedErrorMessageForThisProject = true
                debugMessage.add("Dependency resolution verification triggered from $from")

                Boolean unresolvedDependenciesShouldFailTheBuild = project.hasProperty(UNRESOLVED_DEPENDENCIES_FAIL_THE_BUILD)
                        ? (project.property(UNRESOLVED_DEPENDENCIES_FAIL_THE_BUILD) as String).toBoolean()
                        : (extension.shouldFailTheBuild as String).toBoolean()
                if (unresolvedDependenciesShouldFailTheBuild) {
                    LOGGER.debug(debugMessage.join('\n'))
                    throw new DependencyResolutionException(message.join('\n'))
                } else {
                    LOGGER.debug(debugMessage.join('\n'))
                    LOGGER.warn(message.join('\n'))
                }
            }
        }

        project.gradle.buildFinished() { buildResult ->
            assert buildResult instanceof BuildResult
            boolean buildFailed = buildResult.failure != null
            if (!providedErrorMessageForThisProject && buildFailed) {
                def failureCause = buildResult.failure?.cause?.cause
                if (failureCause == null || !(failureCause instanceof DefaultMultiCauseException)) {
                    return
                }
                def moduleVersionNotFoundCauses = failureCause.causes.findAll {
                    it.class == ModuleVersionNotFoundException
                }
                if (moduleVersionNotFoundCauses.size() > 0) {
                    String buildResultFailureMessage = failureCause.message
                    def split = buildResultFailureMessage.split(':')
                    String projectNameFromFailure = ''
                    if (split.size() == 3) {
                        projectNameFromFailure = split[1]
                    }
                    if (project.name == projectNameFromFailure) {
                        LOGGER.debug("Starting dependency resolution verification after the build has completed: ${buildResultFailureMessage}")

                        Configuration conf = null
                        try {
                            def confName = buildResultFailureMessage.replace('.', '').split('for ')[1]
                            conf = project.configurations
                                    .findAll { it.toString() == confName }
                                    .first()
                            LOGGER.debug("Found $conf from $confName")
                        } catch (Exception e) {
                            throw new BuildCancelledException("Error finding configuration associated with build failure from '${buildResultFailureMessage}'", e)
                        }

                        moduleVersionNotFoundCauses.each { it ->
                            def dep = it.selector.toString()
                            if (failedDepsByConf.containsKey(dep)) {
                                failedDepsByConf.get(dep).add(conf as Configuration)
                            } else {
                                Set<Configuration> failedConfs = new HashSet<Configuration>()
                                failedConfs.add(conf as Configuration)

                                failedDepsByConf.put(dep, failedConfs)
                            }
                        }

                        logOrThrowOnFailedDependencies("buildFinished event")
                    }
                }
            }
        }

        project.gradle.taskGraph.whenReady { taskGraph ->
            LinkedList tasks = GradleVersionUtils.currentGradleVersionIsLessThan("5.0")
                    ? taskGraph.taskExecutionPlan.executionQueue // the method name before Gradle 5.0
                    : taskGraph.executionPlan.executionQueue // the method name as of Gradle 5.0

            List<Task> safeTasks
            if (parallelProjectExecutionEnabled) {
                safeTasks = tasks
                        .groupBy { task ->
                            GradleVersionUtils.currentGradleVersionIsLessThan("6.0")
                                    ? task.project // the method name before Gradle 6.0
                                    : task.owningProject // the method name as of Gradle 6.0
                        }
                        .find { proj, tasksForProj -> proj == project }
                        ?.value
                        ?.collect { it -> it.task }
            } else {
                safeTasks = tasks.collect { it -> it.task }
            }

            Map tasksGroupedByTaskIdentityAcrossProjects = tasks.groupBy { task -> task.toString().split(':').last() }

            if (!safeTasks) {
                return
            }

            taskGraph.addTaskExecutionListener(new TaskExecutionListener() {
                @Override
                void beforeExecute(Task task) {
                    //DO NOTHING
                }

                @Override
                void afterExecute(Task task, TaskState taskState) {
                    boolean taskIsSafeToAccess = safeTasks.contains(task)
                    boolean taskIsNotExcluded = !extension.tasksToExclude.contains(task.name)

                    if (taskIsSafeToAccess && taskIsNotExcluded && !providedErrorMessageForThisProject) {
                        String simpleTaskName = task.toString().replace("'", '').split(':').last()
                        Task lastTaskEvaluatedWithSameName = tasksGroupedByTaskIdentityAcrossProjects
                                .get(simpleTaskName)
                                .last()
                                ?.task

                        boolean lastChanceToThrowExceptionWithTaskOfThisIdentity
                        if (parallelProjectExecutionEnabled) {
                            lastChanceToThrowExceptionWithTaskOfThisIdentity = true
                        } else {
                            lastChanceToThrowExceptionWithTaskOfThisIdentity = task.path == lastTaskEvaluatedWithSameName.path
                        }

                        boolean taskHasFailed = taskState.failure

                        if (lastChanceToThrowExceptionWithTaskOfThisIdentity || taskHasFailed) {
                            Set<String> configurationsToExclude = project.hasProperty(CONFIGURATIONS_TO_EXCLUDE)
                                    ? (project.property(CONFIGURATIONS_TO_EXCLUDE) as String).split(",") as Set<String>
                                    : extension.configurationsToExclude

                            project.configurations.matching { // returns a live collection
                                assert it instanceof Configuration
                                configurationIsResolvedAndMatches(it, configurationsToExclude)
                            }.all { conf ->
                                assert conf instanceof Configuration

                                LOGGER.debug("$conf in ${project.name} has state ${conf.state}. Starting dependency resolution verification.")
                                try {
                                    conf.resolvedConfiguration.resolvedArtifacts
                                } catch (ResolveException | ModuleVersionResolveException | ArtifactResolveException e) {
                                    e.causes.each {
                                        if (LockOutOfDateException.class == it.class) {
                                            lockedDepsOutOfDate.add(it.getMessage())
                                        } else {
                                            String dep = it.selector.toString()
                                            if (failedDepsByConf.containsKey(dep)) {
                                                failedDepsByConf.get(dep).add(conf as Configuration)
                                            } else {
                                                Set<Configuration> failedConfs = new HashSet<Configuration>()
                                                failedConfs.add(conf as Configuration)

                                                failedDepsByConf.put(dep, failedConfs)
                                            }
                                        }
                                    }
                                }
                            }

                            logOrThrowOnFailedDependencies("${task}")
                        }
                    }
                }
            }

            )
        }
    }

    private static boolean configurationIsResolvedAndMatches(Configuration conf, Set<String> configurationsToExclude) {
        return (conf as Configuration).state != Configuration.State.UNRESOLVED &&
                // the configurations `incrementalScalaAnalysisFor_x_` are resolvable only from a scala context
                !(conf as Configuration).name.startsWith('incrementalScala') &&
                !configurationsToExclude.contains((conf as Configuration).name) &&
                !ConfigurationFilters.safelyHasAResolutionAlternative(conf as Configuration)
    }
}
