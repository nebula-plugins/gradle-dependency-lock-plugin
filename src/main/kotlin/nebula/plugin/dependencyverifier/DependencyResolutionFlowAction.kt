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

import org.gradle.api.flow.FlowAction
import org.gradle.api.flow.FlowParameters
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input

/**
 * Runs after the build and reports verification failures when the requested tasks were reporting-only
 * (e.g. `dependencies`, `dependencyInsight`, `dependenciesForAll`).
 * Reads failures and lock mismatches from [DependencyVerificationBuildService] and fails or logs accordingly.
 *
 * Two paths: (1) Normal builds — [DependencyVerificationTask] throws or logs immediately when it runs.
 * (2) Reporting-only builds — the task does not report (throwOnFailures = false); this FlowAction runs
 * after the build and performs the fail or log using BuildService state.
 *
 * Parameters mirror [VerifierParameters] (shouldFailTheBuild, messages, taskNames) but must stay flat:
 * FlowAction Parameters do not support [org.gradle.api.tasks.Nested], so we cannot reuse [VerifierParameters] here.
 */
abstract class DependencyResolutionFlowAction : FlowAction<DependencyResolutionFlowAction.Parameters> {

    private val logger = Logging.getLogger(DependencyResolutionFlowAction::class.java)

    /**
     * Flat parameters required because FlowAction does not support [org.gradle.api.tasks.Nested].
     * Subset of [VerifierParameters] needed for post-build reporting (no resolution/lock data).
     */
    interface Parameters : FlowParameters {
        @get:Input
        val projectKey: Property<String>
        @get:Input
        val projectName: Property<String>
        @get:Input
        val shouldFailTheBuild: Property<Boolean>
        @get:Input
        val lockMismatchMessage: Property<String>
        @get:Input
        val missingVersionsMessageAddition: Property<String>
        @get:Input
        val requestedTaskNames: org.gradle.api.provider.ListProperty<String>
        @get:Input
        val buildResult: Property<org.gradle.api.flow.BuildWorkResult>
        @get:ServiceReference("dependencyVerificationService")
        val verificationService: Property<DependencyVerificationBuildService>
    }

    override fun execute(parameters: Parameters) {
        if (!ReportingTasks.isReportingTasksOnly(parameters.requestedTaskNames.get())) return
        if (parameters.buildResult.get().failure.isPresent) return
        val service = parameters.verificationService.get()
        val projKey = parameters.projectKey.get()
        val projName = parameters.projectName.get()
        val failBuild = parameters.shouldFailTheBuild.get()
        val failures = service.getFailuresForProject(projKey)
        val mismatches = service.getMismatchedVersionsForProject(projKey)

        VerifierFailureReporter.reportFailuresAndMismatches(
            failures,
            mismatches,
            projName,
            parameters.missingVersionsMessageAddition.get(),
            parameters.lockMismatchMessage.get(),
            failBuild,
            throwOnFailures = true,
            logger
        )
    }
}
