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

/**
 * Extension for dependency resolution verification (Property API, config-cache friendly).
 *
 * <h3>SetProperty backward-compat bridge convention</h3>
 *
 * Every {@code SetProperty<String>} field follows a two-name convention — see
 * {@link nebula.plugin.dependencylock.DependencyLockExtension} for the full explanation.
 * In short: {@code getFooProperty()} is the Gradle-managed backing store for internal
 * config-cache-safe task wiring; {@code getFoo()} / {@code setFoo(Iterable<String>)} are the
 * public backward-compatible API that downstream Groovy build scripts call via
 * {@code extension.foo = [...]} assignment syntax. Both sides must be kept.
 */
abstract class DependencyResolutionVerifierExtension {
    /** Fail the build when verification finds issues. Default: true */
    abstract Property<Boolean> getShouldFailTheBuild()

    /**
     * Configuration names to exclude from verification. Default: empty set
     *
     * The backing SetProperty is exposed via {@link #getConfigurationsToExcludeProperty()} for
     * config-cache-compatible task wiring. The getter/setter here preserve the old Set&lt;String&gt;
     * API so existing build scripts continue to work without modification.
     */
    abstract SetProperty<String> getConfigurationsToExcludeProperty()

    Set<String> getConfigurationsToExclude() {
        return configurationsToExcludeProperty.get()
    }

    void setConfigurationsToExclude(Iterable<String> values) {
        configurationsToExcludeProperty.set(values)
    }

    /** Message to append when missing versions are detected. Default: empty */
    abstract Property<String> getMissingVersionsMessageAddition()

    /** Message to append when resolved version does not match locked. Default: empty */
    abstract Property<String> getResolvedVersionDoesNotEqualLockedVersionMessageAddition()

    /**
     * Task names to exclude from verification. Default: empty set
     *
     * The backing SetProperty is exposed via {@link #getTasksToExcludeProperty()} for
     * config-cache-compatible task wiring. The getter/setter here preserve the old Set&lt;String&gt;
     * API so existing build scripts continue to work without modification.
     */
    abstract SetProperty<String> getTasksToExcludeProperty()

    Set<String> getTasksToExclude() {
        return tasksToExcludeProperty.get()
    }

    void setTasksToExclude(Iterable<String> values) {
        tasksToExcludeProperty.set(values)
    }

    /** When true (default): full lock validation. When false: only resolution errors, config cache compatible. */
    abstract Property<Boolean> getEnableLockFileValidation()

    DependencyResolutionVerifierExtension() {
        shouldFailTheBuild.convention(true)
        configurationsToExcludeProperty.convention([] as Set)
        missingVersionsMessageAddition.convention('')
        resolvedVersionDoesNotEqualLockedVersionMessageAddition.convention('')
        tasksToExcludeProperty.convention([] as Set)
        enableLockFileValidation.convention(true)
    }
}
