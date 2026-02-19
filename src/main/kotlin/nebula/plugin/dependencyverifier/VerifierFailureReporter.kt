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

import nebula.plugin.dependencyverifier.exceptions.DependencyResolutionException
import org.gradle.api.logging.Logger

/**
 * Shared failure reporting so [DependencyVerificationTask] and [DependencyResolutionFlowAction]
 * use one place for "format then throw or log" logic.
 *
 * @param throwOnFailures When true, throw or log for both resolution failures and lock mismatches.
 * When false (e.g. task in reporting-only build), do nothing so [DependencyResolutionFlowAction] can report later.
 */
object VerifierFailureReporter {

    fun reportFailuresAndMismatches(
        failures: Map<String, Set<String>>,
        mismatches: Map<String, Set<String>>,
        projectName: String,
        missingVersionsAddition: String,
        lockMismatchMessage: String,
        shouldFailTheBuild: Boolean,
        throwOnFailures: Boolean,
        logger: Logger
    ) {
        if (failures.isNotEmpty() && throwOnFailures) {
            val msg = DependencyResolutionErrorFormatter.formatUnresolvedDependencies(
                failures.keys,
                projectName,
                missingVersionsAddition
            )
            if (shouldFailTheBuild) {
                throw DependencyResolutionException(msg)
            } else {
                logger.warn("Unresolved dependencies:\n$msg")
            }
        }
        if (mismatches.isNotEmpty() && throwOnFailures) {
            val msg = DependencyResolutionErrorFormatter.formatLockMismatches(
                mismatches,
                projectName,
                lockMismatchMessage
            )
            if (shouldFailTheBuild) {
                throw DependencyResolutionException(msg)
            } else {
                logger.warn(msg)
            }
        }
    }
}
