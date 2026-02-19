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

/** Shared formatter for dependency resolution error messages (task and FlowAction). */
object DependencyResolutionErrorFormatter {

    private const val UNRESOLVED_HEADER = "Failed to resolve the following dependencies:"
    private const val LOCK_MISMATCH_HEADER = "Dependency lock state is out of date:"
    private const val LOCK_UPDATE_HINT = "Please update your dependency locks or your build file constraints."
    private const val MISSING_VERSION_PREFIX = "The following dependencies are missing a version: "
    private const val MISSING_VERSION_BOM_HINT = "Please add a version to fix this. If you have been using a BOM, perhaps these dependencies are no longer managed."

    /** Format unresolved dependency coordinates (e.g. from BuildService failure map keys) into a numbered message. */
    fun formatUnresolvedDependencies(
        deps: Collection<String>,
        projectName: String,
        missingVersionsAddition: String = ""
    ): String {
        val sorted = deps.sorted()
        val main = formatUnresolvedDepsList(sorted, projectName)
        val missingVersion = sorted.filter { it.split(':').size < 3 }
        if (missingVersion.isEmpty()) return main
        val sb = StringBuilder(main)
        sb.append("\n\n")
        sb.append(MISSING_VERSION_PREFIX)
        sb.append(missingVersion.joinToString(", "))
        sb.append("\n")
        sb.append(MISSING_VERSION_BOM_HINT)
        if (missingVersionsAddition.isNotEmpty()) {
            sb.append(" ")
            sb.append(missingVersionsAddition)
        }
        sb.append("\n")
        return sb.toString()
    }

    /** Format lock version mismatches (mismatch desc -> configs) with optional custom message. */
    fun formatLockMismatches(
        mismatches: Map<String, Set<String>>,
        projectName: String,
        customMessage: String = ""
    ): String {
        val messages = mutableListOf<String>()
        messages.add(LOCK_MISMATCH_HEADER)
        var counter = 1
        mismatches.toSortedMap().forEach { (mismatchDesc, configs) ->
            messages.add("  $counter. Resolved $mismatchDesc for project '$projectName' for configuration(s): ${configs.joinToString(",")}")
            counter++
        }
        messages.add("")
        messages.add(LOCK_UPDATE_HINT)
        if (customMessage.isNotEmpty()) {
            messages.add(customMessage)
        }
        return messages.joinToString("\n")
    }

    /** Extract dependency coordinates from resolution exception messages (used by engine). */
    fun extractUnresolvedDependenciesFromException(exceptionMessage: String): Set<String> {
        val dependencies = mutableSetOf<String>()
        extractCoordsFromCouldNotFind(exceptionMessage, dependencies)
        extractCoordsFromWasNotFound(exceptionMessage, dependencies)
        extractCoordsFromGenericTriple(exceptionMessage, dependencies)
        return dependencies
    }

    private fun formatUnresolvedDepsList(deps: List<String>, projectName: String): String {
        val lines = mutableListOf(UNRESOLVED_HEADER)
        deps.forEachIndexed { index, dep ->
            lines.add("  ${index + 1}. Failed to resolve '$dep' for project '$projectName'")
        }
        return lines.joinToString("\n")
    }

    /** Matches "Could not find group:name:version" (Gradle-style resolution errors). */
    private fun extractCoordsFromCouldNotFind(message: String, out: MutableSet<String>) {
        Regex("""Could not find ([^:\s]+:[^:\s]+:[^:\s,\.\)]+)""").findAll(message).forEach { out.add(it.groupValues[1]) }
    }

    /** Matches "group:name:version was not found" (alternative phrasing). */
    private fun extractCoordsFromWasNotFound(message: String, out: MutableSet<String>) {
        Regex("""([^:\s]+:[^:\s]+:[^:\s,\.\)]+) was not found""").findAll(message).forEach { out.add(it.groupValues[1]) }
    }

    /** Matches any group:name:version-looking triple (fallback); filters out noise. */
    private fun extractCoordsFromGenericTriple(message: String, out: MutableSet<String>) {
        Regex("""([a-zA-Z0-9._-]+:[a-zA-Z0-9._-]+:[a-zA-Z0-9._-]+)""").findAll(message).forEach { match ->
            val coord = match.groupValues[1]
            if (coord.contains(".") || coord.split(":").all { it.isNotEmpty() }) out.add(coord)
        }
    }
}
