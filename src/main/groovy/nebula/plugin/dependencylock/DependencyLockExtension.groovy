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
package nebula.plugin.dependencylock

import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Internal

/**
 * Extension for configuring the dependency lock plugin.
 * Uses Gradle's Property API for lazy configuration and configuration cache compatibility.
 */
abstract class DependencyLockExtension {

    /**
     * Name of the lock file to generate.
     * Default: 'dependencies.lock'
     */
    abstract Property<String> getLockFile()

    /**
     * Name of the global lock file.
     * Default: 'global.lock'
     */
    abstract Property<String> getGlobalLockFile()

    /**
     * Specific configuration names to lock.
     * If empty, all resolvable configurations will be locked.
     * Default: empty set
     *
     * The backing SetProperty is exposed via {@link #getConfigurationNamesProperty()} for
     * config-cache-compatible task wiring. The getter/setter here preserve the old Set&lt;String&gt;
     * API so existing build scripts continue to work without modification.
     */
    abstract SetProperty<String> getConfigurationNamesProperty()

    Set<String> getConfigurationNames() {
        return configurationNamesProperty.get()
    }

    void setConfigurationNames(Iterable<String> values) {
        configurationNamesProperty.set(values)
    }

    /**
     * Configuration name prefixes to skip when locking.
     * Default: empty set
     *
     * The backing SetProperty is exposed via {@link #getSkippedConfigurationNamesPrefixesProperty()} for
     * config-cache-compatible task wiring. The getter/setter here preserve the old Set&lt;String&gt;
     * API so existing build scripts and plugins compiled against the previous version continue
     * to work without modification.
     */
    abstract SetProperty<String> getSkippedConfigurationNamesPrefixesProperty()

    Set<String> getSkippedConfigurationNamesPrefixes() {
        return skippedConfigurationNamesPrefixesProperty.get()
    }

    void setSkippedConfigurationNamesPrefixes(Iterable<String> values) {
        skippedConfigurationNamesPrefixesProperty.set(values)
    }

    /**
     * Filter closure for dependencies.
     * Note: Closures are not configuration cache compatible, but this is kept for backward compatibility.
     * Marked as @Internal so it doesn't affect task inputs.
     */
    @Internal
    Closure dependencyFilter = { String group, String name, String version -> true }

    /**
     * Dependencies to update when running updateLock task.
     * Format: 'group:artifact'
     * Default: empty set
     *
     * The backing SetProperty is exposed via {@link #getUpdateDependenciesProperty()} for
     * config-cache-compatible task wiring. The getter/setter here preserve the old Set&lt;String&gt;
     * API so existing build scripts continue to work without modification.
     */
    abstract SetProperty<String> getUpdateDependenciesProperty()

    Set<String> getUpdateDependencies() {
        return updateDependenciesProperty.get()
    }

    void setUpdateDependencies(Iterable<String> values) {
        updateDependenciesProperty.set(values)
    }

    /**
     * Dependencies to skip when generating locks.
     * Default: empty set
     *
     * The backing SetProperty is exposed via {@link #getSkippedDependenciesProperty()} for
     * config-cache-compatible task wiring. The getter/setter here preserve the old Set&lt;String&gt;
     * API so existing build scripts and plugins compiled against the previous version continue
     * to work without modification.
     */
    abstract SetProperty<String> getSkippedDependenciesProperty()

    Set<String> getSkippedDependencies() {
        return skippedDependenciesProperty.get()
    }

    void setSkippedDependencies(Iterable<String> values) {
        skippedDependenciesProperty.set(values)
    }

    /**
     * Whether to include transitive dependencies in the lock file.
     * Default: false
     */
    abstract Property<Boolean> getIncludeTransitives()

    /**
     * Whether to delay lock application until configuration resolution.
     * Default: true
     */
    abstract Property<Boolean> getLockAfterEvaluating()

    /**
     * Whether to fail when invalid dependency coordinates are provided for updates.
     * Default: true
     */
    abstract Property<Boolean> getUpdateDependenciesFailOnInvalidCoordinates()

    /**
     * Whether to fail when update tasks are used simultaneously with generate tasks.
     * Default: true
     */
    abstract Property<Boolean> getUpdateDependenciesFailOnSimultaneousTaskUsage()

    /**
     * Whether to fail when non-specified dependencies need updates.
     * Default: true
     */
    abstract Property<Boolean> getUpdateDependenciesFailOnNonSpecifiedDependenciesToUpdate()

    /**
     * Additional configurations to lock beyond the standard set.
     * Default: empty set
     *
     * The backing SetProperty is exposed via {@link #getAdditionalConfigurationsToLockProperty()} for
     * config-cache-compatible task wiring. The getter/setter here preserve the old Set&lt;String&gt;
     * API so existing build scripts continue to work without modification.
     */
    abstract SetProperty<String> getAdditionalConfigurationsToLockProperty()

    Set<String> getAdditionalConfigurationsToLock() {
        return additionalConfigurationsToLockProperty.get()
    }

    void setAdditionalConfigurationsToLock(Iterable<String> values) {
        additionalConfigurationsToLockProperty.set(values)
    }

    /**
     * Constructor sets default conventions for all properties.
     */
    DependencyLockExtension() {
        lockFile.convention('dependencies.lock')
        globalLockFile.convention('global.lock')
        configurationNamesProperty.convention([] as Set)
        skippedConfigurationNamesPrefixesProperty.convention([] as Set)
        updateDependenciesProperty.convention([] as Set)
        skippedDependenciesProperty.convention([] as Set)
        includeTransitives.convention(false)
        lockAfterEvaluating.convention(true)
        updateDependenciesFailOnInvalidCoordinates.convention(true)
        updateDependenciesFailOnSimultaneousTaskUsage.convention(true)
        updateDependenciesFailOnNonSpecifiedDependenciesToUpdate.convention(true)
        additionalConfigurationsToLockProperty.convention([] as Set)
    }

}
