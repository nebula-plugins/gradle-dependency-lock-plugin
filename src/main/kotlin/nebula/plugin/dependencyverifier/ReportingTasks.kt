/**
 *
 *  Copyright 2026 Netflix, Inc.
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

import org.gradle.api.Project
import org.gradle.api.tasks.diagnostics.DependencyInsightReportTask
import org.gradle.api.tasks.diagnostics.DependencyReportTask

/**
 * Shared logic for detecting reporting-only builds (dependency report tasks that show but do not fail on resolution errors).
 *
 * **When to use which overload:**
 * - Use [isReportingTasksOnly] with [Project] during configuration when the task container is available (e.g. in [DependencyResolutionVerifier]).
 * - Use [isReportingTasksOnly] with task names when [Project] is not available (e.g. in [DependencyResolutionFlowAction] after build, or any config-cache-safe context).
 *
 * **Limitation:** The name-based overload only recognizes [REPORTING_TASK_NAMES]. Custom tasks that extend [DependencyReportTask] or [DependencyInsightReportTask] but use a different task name are not recognized by the name-based check and will be treated as non-reporting.
 */
object ReportingTasks {

    /**
     * Task names treated as reporting-only by the name-based overload.
     * Align with Gradle's default names: `dependencies`, `dependencyInsight`, and the common `dependenciesForAll` convention.
     * Custom report task names must be added here to be recognized when using [isReportingTasksOnly] with task names.
     */
    internal val REPORTING_TASK_NAMES: Set<String> = setOf("dependencies", "dependencyInsight", "dependenciesForAll")

    /**
     * Returns true if all requested tasks are reporting types (DependencyReportTask, DependencyInsightReportTask).
     * Use this overload during configuration when [Project] and the task container are available.
     * Uses task lookup by path; not configuration-cache compatible (eager API).
     */
    fun isReportingTasksOnly(project: Project): Boolean {
        val taskNames = project.gradle.startParameter.taskNames
        if (taskNames.isEmpty()) return false
        val taskContainer = project.rootProject.tasks
        return taskNames.all { path ->
            val task = taskContainer.findByPath(path)
            task != null && (task is DependencyReportTask || task is DependencyInsightReportTask)
        }
    }

    /**
     * Returns true if all requested task names match [REPORTING_TASK_NAMES] (by simple name, after the last ':').
     * Use this overload when [Project] is not available (e.g. in FlowAction after build) or when configuration-cache safety is required.
     * Does not recognize custom report tasks whose names are not in [REPORTING_TASK_NAMES].
     */
    fun isReportingTasksOnly(taskNames: List<String>): Boolean {
        if (taskNames.isEmpty()) return false
        return taskNames.all { taskName ->
            val simpleName = taskName.substringAfterLast(':')
            REPORTING_TASK_NAMES.contains(simpleName)
        }
    }
}
