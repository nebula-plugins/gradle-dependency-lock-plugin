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

import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal

/** All inputs to the verification task (project identity, behavior options, lock data, resolution results). */
abstract class VerifierParameters {

    @get:Input
    abstract val projectKey: Property<String>

    @get:Input
    abstract val projectName: Property<String>

    @get:Input
    abstract val shouldFailTheBuild: Property<Boolean>

    @get:Input
    abstract val missingVersionsMessageAddition: Property<String>

    @get:Input
    abstract val resolvedVersionDoesNotEqualLockedVersionMessageAddition: Property<String>

    @get:Input
    abstract val configurationsToExclude: SetProperty<String>

    @get:Input
    abstract val enableLockFileValidation: Property<Boolean>

    @get:Input
    abstract val configurationNamesToValidate: ListProperty<String>

    @get:Input
    abstract val taskNames: ListProperty<String>

    @get:Input
    abstract val reportingOnlyBuild: Property<Boolean>

    @get:Input
    abstract val coreAlignmentEnabled: Property<Boolean>

    @get:Input
    abstract val coreLockingEnabled: Property<Boolean>

    @get:Input
    abstract val lockedDependenciesPerConfiguration: MapProperty<String, Map<String, String>>

    @get:Input
    abstract val overrideDependenciesPerConfiguration: MapProperty<String, Map<String, String>>

    @get:Internal
    abstract val resolutionResults: MapProperty<String, org.gradle.api.provider.Provider<ResolvedComponentResult>>
}
