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

import java.io.Serializable

/**
 * Data about a successfully resolved artifact.
 * Used for comparing with lock file versions (in DependencyVerificationTask).
 */
data class ResolvedArtifactData(
    /** Group (e.g. com.example) */
    val group: String,
    /** Module name (e.g. artifact) */
    val module: String,
    /** Resolved version (e.g. 1.0.0) */
    val version: String
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
