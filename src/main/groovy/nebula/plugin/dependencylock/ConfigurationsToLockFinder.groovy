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

package nebula.plugin.dependencylock

import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

class ConfigurationsToLockFinder {
    private static final Logger LOGGER = Logging.getLogger(ConfigurationsToLockFinder)
    private Project project

    ConfigurationsToLockFinder(Project project) {
        this.project = project
    }

    List<String> findConfigurationsToLock() {

        def configurationsToLock = new ArrayList<>()
        def baseConfigurations = [
                'annotationProcessor',
                'apiElements',
                'archives',
                'compile',
                'compileClasspath',
                'compileOnly',
                'default',
                'implementation',
                'runtime',
                'runtimeClasspath',
                'runtimeElements',
                'runtimeOnly']
        configurationsToLock.addAll(baseConfigurations)

        def confSuffix = 'CompileOnly'
        def configurationsWithPrefix = project.configurations.findAll { it.name.contains(confSuffix) }
        configurationsWithPrefix.each {
            def confPrefix = it.name.replace(confSuffix, '')
            configurationsToLock.addAll(returnConfigurationNamesWithPrefix(confPrefix))
        }

        configurationsToLock.sort()

        return configurationsToLock
    }

    private static List<String> returnConfigurationNamesWithPrefix(it) {
        def testConfigurations = [
                "${it}AnnotationProcessor".toString(),
                "${it}Compile".toString(),
                "${it}CompileClasspath".toString(),
                "${it}CompileOnly".toString(),
                "${it}Implementation".toString(),
                "${it}Runtime".toString(),
                "${it}RuntimeClasspath".toString(),
                "${it}RuntimeOnly".toString()
        ]
        testConfigurations
    }
}
