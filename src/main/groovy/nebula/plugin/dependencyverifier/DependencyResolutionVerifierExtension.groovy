/*
 * Copyright 2014-2019 Netflix, Inc.
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

/**
 * Extension for configuring dependency resolution verification.
 * Uses Gradle's Property API for lazy configuration and configuration cache compatibility.
 */
abstract class DependencyResolutionVerifierExtension {
    /**
     * Whether to fail the build when verification issues are found.
     * Default: true
     */
    abstract Property<Boolean> getShouldFailTheBuild()
    
    /**
     * Configuration names to exclude from verification.
     * Default: empty set
     */
    abstract SetProperty<String> getConfigurationsToExclude()
    
    /**
     * Additional message to append when missing versions are detected.
     * Default: empty string
     */
    abstract Property<String> getMissingVersionsMessageAddition()
    
    /**
     * Additional message to append when resolved version doesn't match locked version.
     * Default: empty string
     */
    abstract Property<String> getResolvedVersionDoesNotEqualLockedVersionMessageAddition()
    
    /**
     * Task names to exclude from verification.
     * Default: empty set
     */
    abstract SetProperty<String> getTasksToExclude()
    
    /**
     * Constructor sets default conventions for all properties.
     */
    DependencyResolutionVerifierExtension() {
        shouldFailTheBuild.convention(true)
        configurationsToExclude.convention([] as Set)
        missingVersionsMessageAddition.convention('')
        resolvedVersionDoesNotEqualLockedVersionMessageAddition.convention('')
        tasksToExclude.convention([] as Set)
    }
}
