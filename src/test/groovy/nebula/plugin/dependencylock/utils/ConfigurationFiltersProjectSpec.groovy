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

import nebula.plugin.responsible.NebulaIntegTestPlugin
import nebula.test.ProjectSpec
import org.gradle.api.artifacts.Configuration

class ConfigurationFiltersProjectSpec extends ProjectSpec {
    def setup() {
        project.apply plugin: 'java-library'
    }

    def "archives, default, compile, compileOnly, runtime, testCompile, testCompileOnly, and testRuntime should not be resolved after Gradle 6.2"() {
        when:
        def results = project
                .configurations
                .stream()
                .filter {
                    ConfigurationFilters.canSafelyBeResolved(it)
                }
                .filter {
                    ConfigurationFilters.safelyHasAResolutionAlternative(it)
                }
                .collect()

        then:
        if (GradleVersionUtils.currentGradleVersionIsLessThan('6.0')) {
            assert results.size() == 0
        } else if (GradleVersionUtils.currentGradleVersionIsLessThan('6.3')) {
            assert results.size() == 6
        } else if (GradleVersionUtils.currentGradleVersionIsGreaterOrEqualThan('7.0') && GradleVersionUtils.currentGradleVersionIsLessThan('8.0-rc-1')) {
            assert results.size() == 2

            Collection<String> configurationNames = results.collect { (it as Configuration).name }
            assert configurationNames.contains('default')
            assert configurationNames.contains('archives')
        } else if (GradleVersionUtils.currentGradleVersionIsGreaterOrEqualThan('8.0-rc-1')) {
            assert results.size() == 0
        } else {
            assert results.size() == 8

            Collection<String> configurationNames = results.collect { (it as Configuration).name }
            assert configurationNames.contains('default')
            assert configurationNames.contains('archives')
            assert configurationNames.contains('compile')
            assert configurationNames.contains('compileOnly')
            assert configurationNames.contains('runtime')
            assert configurationNames.contains('testCompile')
            assert configurationNames.contains('testCompileOnly')
            assert configurationNames.contains('testRuntime')
        }
    }

    def "facets with similar configurations should not be resolved after Gradle 6.2"() {
        given:
        project.apply plugin: NebulaIntegTestPlugin.class
        project.facets {
            integTest {
                parentSourceSet = 'test'
            }
        }

        when:
        def results = project
                .configurations
                .stream()
                .filter {
                    ConfigurationFilters.canSafelyBeResolved(it)
                }
                .filter {
                    ConfigurationFilters.safelyHasAResolutionAlternative(it)
                }
                .collect()

        then:
        if (GradleVersionUtils.currentGradleVersionIsLessThan('6.0')) {
            assert results.size() == 0
        }  else if (GradleVersionUtils.currentGradleVersionIsLessThan('6.3')) {
            assert results.size() == 9
        } else if (GradleVersionUtils.currentGradleVersionIsGreaterOrEqualThan('7.0') && GradleVersionUtils.currentGradleVersionIsLessThan('8.0-rc-1')) {
            assert results.size() == 2

            Collection<String> configurationNames = results.collect { (it as Configuration).name }
            assert configurationNames.contains('default')
            assert configurationNames.contains('archives')
        } else if (GradleVersionUtils.currentGradleVersionIsGreaterOrEqualThan('8.0-rc-1')) {
            assert results.size() == 0
        } else {
            assert results.size() == 11

            Collection<String> configurationNames = results.collect { (it as Configuration).name }
            assert configurationNames.contains('default')
            assert configurationNames.contains('archives')
            assert configurationNames.contains('compile')
            assert configurationNames.contains('compileOnly')
            assert configurationNames.contains('runtime')
            assert configurationNames.contains('testCompile')
            assert configurationNames.contains('testCompileOnly')
            assert configurationNames.contains('testRuntime')
            assert configurationNames.contains('integTestCompile')
            assert configurationNames.contains('integTestCompileOnly')
            assert configurationNames.contains('integTestRuntime')
        }
    }

    def 'verifies that a configuration can be consumed'() {
        given:
        project.configurations {
            readyToBeConsumed
            doNotConsume

            readyToBeConsumed.setCanBeConsumed(true)
            doNotConsume.setCanBeConsumed(false)
        }

        when:
        def canBeSafelyConsumed = ConfigurationFilters.canSafelyBeConsumed(project.configurations.findByName('readyToBeConsumed'))

        then:
        canBeSafelyConsumed
    }

    def 'verifies that a configuration cannot be consumed'() {
        given:
        project.configurations {
            readyToBeConsumed
            doNotConsume

            readyToBeConsumed.setCanBeConsumed(true)
            doNotConsume.setCanBeConsumed(false)
        }

        when:
        def canBeSafelyConsumed = ConfigurationFilters.canSafelyBeConsumed(project.configurations.findByName('doNotConsume'))

        then:
        !canBeSafelyConsumed
    }

    def 'verifies that a configuration can be resolved'() {
        given:
        project.configurations {
            readyToBeResolved
            doNotResolve

            readyToBeResolved.setCanBeResolved(true)
            doNotResolve.setCanBeResolved(false)
        }

        when:
        def canBeSafelyResolved = ConfigurationFilters.canSafelyBeResolved(project.configurations.findByName('readyToBeResolved'))

        then:
        canBeSafelyResolved
    }

    def 'verifies that a configuration cannot be resolved'() {
        given:
        project.configurations {
            readyToBeResolved
            doNotResolve

            readyToBeResolved.setCanBeResolved(true)
            doNotResolve.setCanBeResolved(false)
        }

        when:
        def canBeSafelyResolved = ConfigurationFilters.canSafelyBeResolved(project.configurations.findByName('doNotResolve'))

        then:
        !canBeSafelyResolved
    }

    def 'verifies that a configuration has resolution alternatives'() {
        given:
        project.configurations {
            deprecatedConfig
            nonDeprecatedConfig
        }
        when:
        def hasAResolutionAlternative = ConfigurationFilters.safelyHasAResolutionAlternative(project.configurations.findByName('deprecatedConfig'))

        then:
        assert !hasAResolutionAlternative
    }

    def 'verifies that a configuration does not have resolution alternatives'() {
        given:
        project.configurations {
            deprecatedConfig
            nonDeprecatedConfig
        }

        when:
        def hasAResolutionAlternative = ConfigurationFilters.safelyHasAResolutionAlternative(project.configurations.findByName('nonDeprecatedConfig'))

        then:
        !hasAResolutionAlternative
    }
}
