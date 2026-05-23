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
 *
 * <h3>SetProperty backward-compat bridge convention</h3>
 *
 * Every {@code SetProperty<String>} field follows a two-name convention to satisfy two
 * incompatible requirements simultaneously:
 *
 * <ol>
 *   <li><b>Config-cache safety / lazy task wiring</b> — Gradle requires a {@code SetProperty}
 *       (not a plain {@code Set}) to wire values into task parameters without resolving them at
 *       configuration time. The abstract getter named {@code getFooProperty()} is the Gradle-managed
 *       backing store used by internal plugin code for this purpose.</li>
 *   <li><b>Backward-compatible Groovy DSL</b> — Before the {@code cd58be4 "modernize project"}
 *       commit, all these fields were plain {@code Set<String>} properties. Hundreds of downstream
 *       build scripts and plugins relied on Groovy property-assignment syntax
 *       ({@code extension.foo = ['a', 'b']}) and on the getter returning a {@code Set<String>}.
 *       Switching to {@code SetProperty} broke that syntax because Gradle does not generate a
 *       {@code void setFoo(Iterable<String>)} setter for {@code SetProperty} fields — it only
 *       generates {@code SetProperty<String> getFoo()}.  The concrete {@code Set<String> getFoo()}
 *       getter and {@code void setFoo(Iterable<String>)} setter restore the old public API.</li>
 * </ol>
 *
 * <p><b>Do not merge these two into one.</b> Removing the {@code *Property} getter breaks
 * config-cache-safe task wiring. Removing the {@code Set<String>} getter/setter pair breaks
 * downstream Groovy build scripts. Both sides of the bridge must be kept.</p>
 *
 * <p>Internal plugin code (tasks, configurers, helpers) always calls {@code fooProperty} directly.
 * External callers (build scripts, third-party plugins) see only {@code foo} as a {@code Set<String>}.</p>
 */
abstract class DependencyLockExtension {

    /**
     * Name of the lock file to generate.
     * Default: 'dependencies.lock'
     *
     * The backing Property is exposed via {@link #getLockFileProperty()} for config-cache-compatible
     * task wiring. The getter/setter here preserve the old {@code String} API so existing build
     * scripts using {@code dependencyLock.lockFile = 'custom.lock'} or reading
     * {@code dependencyLock.lockFile} as a plain String continue to work.
     */
    abstract Property<String> getLockFileProperty()

    String getLockFile() {
        return lockFileProperty.get()
    }

    void setLockFile(String value) {
        lockFileProperty.set(value)
    }

    /**
     * Name of the global lock file.
     * Default: 'global.lock'
     *
     * The backing Property is exposed via {@link #getGlobalLockFileProperty()} for
     * config-cache-compatible task wiring. The getter/setter here preserve the old {@code String}
     * API so existing build scripts continue to work without modification.
     */
    abstract Property<String> getGlobalLockFileProperty()

    String getGlobalLockFile() {
        return globalLockFileProperty.get()
    }

    void setGlobalLockFile(String value) {
        globalLockFileProperty.set(value)
    }

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
     *
     * The backing Property is exposed via {@link #getIncludeTransitivesProperty()} for
     * config-cache-compatible task wiring. The getter/setter here preserve the old {@code boolean}
     * API so existing build scripts continue to work without modification.
     */
    abstract Property<Boolean> getIncludeTransitivesProperty()

    Boolean getIncludeTransitives() {
        return includeTransitivesProperty.get()
    }

    void setIncludeTransitives(Boolean value) {
        includeTransitivesProperty.set(value)
    }

    /**
     * Whether to delay lock application until configuration resolution.
     * Default: true
     *
     * The backing Property is exposed via {@link #getLockAfterEvaluatingProperty()} for
     * config-cache-compatible task wiring. The getter/setter here preserve the old {@code boolean}
     * API so existing build scripts continue to work without modification.
     */
    abstract Property<Boolean> getLockAfterEvaluatingProperty()

    Boolean getLockAfterEvaluating() {
        return lockAfterEvaluatingProperty.get()
    }

    void setLockAfterEvaluating(Boolean value) {
        lockAfterEvaluatingProperty.set(value)
    }

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
        lockFileProperty.convention('dependencies.lock')
        globalLockFileProperty.convention('global.lock')
        configurationNamesProperty.convention([] as Set)
        skippedConfigurationNamesPrefixesProperty.convention([] as Set)
        updateDependenciesProperty.convention([] as Set)
        skippedDependenciesProperty.convention([] as Set)
        includeTransitivesProperty.convention(false)
        lockAfterEvaluatingProperty.convention(true)
        updateDependenciesFailOnInvalidCoordinates.convention(true)
        updateDependenciesFailOnSimultaneousTaskUsage.convention(true)
        updateDependenciesFailOnNonSpecifiedDependenciesToUpdate.convention(true)
        additionalConfigurationsToLockProperty.convention([] as Set)
    }

}
