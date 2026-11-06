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
import org.gradle.api.artifacts.Configuration
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal
import org.gradle.api.provider.Provider

/**
 * Policy for verifying dependency resolution while Gradle core lock state is written or updated
 * (`--write-locks` or `--update-locks`).
 *
 * Gradle doesn't write lock state for a configuration whose graph has a failure, and lenient resolution doesn't
 * fail the build, so that configuration silently keeps its previous lock entries. Only lock-enabled
 * configurations can go stale this way, so lock writes verify only those. Unlocked configurations are still
 * verified in normal builds.
 *
 * `verifyDependencyResolution` uses this policy. It's public so that other tasks that resolve configurations
 * during a lock write, such as a build's own `resolve` task, can apply the same rules and honor the same
 * `dependencyResolutionVerifier` settings. Requires this plugin to be applied, because it registers
 * `dependencyResolutionVerifierExtension` on the root project.
 */
object LockWriteVerification {

    /** True when this build writes (`--write-locks`) or updates (`--update-locks`) lock state. */
    @JvmStatic
    fun isWritingOrUpdatingLocks(project: Project): Boolean {
        val startParameter = project.gradle.startParameter
        return startParameter.isWriteDependencyLocks || startParameter.lockedDependenciesToUpdate.isNotEmpty()
    }

    /** True when Gradle dependency locking is activated for [configuration]. */
    @JvmStatic
    fun isDependencyLockingEnabled(configuration: Configuration): Boolean =
        // Gradle has no public API for this; ResolutionStrategyInternal has exposed it since dependency locking was added.
        (configuration.resolutionStrategy as? ResolutionStrategyInternal)?.isDependencyLockingEnabled == true

    /**
     * Configurations excluded from verification: the `dependencyResolutionVerifier.configurationsToExclude`
     * property (comma-separated) when set, otherwise `dependencyResolutionVerifierExtension.configurationsToExclude`.
     */
    @JvmStatic
    fun configurationsToExclude(project: Project): Provider<Set<String>> =
        project.provider { project.findProperty(CONFIGURATIONS_TO_EXCLUDE)?.toString() }
            .map { value -> value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
            .orElse(verifierExtension(project).configurationsToExcludeProperty)

    /**
     * Whether unresolved dependencies fail the build: the `dependencyResolutionVerifier.unresolvedDependenciesFailTheBuild`
     * property when set, otherwise `dependencyResolutionVerifierExtension.shouldFailTheBuild`.
     */
    @JvmStatic
    fun shouldFailTheBuild(project: Project): Provider<Boolean> =
        project.provider { project.findProperty(UNRESOLVED_DEPENDENCIES_FAIL_THE_BUILD)?.toString() }
            .map { it.toBoolean() }
            .orElse(verifierExtension(project).shouldFailTheBuildProperty)

    /**
     * Names of the resolvable, lock-enabled configurations of [project] that are not excluded. Evaluate it after the
     * project is configured, because plugins activate locking as they create configurations.
     */
    @JvmStatic
    fun lockedConfigurationsToVerify(project: Project): Provider<Set<String>> {
        val excluded = configurationsToExclude(project)
        return project.provider {
            val exclusions = excluded.get()
            project.configurations
                .filter { it.isCanBeResolved && isDependencyLockingEnabled(it) && it.name !in exclusions }
                .map { it.name }
                .toSortedSet()
        }
    }

    private fun verifierExtension(project: Project): DependencyResolutionVerifierExtension =
        project.rootProject.extensions.getByType(DependencyResolutionVerifierExtension::class.java)
}
