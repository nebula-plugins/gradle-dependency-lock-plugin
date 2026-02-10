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

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault
abstract class DependencyVerificationTask : DefaultTask() {

    /**
     * Injected by Gradle because this task is registered with [org.gradle.api.Task.usesService] and the
     * name matches the build service registered in the plugin. At execution time [verify] uses it to
     * build a [VerificationSink] that forwards to this service.
     */
    @get:ServiceReference("dependencyVerificationService")
    abstract val verificationService: Property<DependencyVerificationBuildService>

    @get:Nested
    abstract val parameters: VerifierParameters

    init {
        outputs.doNotCacheIf("Verification task with no file outputs") { true }
        outputs.upToDateWhen { false }
        description = "Verifies that all dependencies can be resolved and match lock files"
        group = "verification"
    }

    @TaskAction
    fun verify() {
        val projName = parameters.projectName.get()
        val projKey = parameters.projectKey.get()
        val resolutionResultsMap = parameters.resolutionResults.get()
        if (resolutionResultsMap.isEmpty()) return

        val service = verificationService.get()
        val sink = object : VerificationSink {
            override fun recordFailedDependency(projectKey: String, dependency: String, configurationName: String) {
                service.recordFailedDependency(projectKey, dependency, configurationName)
            }
            override fun recordMismatchedVersion(projectKey: String, key: String, configurationName: String) {
                service.recordMismatchedVersion(projectKey, key, configurationName)
            }
        }

        val lockValidationEnabled = parameters.enableLockFileValidation.get() &&
            parameters.coreAlignmentEnabled.get() &&
            !parameters.coreLockingEnabled.get() &&
            !parameters.taskNames.get().any { it.contains("updateLock") || it.contains("generateLock") }
        val lockedDepsMap = parameters.lockedDependenciesPerConfiguration.get()
        val overrideDepsMap = parameters.overrideDependenciesPerConfiguration.get()

        VerificationEngine.run(
            resolutionResultsMap,
            sink,
            projKey,
            lockValidationEnabled,
            lockedDepsMap,
            overrideDepsMap
        )

        val failures = service.getFailuresForProject(projKey)
        val mismatchedVersions = service.getMismatchedVersionsForProject(projKey)
        val shouldFail = parameters.shouldFailTheBuild.get()
        val throwOnFailures = shouldFail && !parameters.reportingOnlyBuild.get()

        VerifierFailureReporter.reportFailuresAndMismatches(
            failures,
            mismatchedVersions,
            projName,
            parameters.missingVersionsMessageAddition.get(),
            parameters.resolvedVersionDoesNotEqualLockedVersionMessageAddition.get(),
            shouldFail,
            throwOnFailures,
            logger
        )
    }
}
