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

import org.gradle.api.artifacts.ConfigurationContainer

class ConfigurationsToLockFinder {
    private ConfigurationContainer configurations

    ConfigurationsToLockFinder(ConfigurationContainer configurations) {
        this.configurations = configurations
    }

    List<String> findConfigurationsToLock() {

        def configurationsToLock = new ArrayList<>()
        configurationsToLock.addAll(
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
                'runtimeOnly',
                'testAnnotationProcessor',
                'testCompile',
                'testCompileClasspath',
                'testCompileOnly',
                'testImplementation',
                'testRuntime',
                'testRuntimeClasspath',
                'testRuntimeOnly'
        )

        return configurationsToLock
    }
}
