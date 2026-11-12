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

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks verification state across projects; config-cache compatible, thread-safe.
 * State is cleared per project when [initializeProject] is called: at task execution in the
 * opt-in isolated mode, or during configuration on the legacy path. Full project paths keep
 * enabled participants independent, including projects with identical leaf names.
 * Uses only Java collection types for per-dependency sets (Collections.synchronizedSet(HashSet()), not
 * ConcurrentHashMap.newKeySet()) so the service is safe in Gradle workers (no kotlin.collections export).
 */
abstract class DependencyVerificationBuildService : BuildService<DependencyVerificationBuildService.Params>, FailureCauseSink {

    interface Params : BuildServiceParameters

    val failedDepsPerProject: ConcurrentHashMap<String, ConcurrentHashMap<String, MutableSet<String>>> = ConcurrentHashMap()
    val depsWhereResolvedVersionIsNotLockedVersion: ConcurrentHashMap<String, ConcurrentHashMap<String, MutableSet<String>>> = ConcurrentHashMap()
    val failureCausesPerProject: ConcurrentHashMap<String, ConcurrentHashMap<String, String>> = ConcurrentHashMap()

    override fun recordFailedDependency(projectKey: String, dependency: String, configurationName: String) {
        failedDepsPerProject.computeIfAbsent(projectKey) { ConcurrentHashMap() }
            .computeIfAbsent(dependency) { Collections.synchronizedSet(HashSet()) }
            .add(configurationName)
    }

    override fun recordMismatchedVersion(projectKey: String, key: String, configurationName: String) {
        depsWhereResolvedVersionIsNotLockedVersion.computeIfAbsent(projectKey) { ConcurrentHashMap() }
            .computeIfAbsent(key) { Collections.synchronizedSet(HashSet()) }
            .add(configurationName)
    }

    override fun recordFailureCause(projectKey: String, dependency: String, cause: String) {
        failureCausesPerProject.computeIfAbsent(projectKey) { ConcurrentHashMap() }.putIfAbsent(dependency, cause)
    }

    fun getFailureCausesForProject(projectKey: String): Map<String, String> =
        failureCausesPerProject[projectKey]?.toMap().orEmpty()

    fun getFailuresForProject(projectKey: String): Map<String, Set<String>> {
        val map = failedDepsPerProject[projectKey] ?: return emptyMap()
        val result = HashMap<String, Set<String>>()
        map.forEach { (k, v) -> result[k] = HashSet(v) }
        return result
    }

    fun getMismatchedVersionsForProject(projectKey: String): Map<String, Set<String>> {
        val map = depsWhereResolvedVersionIsNotLockedVersion[projectKey] ?: return emptyMap()
        val result = HashMap<String, Set<String>>()
        map.forEach { (k, v) -> result[k] = HashSet(v) }
        return result
    }

    fun clearProject(projectKey: String) {
        failedDepsPerProject.remove(projectKey)
        depsWhereResolvedVersionIsNotLockedVersion.remove(projectKey)
        failureCausesPerProject.remove(projectKey)
    }

    /** Clears any existing state for this project and prepares fresh state for the current build. */
    fun initializeProject(projectKey: String) {
        clearProject(projectKey)
        failedDepsPerProject[projectKey] = ConcurrentHashMap()
        depsWhereResolvedVersionIsNotLockedVersion[projectKey] = ConcurrentHashMap()
    }
}
