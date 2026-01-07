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
package nebula.plugin.dependencylock.tasks

import nebula.plugin.dependencylock.DependencyLockExtension
import nebula.plugin.dependencylock.DependencyLockTaskConfigurer
import nebula.plugin.dependencylock.DependencyLockWriter
import nebula.plugin.dependencylock.exceptions.DependencyLockException
import nebula.plugin.dependencylock.model.LockKey
import nebula.plugin.dependencylock.model.LockValue
import nebula.plugin.dependencylock.utils.ConfigurationFilters
import nebula.plugin.dependencylock.utils.DependencyLockingFeatureFlags
import org.gradle.api.BuildCancelledException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.deprecation.DeprecationLogger
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault
abstract class GenerateLockTask extends AbstractLockTask {
    private static final Logger LOGGER = Logging.getLogger(GenerateLockTask)

    @Internal
    String description = 'Create a lock file in build/<configured name>'

    @Internal
    Collection<Configuration> configurations = null

    @Internal
    abstract SetProperty<String> getConfigurationNames()

    @Internal
    abstract SetProperty<String> getSkippedConfigurationNames()

    @Internal
    Closure filter = { group, name, version -> true }

    @Input
    @Optional
    abstract SetProperty<String> getSkippedDependencies()

    @OutputFile
    abstract RegularFileProperty getDependenciesLock()

    @Internal
    abstract MapProperty<String, String> getOverrides()

    @Input
    @Optional
    abstract Property<Boolean> getIncludeTransitives()

    // Configuration cache compatibility - Capture project state at configuration time
    @Internal
    abstract DirectoryProperty getProjectDirectory()

    @Internal
    abstract Property<String> getGlobalLockFileName()

    @Internal
    abstract Property<Boolean> getDependencyLockIgnored()

    // Official Gradle Resolution APIs (Approach 1)
    // Captures dependency graphs using Gradle's official APIs instead of Configuration objects
    @Input
    abstract MapProperty<String, Provider<ResolvedComponentResult>> getResolutionResults()

    @Input
    abstract ListProperty<String> getPeerProjectCoordinates()

    @TaskAction
    void lock() {
        final String WRITE_CORE_LOCK_TASK_TO_RUN = "`./gradlew dependencies --write-locks`"
        final String MIGRATE_TO_CORE_LOCK_TASK_NAME = "migrateToCoreLocks"
        
        // Use captured properties instead of project access for configuration cache compatibility
        if (DependencyLockingFeatureFlags.isCoreLockingEnabled()) {
            def globalLockFile = new File(getProjectDirectory().get().asFile, getGlobalLockFileName().get())
            if (globalLockFile.exists()) {
                throw new BuildCancelledException("Legacy global locks are not supported with core locking.\n" +
                        "Please remove global locks.\n" +
                        " - Global locks: ${globalLockFile.absolutePath}")
            }

            throw new BuildCancelledException("generateLock is not supported with core locking.\n" +
                    "Please use $WRITE_CORE_LOCK_TASK_TO_RUN\n" +
                    "or do a one-time migration with `./gradlew $MIGRATE_TO_CORE_LOCK_TASK_NAME` to preserve the current lock state")
        }
        
        if (getDependencyLockIgnored().get()) {
            throw new DependencyLockException("Dependency locks cannot be generated. The plugin is disabled for this project (dependencyLock.ignore is set to true)")
        }

        // Use NEW API (Resolution APIs) or OLD API (Configuration objects)
        Map dependencyMap
        if (resolutionResults.isPresent() && !resolutionResults.get().isEmpty()) {
            // NEW API: Configuration cache compatible!
            def resolutionMap = resolutionResults.get()
            def peerCoordinates = peerProjectCoordinates.get()
            dependencyMap = new GenerateLockFromConfigurations().lock(resolutionMap, peerCoordinates)
        } else if (configurations != null && !configurations.isEmpty()) {
            // OLD API: For global lock (NOT configuration cache compatible)
            // This path will be fixed in a following phase
            //TODO: address Invocation of Task.project at execution time has been deprecated.
            DeprecationLogger.whileDisabled {
                dependencyMap = new GenerateLockFromConfigurations().lock(configurations)
            }
        } else {
            // No configurations to lock - valid for projects without dependencies (e.g., root project in multiproject builds)
            dependencyMap = [:]
        }
        
        new DependencyLockWriter(getDependenciesLock().get().asFile, getSkippedDependencies().getOrElse([] as Set)).writeLock(dependencyMap)
    }

    static Collection<Configuration> lockableConfigurations(Project taskProject, Project project, Set<String> configurationNames, Set<String> skippedConfigurationNamesPrefixes = []) {
        Set<Configuration> lockableConfigurations = []
        if (configurationNames.empty) {
            if (Configuration.class.declaredMethods.any { it.name == 'isCanBeResolved' }) {
                lockableConfigurations.addAll project.configurations.findAll {
                    it.canBeResolved && !ConfigurationFilters.safelyHasAResolutionAlternative(it) &&
                            // Always exclude compileOnly and build tools configurations to avoid issues with kotlin plugin
                            !it.name.endsWith("CompileOnly") &&
                            !it.name.endsWith("DependenciesMetadata") &&
                            it.name != "compileOnly" &&
                            it.name != "kotlinBuildToolsApiClasspath"
                }
            } else {
                lockableConfigurations.addAll project.configurations.asList()
            }
        } else {
            // Use named() for lazy lookup (though we resolve with .get() since we need the actual Configuration)
            lockableConfigurations.addAll configurationNames.collect { project.configurations.named(it).get() }
        }

        lockableConfigurations.removeAll {
            Configuration configuration -> skippedConfigurationNamesPrefixes.any {
                String prefix -> configuration.name.startsWith(prefix)
            }
        }
        return lockableConfigurations
    }

    static Collection<Configuration> filterNonLockableConfigurationsAndProvideWarningsForGlobalLockSubproject(Project subproject, Set<String> configurationNames, Collection<Configuration> lockableConfigurations) {
        if (configurationNames.size() > 0) {
            Collection<String> warnings = new HashSet<>()

            Collection<Configuration> consumableLockableConfigurations = new ArrayList<>()
            lockableConfigurations.each { conf ->
                Collection<String> warningsForConfiguration = provideWarningsForConfiguration(conf, subproject)
                warnings.addAll(warningsForConfiguration)
                if (warningsForConfiguration.isEmpty()) {
                    consumableLockableConfigurations.add(conf)
                }
            }

            configurationNames.each { nameToLock ->
                if (!lockableConfigurations.collect { it.name }.contains(nameToLock)) {
                    Configuration confThatWillNotBeLocked = subproject.configurations.findByName(nameToLock)
                    if (confThatWillNotBeLocked == null) {
                        String message = "Global lock warning: project '${subproject.name}' requested locking a configuration which cannot be locked: '${nameToLock}'"
                        warnings.add(message)
                    } else {
                        warnings.addAll(provideWarningsForConfiguration(confThatWillNotBeLocked, subproject))
                    }
                }
            }

            if (warnings.size() > 0) {
                warnings.add("Requested configurations for global locks must be resolvable, consumable, and without resolution alternatives.\n" +
                        "You can remove the configuration 'dependencyLock.configurationNames' to stop this customization.\n" +
                        "If you wish to lock only specific configurations, please update 'dependencyLock.configurationNames' with other configurations.\n" +
                        "Please read more about this at:\n" +
                        "- https://docs.gradle.org/current/userguide/java_plugin.html#sec:java_plugin_and_dependency_management\n" +
                        "- https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_configurations_graph")
                LOGGER.warn('--------------------\n' + warnings.sort().join("\n") + '\n--------------------')
            }
            return consumableLockableConfigurations
        }

        return lockableConfigurations
    }

    private static Collection<String> provideWarningsForConfiguration(Configuration conf, Project subproject) {
        Collection<String> errorMessages = new HashSet<>()

        if (!ConfigurationFilters.canSafelyBeConsumed(conf)) {
            String message = "Global lock warning: project '${subproject.name}' requested locking a configuration which cannot be consumed: '${conf.name}'"
            errorMessages.add(message)
        }
        if (!ConfigurationFilters.canSafelyBeResolved(conf)) {
            String message = "Global lock warning: project '${subproject.name}' requested locking a configuration which cannot be resolved: '${conf.name}'"
            errorMessages.add(message)
        }
        if (ConfigurationFilters.safelyHasAResolutionAlternative(conf)) {
            String message = "Global lock warning: project '${subproject.name}' requested locking a deprecated configuration '${conf.name}' " +
                    "which has resolution alternatives: ${conf.getResolutionAlternatives()}"
            errorMessages.add(message)
        }

        return errorMessages
    }

    class GenerateLockFromConfigurations {
        /**
         * Generate lock file using Gradle's official Resolution API (NEW API - Configuration Cache Compatible).
         * @param resolutionMap Map of configuration name to Provider<ResolvedComponentResult>
         * @param peerCoordinates List of peer project coordinates in "group:name" format
         * @return Map of LockKey to LockValue
         */
        Map<LockKey, LockValue> lock(Map<String, Provider<ResolvedComponentResult>> resolutionMap, List<String> peerCoordinates) {
            Map<LockKey, LockValue> deps = [:].withDefault { new LockValue() }

            // Convert peer coordinates to a Set for fast lookup
            Set<String> peerSet = new HashSet<>(peerCoordinates.collect { it.toString() })

            resolutionMap.each { String configName, Provider<ResolvedComponentResult> rootProvider ->
                ResolvedComponentResult root = rootProvider.get()

                // Process first-level dependencies
                root.dependencies.each { DependencyResult depResult ->
                    if (depResult instanceof ResolvedDependencyResult) {
                        ResolvedDependencyResult resolvedDep = (ResolvedDependencyResult) depResult
                        def component = resolvedDep.selected

                        def moduleVersion = component.moduleVersion
                        if (moduleVersion == null) {
                            return // Skip if no module version
                        }

                        // Apply filter
                        if (!filter(moduleVersion.group, moduleVersion.name, moduleVersion.version)) {
                            return
                        }

                        def key = new LockKey(group: moduleVersion.group, artifact: moduleVersion.name, configuration: configName)
                        String coordinate = "${moduleVersion.group}:${moduleVersion.name}".toString()

                        // Check if this is a peer project
                        if (!peerSet.contains(coordinate)) {
                            // Standard external dependency
                            deps[key].locked = moduleVersion.version
                        } else {
                            // Project dependency
                            deps[key].project = true

                            // If we don't include transitives, handle project's first-level deps
                            if (!getIncludeTransitives().getOrElse(false)) {
                                handleSiblingTransitivesNew(component, configName, deps, peerSet)
                            }
                        }

                        // If requested, lock all transitive dependencies
                        if (getIncludeTransitives().getOrElse(false)) {
                            deps[key].childrenVisited = true
                            handleTransitiveNew(component, configName, deps, peerSet, key)
                        }
                    }
                }
            }

            // Add overrides
            getOverrides().getOrElse([:]).each { String k, String overrideVersion ->
                def (overrideGroup, overrideArtifact) = k.tokenize(':')
                deps.each { depLockKey, depValue ->
                    if (depLockKey.group == overrideGroup && depLockKey.artifact == overrideArtifact) {
                        depValue.viaOverride = overrideVersion
                    }
                }
            }

            return deps
        }

        /**
         * Handle transitive dependencies of project dependencies (when includeTransitives is false).
         * Uses NEW Resolution API.
         */
        private void handleSiblingTransitivesNew(ResolvedComponentResult sibling, String configName, Map<LockKey, LockValue> deps, Set<String> peerSet) {
            def moduleVersion = sibling.moduleVersion
            if (moduleVersion == null) return

            def parent = new LockKey(group: moduleVersion.group, artifact: moduleVersion.name, configuration: configName)

            sibling.dependencies.each { DependencyResult depResult ->
                if (depResult instanceof ResolvedDependencyResult) {
                    def component = ((ResolvedDependencyResult) depResult).selected
                    def childModuleVersion = component.moduleVersion
                    if (childModuleVersion == null) return

                    def key = new LockKey(group: childModuleVersion.group, artifact: childModuleVersion.name, configuration: configName)
                    String coordinate = "${childModuleVersion.group}:${childModuleVersion.name}".toString()

                    // Record where this dependency came from
                    deps[key].firstLevelTransitive << parent

                    if (peerSet.contains(coordinate)) {
                        // Another project dependency
                        deps[key].project = true

                        if (!deps[key].childrenVisited && component.dependencies.size() > 0) {
                            deps[key].childrenVisited = true
                            handleSiblingTransitivesNew(component, configName, deps, peerSet)
                        }
                    } else {
                        // External dependency
                        deps[key].locked = childModuleVersion.version
                    }
                }
            }
        }

        /**
         * Handle transitive dependencies recursively (when includeTransitives is true).
         * Uses NEW Resolution API.
         */
        private void handleTransitiveNew(ResolvedComponentResult component, String configName, Map<LockKey, LockValue> deps, Set<String> peerSet, LockKey parent) {
            component.dependencies.each { DependencyResult depResult ->
                if (depResult instanceof ResolvedDependencyResult) {
                    def childComponent = ((ResolvedDependencyResult) depResult).selected
                    def moduleVersion = childComponent.moduleVersion
                    if (moduleVersion == null) return

                    def key = new LockKey(group: moduleVersion.group, artifact: moduleVersion.name, configuration: configName)
                    String coordinate = "${moduleVersion.group}:${moduleVersion.name}".toString()

                    // Visit each dependency only once
                    if (!deps[key].childrenVisited) {
                        if (peerSet.contains(coordinate)) {
                            deps[key].project = true
                        } else {
                            deps[key].locked = moduleVersion.version
                        }

                        if (childComponent.dependencies.size() > 0) {
                            deps[key].childrenVisited = true
                            handleTransitiveNew(childComponent, configName, deps, peerSet, key)
                        }
                    }

                    // Record the transitive relationship
                    deps[key].transitive << parent
                }
            }
        }

        /**
         * OLD API: Generate lock file using Configuration objects (for global lock - NOT config cache compatible).
         * @param confs Collection of Configuration objects
         * @return Map of LockKey to LockValue
         */
        Map<LockKey, LockValue> lock(Collection<Configuration> confs) {
            Map<LockKey, LockValue> deps = [:].withDefault { new LockValue() }

            // Peers are all the projects in the build to which this plugin has been applied.
            def peers = project.rootProject.allprojects.collect { new LockKey(group: it.group, artifact: it.name) }

            confs.each { Configuration configuration ->
                // Lock the version of each dependency specified in the build script as resolved by Gradle.
                def resolvedDependencies = configuration.resolvedConfiguration.firstLevelModuleDependencies
                def filteredResolvedDependencies = resolvedDependencies.findAll { ResolvedDependency resolved ->
                    filter(resolved.moduleGroup, resolved.moduleName, resolved.moduleVersion)
                }

                filteredResolvedDependencies.each { ResolvedDependency resolved ->
                    def key = new LockKey(group: resolved.moduleGroup, artifact: resolved.moduleName, configuration: configuration.name)

                    // If this dependency does not exist in our list of peers, it is a standard dependency. Otherwise, it is
                    // a project dependency.
                    if (!isKeyInPeerList(key, peers)) {
                        deps[key].locked = resolved.moduleVersion
                    } else {
                        // Project dependencies don't have a version so they must be treated differently. Record the project
                        // as an explicit dependency, but do not lock it to a version.
                        deps[key].project = true

                        // If we don't include transitive dependencies, then we must lock the first-level "transitive"
                        // dependencies of each project dependency.
                        if (!getIncludeTransitives().getOrElse(false)) {
                            handleSiblingTransitives(resolved, configuration.name, deps, peers)
                        }
                    }

                    // If requested, lock all the transitive dependencies of the declared top-level dependencies.
                    if (getIncludeTransitives().getOrElse(false)) {
                        deps[key].childrenVisited = true
                        resolved.children.each { handleTransitive(it, configuration.name, deps, peers, key) }
                    }
                }
            }

            // Add all the overrides to the locked dependencies and record whether a specified override modified a
            // preexisting dependency.
            getOverrides().getOrElse([:]).each { String k, String overrideVersion ->
                def (overrideGroup, overrideArtifact) = k.tokenize(':')
                deps.each { depLockKey, depValue ->
                    if (depLockKey.group == overrideGroup && depLockKey.artifact == overrideArtifact) {
                        depValue.viaOverride = overrideVersion
                    }
                }
            }

            return deps
        }

        private void handleSiblingTransitives(ResolvedDependency sibling, String configName, Map<LockKey, LockValue> deps, List peers) {
            def parent = new LockKey(group: sibling.moduleGroup, artifact: sibling.moduleName, configuration: sibling.configuration)
            sibling.children.each { ResolvedDependency dependency ->
                def key = new LockKey(group: dependency.moduleGroup, artifact: dependency.moduleName, configuration: configName)

                // Record the project[s] from which this dependency originated.
                deps[key].firstLevelTransitive << parent

                // Lock the transitive dependencies of each project dependency, recursively.
                if (isKeyInPeerList(key, peers)) {
                    deps[key].project = true

                    // Multiple configurations may specify dependencies on the same project, and multiple projects might
                    // also be dependent on the same project. We only need to record the top-level transitive dependencies
                    // once for each project. Flag a project as visited as soon as we encounter it.
                    if ((dependency.children.size() > 0) && !deps[key].childrenVisited) {
                        deps[key].childrenVisited = true
                        handleSiblingTransitives(dependency, configName, deps, peers)
                    }
                } else {
                    deps[key].locked = dependency.moduleVersion
                }
            }
        }

        private void handleTransitive(ResolvedDependency transitive, String configName, Map<LockKey, LockValue> deps, List peers, LockKey parent) {
            def key = new LockKey(group: transitive.moduleGroup, artifact: transitive.moduleName, configuration: configName)

            // Multiple dependencies may share any subset of their transitive dependencies. Each dependency only needs to be
            // visited once so flag it once we visit it.
            if (!deps[key].childrenVisited) {

                // Lock each dependency and its children, recursively. Don't forget transitive project dependencies.
                if (!isKeyInPeerList(key, peers)) {
                    deps[key].locked = transitive.moduleVersion
                } else {
                    deps[key].project = true
                }
                if (transitive.children.size() > 0) {
                    deps[key].childrenVisited = true
                }
                transitive.children.each { handleTransitive(it, configName, deps, peers, key) }
            }

            // Record the dependencies from which this artifact originated transitively.
            deps[key].transitive << parent
        }

        private static def isKeyInPeerList(LockKey lockKey, List peers) {
            return peers.any {
                it.group == lockKey.group && it.artifact == lockKey.artifact
            }
        }
    }
}
