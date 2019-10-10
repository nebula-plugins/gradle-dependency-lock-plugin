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
import org.gradle.api.artifacts.ConfigurationContainer


class ConfigurationFilters {
    static Collection<Configuration> findAllConfigurationsThatMatchSuffixes(ConfigurationContainer configurations, Collection<String> suffixesToMatch) {
        configurations
                .stream()
                .filter { conf ->
                    configurationMatchesSuffixes(conf, suffixesToMatch)
                }
                .collect()
    }

    static boolean configurationMatchesSuffixes(Configuration configuration, Collection<String> suffixesToMatch) {
        return configuration.name.toLowerCase().endsWithAny(*suffixesToMatch.collect { it.toLowerCase() })
    }

    static Collection<Configuration> findAllConfigurationsThatResolveButHaveAlternatives(Project project) {
        project
                .configurations
                .stream()
                .filter {
                    if (Configuration.class.declaredMethods.any { it.name == 'isCanBeResolved' }) {
                        return it.canBeResolved
                    }
                    return true
                }
                .filter {
                    hasAResolutionAlternative(it)
                }
                .collect()
    }

    static boolean hasAResolutionAlternative(Configuration configuration) {
        boolean hasAResolutionAlternative = false

        // 'getResolutionAlternatives' is a method on DefaultConfiguration as of Gradle 6.0
        def method = configuration.metaClass.getMetaMethod('getResolutionAlternatives')
        if (method != null) {
            def alternatives = configuration.getResolutionAlternatives()
            if (alternatives != null && alternatives.size > 0) {
                hasAResolutionAlternative = true
            }
        }

        hasAResolutionAlternative
    }
}
