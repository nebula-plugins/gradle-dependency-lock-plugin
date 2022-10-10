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

package nebula.plugin.dependencylock.dependencyfixture

import nebula.plugin.dependencylock.DependencyLockExtension
import nebula.plugin.dependencylock.DependencyLockPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project

class WrapperPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.with {
            plugins.apply('com.netflix.nebula.dependency-lock')

            plugins.withType(DependencyLockPlugin) {
                def extension = extensions.getByType(DependencyLockExtension)
                extension.additionalConfigurationsToLock = ["spotbugs"]
            }
        }
        project.logger.lifecycle('hello from test wrapper plugin')
    }
}
