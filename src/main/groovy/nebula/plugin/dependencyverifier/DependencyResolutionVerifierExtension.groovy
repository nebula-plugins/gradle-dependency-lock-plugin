/*
 * Copyright 2014-2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nebula.plugin.dependencyverifier

import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/** Extension for dependency resolution verification (Property API, config-cache friendly). */
abstract class DependencyResolutionVerifierExtension {
    /** Fail the build when verification finds issues. Default: true */
    abstract Property<Boolean> getShouldFailTheBuild()

    /** Configuration names to exclude from verification. Default: empty set */
    abstract SetProperty<String> getConfigurationsToExclude()

    /** Message to append when missing versions are detected. Default: empty */
    abstract Property<String> getMissingVersionsMessageAddition()

    /** Message to append when resolved version does not match locked. Default: empty */
    abstract Property<String> getResolvedVersionDoesNotEqualLockedVersionMessageAddition()

    /** Task names to exclude from verification. Default: empty set */
    abstract SetProperty<String> getTasksToExclude()

    /** When true (default): full lock validation. When false: only resolution errors, config cache compatible. */
    abstract Property<Boolean> getEnableLockFileValidation()

    DependencyResolutionVerifierExtension() {
        shouldFailTheBuild.convention(true)
        configurationsToExclude.convention([] as Set)
        missingVersionsMessageAddition.convention('')
        resolvedVersionDoesNotEqualLockedVersionMessageAddition.convention('')
        tasksToExclude.convention([] as Set)
        enableLockFileValidation.convention(true)
    }
}
