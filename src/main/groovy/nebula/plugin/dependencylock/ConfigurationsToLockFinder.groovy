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
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

class ConfigurationsToLockFinder {
    private static final Logger LOGGER = Logging.getLogger(ConfigurationsToLockFinder)
    private Project project

    ConfigurationsToLockFinder(Project project) {
        this.project = project
    }

    List<String> findConfigurationsToLock(Set<String> configurationNames, List<String> additionalBaseConfigurationsToLock = new ArrayList<>()) {
        def configurationsToLock = new ArrayList<String>()
        def baseConfigurations = [
                'annotationProcessor',
                'compileClasspath',
                'runtimeClasspath'
        ]
        baseConfigurations.addAll(additionalBaseConfigurationsToLock)

        configurationsToLock.addAll(baseConfigurations)

        def confSuffix = 'CompileClasspath'
        def configurationsWithPrefix = project.configurations.findAll { it.name.contains(confSuffix) }
        configurationsWithPrefix.each {
            def confPrefix = it.name.replace(confSuffix, '')
            configurationsToLock.addAll(returnConfigurationNamesWithPrefix(confPrefix, baseConfigurations))
        }

        // ensure gathered configurations to lock are lockable
        def lockableConfigurationNames = []
        def lockableConfigurations = GenerateLockTask.lockableConfigurations(project, project, configurationNames)
        lockableConfigurations.each {
            lockableConfigurationNames.add(it.name)
        }
        def lockableConfigsToLock = configurationsToLock.findAll {
            lockableConfigurationNames.contains(it)
        }

        def kotlinPlugins = project.plugins.findAll { it.class.name.contains("Kotlin") }
        if (!lockableConfigsToLock.contains('compileClasspath')
                || kotlinPlugins.size() > 0) {
            def defaultConfigurations = project.configurations
                    .findAll { it.name == 'default' }
                    .each { it.isCanBeResolved() }
            if (defaultConfigurations.size() > 0) {
                defaultConfigurations.each {
                    lockableConfigsToLock.add(it.name)
                }
            }
        }

        def sortedLockableConfigs = lockableConfigsToLock.sort()
        return sortedLockableConfigs
    }

    private static List<String> returnConfigurationNamesWithPrefix(it, List<String> baseConfigurations) {
        def configurationNamesWithPrefix = []
        baseConfigurations.each { baseConfig ->
            configurationNamesWithPrefix.add("${it}${baseConfig.capitalize()}".toString())
        }
        return configurationNamesWithPrefix
    }
}
