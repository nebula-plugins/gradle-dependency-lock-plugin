/**
 *
 *  Copyright 2018 Netflix, Inc.
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

package nebula.plugin.dependencylock.utils

import nebula.plugin.dependencylock.ConfigurationsToLockFinder
import nebula.plugin.dependencylock.DependencyLockExtension
import nebula.plugin.dependencylock.tasks.GenerateLockTask
import org.gradle.api.BuildCancelledException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

class CoreLockingHelper {
    private static final Logger LOGGER = Logging.getLogger(CoreLockingHelper.class)
    private static final String ADDITIONAL_CONFIGS_TO_LOCK = 'dependencyLock.additionalConfigurationsToLock'

    private Project project
    private Boolean shouldLockAllConfigurations
    private synchronized Set<Configuration> configsWithActivatedDependencyLocking
    private synchronized Set<Configuration> configsNotGettingLocked

    boolean hasWriteLocksFlag = project.gradle.startParameter.isWriteDependencyLocks()
    boolean projectIsOffline = project.gradle.startParameter.isOffline()
    boolean hasDependenciesToUpdate = !project.gradle.startParameter.getLockedDependenciesToUpdate().isEmpty()
    boolean isUpdatingDependencies = hasWriteLocksFlag || hasDependenciesToUpdate

    CoreLockingHelper(Project project) {
        this.project = project
        shouldLockAllConfigurations = project.hasProperty("lockAllConfigurations") && (project.property("lockAllConfigurations") as String).toBoolean()
        configsWithActivatedDependencyLocking = new HashSet<Configuration>()
        configsNotGettingLocked = new HashSet<Configuration>()
    }

    void lockSelectedConfigurations(Set<String> configurationNames) {
        if (shouldLockAllConfigurations) {
            project.dependencyLocking {
                it.lockAllConfigurations()
            }
        } else {
            def closureToLockConfigurations = {
                if (!it instanceof Configuration) {
                    throw new BuildCancelledException("There is an issue with the configuration to lock '${it.toString()}'")
                }
                if (!configsWithActivatedDependencyLocking.contains(it) && !configsNotGettingLocked.contains(it)) {
                    if ((it as Configuration).state.toString() != "UNRESOLVED") {
                        LOGGER.info("Will not migrate ${it} as this configuration is not UNRESOLVED")
                        configsNotGettingLocked.add(it as Configuration)
                    } else {
                        it.resolutionStrategy.activateDependencyLocking()
                        LOGGER.debug("Activated ${it} for dependency locking")
                        configsWithActivatedDependencyLocking.add(it as Configuration)
                    }
                }
            }
            runClosureWhenPluginsAreSeen(configurationNames, closureToLockConfigurations)
        }
    }

    void migrateLockedConfigurations(Set<String> configurationNames, Closure closure) {
        runClosureWhenPluginsAreSeen(configurationNames, closure)
    }

    void migrateUnlockedDependenciesClosure(Set<String> configurationNames, Closure closure) {
        runClosureWhenPluginsAreSeen(configurationNames, closure)
    }

    private void runClosureWhenPluginsAreSeen(Set<String> configurationNames, Closure closure) {
        project.plugins.withType(Plugin) { plugin ->
            runClosureOnConfigurations(configurationNames, closure, new ArrayList<String>())
            findAndLockAdditionalConfigurations(configurationNames, closure)
        }
        findAndLockAdditionalConfigurations(configurationNames, closure)
    }

    private void findAndLockAdditionalConfigurations(Set<String> configurationNames, Closure closure) {
        def additionalConfigNames = gatherAdditionalConfigurationsToLock()
        project.configurations.matching { // returns a live collection
            additionalConfigNames.findAll { additionalConfigName ->
                it.name == additionalConfigName
            }
        }.all { it ->
            runClosureOnConfigurations(configurationNames, closure, additionalConfigNames)
        }
    }

    private void runClosureOnConfigurations(Set<String> configurationNames, Closure closure, Collection<String> additionalBaseConfigurationsToLock) {
        Set<Configuration> configurationsToLock
        if (shouldLockAllConfigurations) {
            configurationsToLock = GenerateLockTask.lockableConfigurations(project, project, configurationNames)
        } else {
            configurationsToLock = findConfigurationsToLock(configurationNames, additionalBaseConfigurationsToLock)
        }

        configurationsToLock.each {
            closure(it)
        }
    }

    private Set<Configuration> findConfigurationsToLock(Set<String> configurationNames, Collection<String> additionalBaseConfigurationsToLock) {
        def lockableConfigurationNames = new ConfigurationsToLockFinder(project).findConfigurationsToLock(configurationNames, additionalBaseConfigurationsToLock)

        def lockableConfigurations = new HashSet()
        project.configurations.each {
            if (lockableConfigurationNames.contains(it.name)) {
                lockableConfigurations.add(it)
            }
        }
        return lockableConfigurations
    }

    private Collection<String> gatherAdditionalConfigurationsToLock() {
        def dependencyLockExtension = project.extensions.findByType(DependencyLockExtension)
        def additionalConfigurationsToLockViaProperty = project.hasProperty(ADDITIONAL_CONFIGS_TO_LOCK)
                ? (project[ADDITIONAL_CONFIGS_TO_LOCK] as String).split(",") as Set<String>
                : []
        def additionalConfigurationsToLockViaExtension = dependencyLockExtension.additionalConfigurationsToLock as Set<String>
        def additionalConfigNames = additionalConfigurationsToLockViaProperty + additionalConfigurationsToLockViaExtension
        additionalConfigNames
    }

    void disableCachingWhenUpdatingDependencies() {
        if (isUpdatingDependencies) {
            project.configurations.all({ Configuration configuration ->
                if (configuration.state == Configuration.State.UNRESOLVED) {
                    configuration.resolutionStrategy {
                        cacheDynamicVersionsFor(0, 'seconds')
                        cacheChangingModulesFor(0, 'seconds')
                    }
                }
            })
        }
    }

    void notifyWhenUsingOfflineMode() {
        if (projectIsOffline) {
            LOGGER.lifecycle("${project.name}: offline mode enabled. Using cached dependencies")
        }
    }
}
