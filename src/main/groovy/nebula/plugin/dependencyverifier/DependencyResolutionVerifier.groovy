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

import nebula.plugin.dependencylock.utils.GradleVersionUtils
import nebula.plugin.dependencyverifier.exceptions.DependencyResolutionException
import org.gradle.api.BuildCancelledException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolveException
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.TaskState
import org.gradle.internal.locking.LockOutOfDateException
import org.gradle.internal.resolve.ModuleVersionResolveException

class DependencyResolutionVerifier {
    private static final Logger LOGGER = Logging.getLogger(DependencyResolutionVerifier.class)
    private static final String UNRESOLVED_DEPENDENCIES_FAIL_THE_BUILD = 'dependencyLock.unresolvedDependenciesFailTheBuild'

    static void verifySuccessfulResolution(Project project) {
        Boolean unresolvedDependenciesShouldFailTheBuild = project.hasProperty(UNRESOLVED_DEPENDENCIES_FAIL_THE_BUILD)
                ? (project.property(UNRESOLVED_DEPENDENCIES_FAIL_THE_BUILD) as String).toBoolean()
                : true

        Map<String, Set<Configuration>> failedDepsByConf = new HashMap<String, Set<Configuration>>()
        Set<String> lockedDepsOutOfDate = new HashSet<>()

        boolean parallelProjectExecutionEnabled = project.gradle.startParameter.isParallelProjectExecutionEnabled()

        project.gradle.taskGraph.whenReady { taskGraph ->
            LinkedList tasks = taskGraph.executionPlan.executionQueue

            List<Task> safeTasks
            if (parallelProjectExecutionEnabled) {
                safeTasks = tasks
                        .groupBy { task ->
                            GradleVersionUtils.currentGradleVersionIsLessThan("6.0")
                                    ? task.project // the method name before Gradle 6.0
                                    : task.owningProject // the method name as of Gradle 6.0
                        }
                        .find { proj, tasksForProj -> proj == project }
                        .value
                        .collect { it -> it.task }
            } else {
                safeTasks = tasks.collect { it -> it.task }
            }

            Map tasksGroupedByTaskIdentityAcrossProjects = tasks.groupBy { task -> task.toString().split(':').last() }


            taskGraph.addTaskExecutionListener(new TaskExecutionListener() {
                @Override
                void beforeExecute(Task task) {
                    //DO NOTHING
                }

                @Override
                void afterExecute(Task task, TaskState taskState) {
                    boolean taskIsSafeToAccess = safeTasks.contains(task)

                    if (taskIsSafeToAccess) {
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
                            project.configurations.matching { // returns a live collection
                                (it as Configuration).state != Configuration.State.UNRESOLVED &&
                                        // the configurations `incrementalScalaAnalysisFor_x_` are resolvable only from a scala context
                                        !(it as Configuration).name.startsWith('incrementalScala')
                            }.all { conf ->
                                assert conf instanceof Configuration

                                LOGGER.debug("$conf has state ${conf.state}. Starting dependency resolution verification.")
                                try {
                                    conf.resolvedConfiguration.resolvedArtifacts
                                } catch (ResolveException | ModuleVersionResolveException e) {
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

                            if (failedDepsByConf.size() != 0 || lockedDepsOutOfDate.size() != 0) {
                                List<String> message = new ArrayList<>()
                                List<String> debugMessage = new ArrayList<>()
                                try {
                                    if (failedDepsByConf.size() > 0) {
                                        message.add("Failed to resolve the following dependencies:")
                                    }
                                    failedDepsByConf
                                            .sort()
                                            .eachWithIndex { it, index ->
                                                def dep = it.key
                                                def failedConfs = it.value
                                                message.add("  ${index + 1}. Failed to resolve '$dep' for project '${project.name}'")
                                                debugMessage.add("Failed to resolve $dep on:")
                                                failedConfs
                                                        .sort { a, b -> a.name <=> b.name }
                                                        .each { failedConf ->
                                                            debugMessage.add("  - $failedConf")
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

                                } catch (Exception e) {
                                    throw new BuildCancelledException("Error creating message regarding failed dependencies", e)
                                }
                                if (unresolvedDependenciesShouldFailTheBuild) {
                                    LOGGER.debug(debugMessage.join('\n'))
                                    throw new DependencyResolutionException(message.join('\n'))
                                } else {
                                    LOGGER.debug(debugMessage.join('\n'))
                                    LOGGER.warn(message.join('\n'))
                                }
                            }
                        }
                    }
                }
            }

            )
        }
    }
}
