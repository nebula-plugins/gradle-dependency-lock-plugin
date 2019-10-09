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
    private static final List<String> configurationsThatShouldNotBeResolvedAfterGradle60 = ['compile', 'compileOnly', 'runtime']

    static boolean currentGradleVersionIsLessThan(String version) {
        GradleVersion.current().baseVersion < GradleVersion.version(version)
    }

    static Collection<Configuration> findAllConfigurationsThatShouldNotBeResolvedAfterGradle60(Project project) {
        ConfigurationFilter.findAllConfigurationsThatMatchSuffixes(project.configurations, configurationsThatShouldNotBeResolvedAfterGradle60)
    }

    static boolean shouldThisConfigurationBeResolvedAfterGradle60(Configuration configuration) {
        ConfigurationFilter.configurationMatchesSuffixes(configuration, configurationsThatShouldNotBeResolvedAfterGradle60)
    }
}
