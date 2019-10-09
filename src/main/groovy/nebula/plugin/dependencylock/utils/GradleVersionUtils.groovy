/**
 *
 *  Copyright 2019 Netflix, Inc.
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

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.util.GradleVersion


class GradleVersionUtils {
    static boolean currentGradleVersionIsLessThan(String version) {
        GradleVersion.current().baseVersion < GradleVersion.version(version)
    }

    static Collection<Configuration> findAllConfigurationsThatResolveButHaveAlternatives(Project project) {
        project
                .configurations
                .stream()
                .filter {
                    shouldThisConfigurationBeResolvedAfterGradle60(it)
                }
                .collect()
    }

    static boolean shouldThisConfigurationBeResolvedAfterGradle60(Configuration configuration) {
        boolean hasAResolutionAlternative = false

        def method = configuration.metaClass.getMetaMethod('getResolutionAlternatives')
        if (method != null) {
            def alternatives = configuration.getResolutionAlternatives()
            if (alternatives != null && alternatives.size > 0) {
                hasAResolutionAlternative = true
            }
        }

        configuration.isCanBeResolved() && hasAResolutionAlternative
    }
}
