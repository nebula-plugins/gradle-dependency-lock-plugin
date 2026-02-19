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

import org.gradle.api.artifacts.ResolveException
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider

/**
 * Pure verification logic: one graph traversal, optional lock validation, records via sink.
 * No Gradle task/service types so the engine is easy to test and reuse.
 */
object VerificationEngine {

    private val logger = Logging.getLogger(VerificationEngine::class.java)

    /**
     * Runs resolution verification and optional lock validation.
     * Single traversal per configuration: collects unresolved deps and resolved coordinates.
     * Records failures and mismatches to [sink].
     */
    fun run(
        resolutionResultsMap: Map<String, Provider<ResolvedComponentResult>>,
        sink: VerificationSink,
        projectKey: String,
        lockValidationEnabled: Boolean,
        lockedDepsMap: Map<String, Map<String, String>>,
        overrideDepsMap: Map<String, Map<String, String>>
    ) {
        val resolvedPerConf = mutableMapOf<String, List<ResolvedArtifactData>>()
        for ((confName, rootProvider) in resolutionResultsMap) {
            try {
                val root = rootProvider.get()
                val unresolved = mutableSetOf<String>()
                val resolved = mutableListOf<ResolvedArtifactData>()
                traverse(root, mutableSetOf(), unresolved, resolved)
                unresolved.forEach { sink.recordFailedDependency(projectKey, it, confName) }
                resolvedPerConf[confName] = resolved
            } catch (e: ResolveException) {
                logger.debug("Resolution failed for configuration '$confName': ${e.message}", e)
                val messages = collectMessagesFromCauses(e.causes)
                val deps = DependencyResolutionErrorFormatter.extractUnresolvedDependenciesFromException(messages.joinToString(" | "))
                deps.forEach { sink.recordFailedDependency(projectKey, it, confName) }
            } catch (e: Exception) {
                logger.debug("Resolution failed for configuration '$confName': ${e.message}", e)
                val messages = mutableListOf<String>()
                var t: Throwable? = e
                while (t != null) {
                    t.message?.let { messages.add(it) }
                    t = t.cause
                }
                val deps = DependencyResolutionErrorFormatter.extractUnresolvedDependenciesFromException(messages.joinToString(" | "))
                deps.forEach { sink.recordFailedDependency(projectKey, it, confName) }
            }
        }
        if (!lockValidationEnabled || lockedDepsMap.isEmpty()) return
        for ((confName, artifacts) in resolvedPerConf) {
            val locked = lockedDepsMap.getOrDefault(confName, emptyMap())
            val overrides = overrideDepsMap.getOrDefault(confName, emptyMap())
            if (locked.isEmpty() && overrides.isEmpty()) continue
            for (a in artifacts) {
                val key = "${a.group}:${a.module}"
                val expected = overrides[key]?.takeIf { it.isNotEmpty() } ?: locked[key]?.takeIf { it.isNotEmpty() } ?: ""
                if (expected.isNotEmpty() && expected != a.version) {
                    val mismatchKey = "'${a.group}:${a.module}:${a.version}' instead of locked version '$expected'"
                    sink.recordMismatchedVersion(projectKey, mismatchKey, confName)
                }
            }
        }
    }

    /** Collects exception messages from ResolveException.getCauses() and each cause's chain. */
    private fun collectMessagesFromCauses(causes: Iterable<Throwable>): List<String> {
        val messages = mutableListOf<String>()
        for (cause in causes) {
            var t: Throwable? = cause
            while (t != null) {
                t.message?.let { messages.add(it) }
                t = t.cause
            }
        }
        return messages
    }

    private fun traverse(
        component: ResolvedComponentResult,
        visited: MutableSet<ComponentIdentifier>,
        unresolved: MutableSet<String>,
        resolved: MutableList<ResolvedArtifactData>
    ) {
        if (!visited.add(component.id)) return
        val id = component.id
        if (id is ModuleComponentIdentifier) {
            resolved.add(ResolvedArtifactData(group = id.group, module = id.module, version = id.version))
        }
        component.dependencies.forEach { dep ->
            when (dep) {
                is org.gradle.api.artifacts.result.UnresolvedDependencyResult ->
                    unresolved.add(dep.requested.displayName)
                is ResolvedDependencyResult ->
                    traverse(dep.selected, visited, unresolved, resolved)
            }
        }
    }
}
