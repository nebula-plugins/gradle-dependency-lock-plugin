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
import nebula.plugin.dependencylock.utils.CoreLocking
import org.gradle.api.BuildCancelledException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

class GenerateLockTask extends AbstractLockTask {
    private String WRITE_CORE_LOCK_TASK_TO_RUN = "`./gradlew dependencies --write-locks`"
    private String MIGRATE_TO_CORE_LOCK_TASK_NAME = "migrateToCoreLocks"
    private static final Logger LOGGER = Logging.getLogger(GenerateLockTask)

    @Internal
    String description = 'Create a lock file in build/<configured name>'

    @Internal
    Collection<Configuration> configurations = []

    @Internal
    Set<String> configurationNames

    @Internal
    Closure filter = { group, name, version -> true }

    @Input
    @Optional
    Set<String> skippedDependencies = []

    @Internal
    File dependenciesLock

    @Internal
    Map<String, String> overrides

    @Input
    @Optional
    Boolean includeTransitives = false

    @TaskAction
    void lock() {
        if (CoreLocking.isCoreLockingEnabled()) {
            def dependencyLockExtension = project.extensions.findByType(DependencyLockExtension)
            def globalLockFile = new File(project.projectDir, dependencyLockExtension.globalLockFile)
            if (globalLockFile.exists()) {
                throw new BuildCancelledException("Legacy global locks are not supported with core locking.\n" +
                        "Please remove global locks.\n" +
                        " - Global locks: ${globalLockFile.absolutePath}")
            }

            throw new BuildCancelledException("generateLock is not supported with core locking.\n" +
                    "Please use $WRITE_CORE_LOCK_TASK_TO_RUN\n" +
                    "or do a one-time migration with `./gradlew $MIGRATE_TO_CORE_LOCK_TASK_NAME` to preserve the current lock state")
        }
        if (DependencyLockTaskConfigurer.shouldIgnoreDependencyLock(project)) {
            throw new DependencyLockException("Dependency locks cannot be generated. The plugin is disabled for this project (dependencyLock.ignore is set to true)")
        }
        Collection<Configuration> confs = getConfigurations() ?: lockableConfigurations(project, project, getConfigurationNames())
        Map dependencyMap = new GenerateLockFromConfigurations().lock(confs)
        new DependencyLockWriter(getDependenciesLock(), getSkippedDependencies()).writeLock(dependencyMap)
    }

    static Collection<Configuration> lockableConfigurations(Project taskProject, Project project, Set<String> configurationNames) {
        if (configurationNames.empty) {
            if (Configuration.class.declaredMethods.any { it.name == 'isCanBeResolved' }) {
                project.configurations.findAll {
                    if (taskProject == project) {
                        it.canBeResolved && !ConfigurationFilters.safelyHasAResolutionAlternative(it)
                    } else {
                        it.canBeResolved && it.canBeConsumed && !ConfigurationFilters.safelyHasAResolutionAlternative(it)
                    }
                }
            } else {
                project.configurations.asList()
            }
        } else {
            configurationNames.collect { project.configurations.getByName(it) }
        }
    }

    static Collection<Configuration> filterNonLockableConfigurationsAndProvideWarningsForGlobalLockSubproject(Project subproject, Set<String> configurationNames, Collection<Configuration> lockableConfigurations) {
        if (configurationNames.size() > 0) {
            Collection<String> errorMessages = new HashSet<>()

            Collection<Configuration> consumableLockableConfigurations = new ArrayList<>()
            lockableConfigurations.each { conf ->
                boolean confHasError = false
                if (!ConfigurationFilters.canSafelyBeConsumed(conf)) {
                    String message = "Global lock warning: project '${subproject.name}' requested locking a configuration which cannot be consumed: '${conf.name}'"
                    errorMessages.add(message)
                    confHasError = true
                }
                if (!ConfigurationFilters.canSafelyBeResolved(conf)) {
                    String message = "Global lock warning: project '${subproject.name}' requested locking a configuration which cannot be resolved: '${conf.name}'"
                    errorMessages.add(message)
                    confHasError = true
                }
                if (ConfigurationFilters.safelyHasAResolutionAlternative(conf)) {
                    String message = "Global lock warning: project '${subproject.name}' requested locking a deprecated configuration '${conf.name}' " +
                            "which has resolution alternatives: ${conf.getResolutionAlternatives()}"
                    errorMessages.add(message)
                    confHasError = true
                }
                if (!confHasError) {
                    consumableLockableConfigurations.add(conf)
                }
            }

            configurationNames.each { nameToLock ->
                if (!lockableConfigurations.collect { it.name }.contains(nameToLock)) {
                    Configuration confThatWillNotBeLocked = subproject.configurations.findByName(nameToLock)
                    if (confThatWillNotBeLocked == null) {
                        String message = "Global lock warning: project '${subproject.name}' requested locking a configuration which cannot be locked: '${nameToLock}'"
                        errorMessages.add(message)
                    }
                }
            }

            if (errorMessages.size() > 0) {
                errorMessages.add("Requested configurations for global locks must be resolvable, consumable, and without resolution alternatives.\n" +
                        "You can remove the configuration 'dependencyLock.configurationNames' to stop this customization.\n" +
                        "If you wish to lock only specific configurations, please update 'dependencyLock.configurationNames' " +
                        "to use consumable configurations such as 'apiElements', 'runtimeElements', and/or 'default' configurations " +
                        "instead of the non-consumable configuration(s) described above. \n" +
                        "Please read more about this at:\n" +
                        "- https://docs.gradle.org/current/userguide/java_plugin.html#sec:java_plugin_and_dependency_management and " +
                        "- https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_configurations_graph")
                LOGGER.warn('--------------------\n' + errorMessages.sort().join("\n") + '\n--------------------')
            }
            return consumableLockableConfigurations
        }

        return lockableConfigurations
    }

    class GenerateLockFromConfigurations {
        Map<LockKey, LockValue> lock(Collection<Configuration> confs) {
            Map<LockKey, LockValue> deps = [:].withDefault { new LockValue() }

            // Peers are all the projects in the build to which this plugin has been applied.
            def peers = project.rootProject.allprojects.collect { new LockKey(group: it.group, artifact: it.name) }

            confs.each { Configuration configuration ->
                // Capture the version of each dependency as requested in the build script for reference.
                def externalDependencies = configuration.allDependencies.withType(ExternalDependency)
                def filteredExternalDependencies = externalDependencies.findAll { Dependency dependency ->
                    filter(dependency.group, dependency.name, dependency.version)
                }
                filteredExternalDependencies.each { ExternalDependency dependency ->
                    def key = new LockKey(group: dependency.group, artifact: dependency.name, configuration: configuration.name)
                    deps[key].requested = dependency.version
                }

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
                        if (!getIncludeTransitives()) {
                            handleSiblingTransitives(resolved, configuration.name, deps, peers)
                        }
                    }

                    // If requested, lock all the transitive dependencies of the declared top-level dependencies.
                    if (getIncludeTransitives()) {
                        deps[key].childrenVisited = true
                        resolved.children.each { handleTransitive(it, configuration.name, deps, peers, key) }
                    }
                }
            }

            // Add all the overrides to the locked dependencies and record whether a specified override modified a
            // preexisting dependency.
            getOverrides().each { String k, String overrideVersion ->
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

