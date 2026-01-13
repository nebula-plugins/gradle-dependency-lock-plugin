/*
 * Copyright 2014-2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License atpre5*4nu
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nebula.plugin.dependencylock

import nebula.plugin.BaseIntegrationTestKitSpec
import nebula.plugin.GlobalLockDeprecations
import nebula.plugin.dependencylock.dependencyfixture.Fixture
import org.junit.Rule
import org.junit.contrib.java.lang.system.ProvideSystemProperty

class GlobalLockWithForceLauncherSpec extends BaseIntegrationTestKitSpec implements GlobalLockDeprecations {

    @Rule
    public final ProvideSystemProperty ignoreGlobalLockDeprecations = globalLockDeprecationRule()

    def setupSpec() {
        Fixture.createFixtureIfNotCreated()
    }

    def setup() {
        definePluginOutsideOfPluginBlock = true
        keepFiles = true
        disableConfigurationCache()
    }

    def 'create global locks in multiproject when force is present'() {
        disableConfigurationCache()
        addSubproject('sub1', """\
            dependencies {
                implementation 'test.example:bar:1.1.0'
                implementation 'test.example:foo:2.0.0'
            }
        """.stripIndent())
        addSubproject('sub2', """\
            dependencies {
                implementation 'test.example:transitive:1.+'
            }
            configurations.all {
                resolutionStrategy {
                    force 'test.example:foo:2.0.1'
                }
            }
        """.stripIndent())

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-lock'
                group = 'test'
            }
            subprojects {
                apply plugin: 'java'
                repositories { maven { url = '${Fixture.repo}' } }
            }
            dependencyLock {
                includeTransitives = true
            }
        """.stripIndent()

        when:
        runTasks('generateGlobalLock')

        then:
        String globalLockText = '''\
            {
                "_global_": {
                    "test.example:bar": {
                        "locked": "1.1.0",
                        "transitive": [
                            "test.example:transitive",
                            "test:sub1"
                        ]
                    },
                    "test.example:baz": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foobaz"
                        ]
                    },
                    "test.example:foo": {
                        "locked": "2.0.1",
                        "transitive": [
                            "test.example:bar",
                            "test.example:foobaz",
                            "test:sub1"
                        ]
                    },
                    "test.example:foobaz": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:transitive"
                        ]
                    },
                    "test.example:transitive": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test:sub2"
                        ]
                    },
                    "test:sub1": {
                        "project": true
                    },
                    "test:sub2": {
                        "project": true
                    }
                }
            }'''.stripIndent()
        new File(projectDir, 'build/global.lock').text == globalLockText
    }


}
