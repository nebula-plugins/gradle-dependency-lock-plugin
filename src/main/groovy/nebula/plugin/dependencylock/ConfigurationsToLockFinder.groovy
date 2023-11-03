/**
 *
 *  Copyright 2014-2019 Netflix, Inc.
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

package nebula.plugin.dependencylock

import nebula.plugin.dependencylock.tasks.GenerateLockTask
import nebula.plugin.dependencylock.utils.ConfigurationFilters
import nebula.plugin.dependencylock.utils.ConfigurationUtils
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

class ConfigurationsToLockFinder {
    private static final Logger LOGGER = Logging.getLogger(ConfigurationsToLockFinder)
    private Project project

    ConfigurationsToLockFinder(Project project) {
        this.project = project
    }

    List<String> findConfigurationsToLock(Set<String> configurationNames, Collection<String> additionalBaseConfigurationsToLock = new ArrayList<>()) {
        Collection<String> gatheredConfigurationNames = gatherConfigurationNames(additionalBaseConfigurationsToLock)
        Collection<String> lockableConfigurationNames = gatherLockableConfigurationNames(configurationNames, gatheredConfigurationNames)
        Collection<String> sortedLockableConfigNames = lockableConfigurationNames.sort()
        return sortedLockableConfigNames
    }

    private Collection<String> gatherConfigurationNames(Collection<String> additionalBaseConfigurationsToLock) {
        def configurationsToLock = new HashSet<String>()
        def baseConfigurations = [
                'annotationProcessor',
                'compileClasspath',
                'runtimeClasspath'
        ]
        baseConfigurations.addAll(additionalBaseConfigurationsToLock)

        configurationsToLock.addAll(baseConfigurations)

        def configurationsThatMatchSuffixes = ConfigurationFilters.findAllConfigurationsThatMatchSuffixes(project.configurations, baseConfigurations).collect {
            it.name
        }
        configurationsToLock.addAll(configurationsThatMatchSuffixes)

        def originatingConfigurationsToAlsoLock = new HashSet<String>()
        configurationsToLock.each { nameOfConfToLock ->
            originatingConfigurationsToAlsoLock.addAll(
                    findOriginatingConfigurationsOf(nameOfConfToLock, originatingConfigurationsToAlsoLock)
            )
        }
        configurationsToLock.addAll(originatingConfigurationsToAlsoLock)

        return configurationsToLock.sort()
    }

    private Collection<String> gatherLockableConfigurationNames(Collection<String> configurationNames, Collection<String> gatheredConfigurations) {
        def lockableConfigurationNames = []
        def lockableConfigurations = ConfigurationUtils.lockableConfigurations(project, configurationNames as Set)
        lockableConfigurations.each {
            lockableConfigurationNames.add(it.name)
        }
        def lockableConfigsToLock = gatheredConfigurations.findAll {
            lockableConfigurationNames.contains(it)
        }
        lockableConfigsToLock
    }

    private Collection<String> findOriginatingConfigurationsOf(String nameOfConfToLock, Set<String> accumulator) {
        def conf = project.configurations.findByName(nameOfConfToLock)
        if (conf == null) {
            return []
        }
        accumulator.add(conf.name)
        if (conf.extendsFrom.size() != 0) {
            def parentConfigs = new HashSet<String>()
            conf.extendsFrom.each { parentConf ->
                if (!accumulator.contains(parentConf.name)) {
                    parentConfigs.addAll(findOriginatingConfigurationsOf(parentConf.name, accumulator))
                }
            }
            accumulator.addAll(parentConfigs)
            return accumulator
        } else {
            return []
        }
    }
}
