/*
 * Copyright 2014-2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nebula.plugin.dependencylock.tasks

import nebula.plugin.dependencylock.dependencyfixture.Fixture
import nebula.plugin.dependencylock.model.ConfigurationResolutionData
import nebula.plugin.dependencylock.model.LockKey
import nebula.plugin.dependencylock.util.LockGenerator
import nebula.test.ProjectSpec
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Unroll

import static nebula.plugin.dependencylock.utils.ConfigurationUtils.lockableConfigurations

class GenerateLockTaskSpec extends ProjectSpec {
    final String taskName = 'generateLock'

    def setupSpec() {
        Fixture.createFixtureIfNotCreated()
    }

    def 'simple lock'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            implementation 'test.example:foo:2.+'
            testImplementation 'test.example:baz:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock.set new File(project.layout.buildDirectory.getAsFile().get(), 'dependencies.lock')
        task.configurationNames.set(['testRuntimeClasspath'])
        task.configure { generateLockTask ->
            generateLockTask.conventionMapping.with {
                configurationResolutionData = lockableConfigurations(project, ['testRuntimeClasspath'] as Set<String>).findAll { it.isCanBeResolved() }.collect {
                    new ConfigurationResolutionData(
                            it.name,
                            it.incoming.resolutionResult.getAllDependencies(),
                            it.incoming.resolutionResult.rootComponent
                    )
                }
            }
        }

        when:
        task.lock()

        then:
        String lockText = '''\
            {
                "testRuntimeClasspath": {
                    "test.example:baz": {
                        "locked": "1.1.0"
                    },
                    "test.example:foo": {
                        "locked": "2.0.1"
                    }
                }
            }'''.stripIndent()
        task.dependenciesLock.get().text == lockText
    }

    def 'simple lock for all lockable configurations'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            implementation 'test.example:foo:2.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock.set new File(project.layout.buildDirectory.getAsFile().get(), 'dependencies.lock')
        Set<String> configurationNames = project.configurations
                .stream()
                .filter { it.isCanBeResolved() && (it?.getResolutionAlternatives()?.isEmpty() || !it?.getResolutionAlternatives()) }
                .collect { it.name }
                .toSet()
        task.configurationNames.set configurationNames

        task.configure { generateLockTask ->
            generateLockTask.conventionMapping.with {
                configurationResolutionData = lockableConfigurations(project, configurationNames).findAll { it.isCanBeResolved() }.collect {
                    new ConfigurationResolutionData(
                            it.name,
                            it.incoming.resolutionResult.getAllDependencies(),
                            it.incoming.resolutionResult.rootComponent
                    )
                }
            }
        }

        when:
        task.lock()

        then:
        String lockText = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly(
                '''\
                    "test.example:foo": {
                        "locked": "2.0.1"
                    }'''.stripIndent())
        task.dependenciesLock.get().text == lockText
    }

    def 'simple lock for all lockable configurations - without skippedConfigurationsPrefixes'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.configurations {
            zinc
            incrementalAnalysisTest
        }
        project.dependencies {
            zinc 'test.example:foo:2.+'
            incrementalAnalysisTest 'test.example:foo:2.+'
            implementation 'test.example:foo:2.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock.set(new File(project.layout.buildDirectory.getAsFile().get(), 'dependencies.lock'))
        def configurationNames = project.configurations
                .stream()
                .filter { it.isCanBeResolved() && (it?.getResolutionAlternatives()?.isEmpty() || !it?.getResolutionAlternatives()) }
                .collect { it.name }
                .toSet()
        task.configurationNames.set configurationNames
        def skippedConfigurationNames = ['zinc', 'incrementalAnalysis'] as Set<String>
        task.skippedConfigurationNames.set(skippedConfigurationNames)
        task.configure { generateLockTask ->
            generateLockTask.conventionMapping.with {
                configurationResolutionData = lockableConfigurations(project, configurationNames, skippedConfigurationNames).findAll { it.isCanBeResolved() }.collect {
                    new ConfigurationResolutionData(
                            it.name,
                            it.incoming.resolutionResult.getAllDependencies(),
                            it.incoming.resolutionResult.rootComponent
                    )
                }
            }
        }

        when:
        task.lock()

        then:
        String lockText = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly(
                '''\
                    "test.example:foo": {
                        "locked": "2.0.1"
                    }'''.stripIndent())
        task.dependenciesLock.get().text == lockText
        !task.dependenciesLock.get().text.contains('"zinc"')
        !task.dependenciesLock.get().text.contains('"incrementalAnalysisTest"')
    }

    def 'skip dependencies via transitives when configured'() {
        project.apply plugin: 'java'
        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            implementation 'test.example:foobaz:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock.set(new File(project.layout.buildDirectory.getAsFile().get(), 'dependencies.lock'))
        task.configurationNames.set(['testRuntimeClasspath'])
        task.skippedDependencies.set(['test.example:foo'])
        task.includeTransitives.set(true)
        task.configure { generateLockTask ->
            generateLockTask.conventionMapping.with {
                configurationResolutionData = lockableConfigurations(project, ['testRuntimeClasspath'] as Set<String>).findAll { it.isCanBeResolved() }.collect {
                    new ConfigurationResolutionData(
                            it.name,
                            it.incoming.resolutionResult.getAllDependencies(),
                            it.incoming.resolutionResult.rootComponent
                    )
                }
            }
        }

        String lockText = '''\
            {
                "testRuntimeClasspath": {
                    "test.example:baz": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foobaz"
                        ]
                    },
                    "test.example:foobaz": {
                        "locked": "1.0.0"
                    }
                }
            }'''.stripIndent()

        when:
        task.lock()

        then:
        task.dependenciesLock.get().text == lockText
    }

    def 'multiproject inter-project dependencies should be excluded'() {
        def common = ProjectBuilder.builder().withName('common').withProjectDir(new File(projectDir, 'common')).withParent(project).build()
        project.subprojects.add(common)
        def app = ProjectBuilder.builder().withName('app').withProjectDir(new File(projectDir, 'app')).withParent(project).build()
        project.subprojects.add(app)

        project.subprojects {
            apply plugin: 'java'
            group = 'test.nebula'
            repositories { maven { url Fixture.repo } }
        }

        app.dependencies {
            implementation app.project(':common')
            implementation 'test.example:foo:2.+'
        }

        GenerateLockTask task = app.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock.set(new File(app.layout.buildDirectory.getAsFile().get(), 'dependencies.lock'))
        task.configurationNames.set( ['testRuntimeClasspath'])
        task.configure { generateLockTask ->
            generateLockTask.conventionMapping.with {
                configurationResolutionData = lockableConfigurations(app, ['testRuntimeClasspath'] as Set<String>).findAll { it.isCanBeResolved() }.collect {
                    new ConfigurationResolutionData(
                            it.name,
                            it.incoming.resolutionResult.getAllDependencies(),
                            it.incoming.resolutionResult.rootComponent
                    )
                }
            }
        }
        task.peers.set(app.rootProject.allprojects.collect { new LockKey(group: it.group, artifact: it.name) })

        when:
        task.lock()

        then:
        String lockText = '''\
            {
                "testRuntimeClasspath": {
                    "test.example:foo": {
                        "locked": "2.0.1"
                    },
                    "test.nebula:common": {
                        "project": true
                    }
                }
            }'''.stripIndent()
        task.dependenciesLock.get().text == lockText
    }

    def 'multiproject inter-project dependencies should be excluded when coming in transitively'() {
        def common = ProjectBuilder.builder().withName('common').withProjectDir(new File(projectDir, 'common')).withParent(project).build()
        project.subprojects.add(common)
        def lib = ProjectBuilder.builder().withName('lib').withProjectDir(new File(projectDir, 'lib')).withParent(project).build()
        project.subprojects.add(lib)
        def app = ProjectBuilder.builder().withName('app').withProjectDir(new File(projectDir, 'app')).withParent(project).build()
        project.subprojects.add(app)

        project.subprojects {
            apply plugin: 'java'
            group = 'test.nebula'
            repositories { maven { url Fixture.repo } }
        }

        lib.dependencies {
            implementation lib.project(':common')
        }

        app.dependencies {
            implementation app.project(':lib')
        }

        GenerateLockTask task = app.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock.set(new File(app.layout.buildDirectory.getAsFile().get(), 'dependencies.lock'))
        task.configurationNames.set(['testRuntimeClasspath'])
        task.includeTransitives.set(true)
        task.configure { generateLockTask ->
            generateLockTask.conventionMapping.with {
                configurationResolutionData = lockableConfigurations(app, ['testRuntimeClasspath'] as Set<String>).findAll { it.isCanBeResolved() }.collect {
                    new ConfigurationResolutionData(
                            it.name,
                            it.incoming.resolutionResult.getAllDependencies(),
                            it.incoming.resolutionResult.rootComponent
                    )
                }
            }
        }
        task.peers.set(app.rootProject.allprojects.collect { new LockKey(group: it.group, artifact: it.name) })

        when:
        task.lock()

        then:
        String lockText = '''\
            {
                "testRuntimeClasspath": {
                    "test.nebula:common": {
                        "project": true,
                        "transitive": [
                            "test.nebula:lib"
                        ]
                    },
                    "test.nebula:lib": {
                        "project": true
                    }
                }
            }'''.stripIndent()
        task.dependenciesLock.get().text == lockText
    }

    def 'multiproject inter-project dependencies should lock first levels'() {
        def common = ProjectBuilder.builder().withName('common').withProjectDir(new File(projectDir, 'common')).withParent(project).build()
        project.subprojects.add(common)
        def lib = ProjectBuilder.builder().withName('lib').withProjectDir(new File(projectDir, 'lib')).withParent(project).build()
        project.subprojects.add(lib)
        def app = ProjectBuilder.builder().withName('app').withProjectDir(new File(projectDir, 'app')).withParent(project).build()
        project.subprojects.add(app)

        project.subprojects {
            apply plugin: 'java'
            group = 'test.nebula'
            repositories { maven { url Fixture.repo } }
        }

        common.dependencies {
            implementation 'test.example:foo:2.+'
            implementation 'test.example:baz:2.+'
        }

        lib.dependencies {
            implementation lib.project(':common')
            implementation 'test.example:baz:1.+'
        }

        app.dependencies {
            implementation app.project(':lib')
        }

        GenerateLockTask task = app.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock.set new File(app.layout.buildDirectory.getAsFile().get(), 'dependencies.lock')
        task.configurationNames.set(['testRuntimeClasspath'])
        task.configure { generateLockTask ->
            generateLockTask.conventionMapping.with {
                configurationResolutionData = lockableConfigurations(app, ['testRuntimeClasspath'] as Set<String>).findAll { it.isCanBeResolved() }.collect {
                    new ConfigurationResolutionData(
                            it.name,
                            it.incoming.resolutionResult.getAllDependencies(),
                            it.incoming.resolutionResult.rootComponent
                    )
                }
            }
        }
        task.peers.set(app.rootProject.allprojects.collect { new LockKey(group: it.group, artifact: it.name) })

        when:
        task.lock()

        then:
        String lockText = '''\
            {
                "testRuntimeClasspath": {
                    "test.example:baz": {
                        "locked": "2.0.0",
                        "transitive": [
                            "test.nebula:common",
                            "test.nebula:lib"
                        ]
                    },
                    "test.example:foo": {
                        "locked": "2.0.1",
                        "transitive": [
                            "test.nebula:common"
                        ]
                    },
                    "test.nebula:common": {
                        "project": true,
                        "transitive": [
                            "test.nebula:lib"
                        ]
                    },
                    "test.nebula:lib": {
                        "project": true
                    }
                }
            }'''.stripIndent()
        task.dependenciesLock.get().text == lockText
    }

    def 'multiproject inter-project dependencies no locked'() {
        def model = ProjectBuilder.builder().withName('model').withProjectDir(new File(projectDir, 'model')).withParent(project).build()
        project.subprojects.add(model)
        def common = ProjectBuilder.builder().withName('common').withProjectDir(new File(projectDir, 'common')).withParent(project).build()
        project.subprojects.add(common)
        def lib = ProjectBuilder.builder().withName('lib').withProjectDir(new File(projectDir, 'lib')).withParent(project).build()
        project.subprojects.add(lib)
        def app = ProjectBuilder.builder().withName('app').withProjectDir(new File(projectDir, 'app')).withParent(project).build()
        project.subprojects.add(app)

        project.subprojects {
            apply plugin: 'java'
            group = 'test.nebula'
            version = '42.0.0'
            repositories { maven { url Fixture.repo } }
        }

        model.dependencies {
            implementation 'test.example:foo:2.+'
        }

        common.dependencies {
            implementation common.project(':model')
        }

        lib.dependencies {
            implementation lib.project(':model')
            implementation lib.project(':common')
        }

        app.dependencies {
            implementation app.project(':model')
            implementation app.project(':common')
            implementation app.project(':lib')
        }

        GenerateLockTask task = app.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock.set new File(app.layout.buildDirectory.getAsFile().get(), 'dependencies.lock')
        task.configurationNames.set(['testRuntimeClasspath'])
        task.configure { generateLockTask ->
            generateLockTask.conventionMapping.with {
                configurationResolutionData = lockableConfigurations(app, ['testRuntimeClasspath'] as Set<String>).findAll { it.isCanBeResolved() }.collect {
                    new ConfigurationResolutionData(
                            it.name,
                            it.incoming.resolutionResult.getAllDependencies(),
                            it.incoming.resolutionResult.rootComponent
                    )
                }
            }
        }
        task.peers.set(app.rootProject.allprojects.collect { new LockKey(group: it.group, artifact: it.name) })

        when:
        task.lock()

        then:
        String lockText = '''\
            {
                "testRuntimeClasspath": {
                    "test.example:foo": {
                        "locked": "2.0.1",
                        "transitive": [
                            "test.nebula:model"
                        ]
                    },
                    "test.nebula:common": {
                        "project": true,
                        "transitive": [
                            "test.nebula:lib"
                        ]
                    },
                    "test.nebula:lib": {
                        "project": true
                    },
                    "test.nebula:model": {
                        "project": true,
                        "transitive": [
                            "test.nebula:common",
                            "test.nebula:lib"
                        ]
                    }
                }
            }'''.stripIndent()
        task.dependenciesLock.get().text == lockText
    }

    def 'simple transitive lock'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            implementation 'test.example:bar:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock.set new File(project.layout.buildDirectory.getAsFile().get(), 'dependencies.lock')
        task.configurationNames.set(['testRuntimeClasspath'])
        task.includeTransitives.set true
        task.configure { generateLockTask ->
            generateLockTask.conventionMapping.with {
                configurationResolutionData = lockableConfigurations(project, ['testRuntimeClasspath'] as Set<String>).findAll { it.isCanBeResolved() }.collect {
                    new ConfigurationResolutionData(
                            it.name,
                            it.incoming.resolutionResult.getAllDependencies(),
                            it.incoming.resolutionResult.rootComponent
                    )
                }
            }
        }

        when:
        task.lock()

        then:
        String lockText = '''\
            {
                "testRuntimeClasspath": {
                    "test.example:bar": {
                        "locked": "1.1.0"
                    },
                    "test.example:foo": {
                        "locked": "1.0.1",
                        "transitive": [
                            "test.example:bar"
                        ]
                    }
                }
            }'''.stripIndent()
        task.dependenciesLock.get().text == lockText
    }

    def 'check circular dependency does not loop infinitely'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            implementation 'circular:a:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock.set new File(project.layout.buildDirectory.getAsFile().get(), 'dependencies.lock')
        task.configurationNames.set(['testRuntimeClasspath'])
        task.includeTransitives.set true
        task.configure { generateLockTask ->
            generateLockTask.conventionMapping.with {
                configurationResolutionData = lockableConfigurations(project, ['testRuntimeClasspath'] as Set<String>).findAll { it.isCanBeResolved() }.collect {
                    new ConfigurationResolutionData(
                            it.name,
                            it.incoming.resolutionResult.getAllDependencies(),
                            it.incoming.resolutionResult.rootComponent
                    )
                }
            }
        }

        when:
        task.lock()

        then:
        String lockText = '''\
            {
                "testRuntimeClasspath": {
                    "circular:a": {
                        "locked": "1.0.0",
                        "transitive": [
                            "circular:b"
                        ]
                    },
                    "circular:b": {
                        "locked": "1.0.0",
                        "transitive": [
                            "circular:a"
                        ]
                    }
                }
            }'''.stripIndent()
        task.dependenciesLock.get().text == lockText
    }

    def 'check for deeper circular dependency'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            implementation 'circular:oneleveldeep:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock.set new File(project.layout.buildDirectory.getAsFile().get(), 'dependencies.lock')
        task.configurationNames.set(['testRuntimeClasspath'])
        task.includeTransitives.set true
        task.configure { generateLockTask ->
            generateLockTask.conventionMapping.with {
                configurationResolutionData = lockableConfigurations(project, ['testRuntimeClasspath'] as Set<String>).findAll { it.isCanBeResolved() }.collect {
                    new ConfigurationResolutionData(
                            it.name,
                            it.incoming.resolutionResult.getAllDependencies(),
                            it.incoming.resolutionResult.rootComponent
                    )
                }
            }
        }

        when:
        task.lock()

        then:
        String lockText = '''\
            {
                "testRuntimeClasspath": {
                    "circular:a": {
                        "locked": "1.0.0",
                        "transitive": [
                            "circular:b",
                            "circular:oneleveldeep"
                        ]
                    },
                    "circular:b": {
                        "locked": "1.0.0",
                        "transitive": [
                            "circular:a"
                        ]
                    },
                    "circular:oneleveldeep": {
                        "locked": "1.0.0"
                    }
                }
            }'''.stripIndent()
        task.dependenciesLock.get().text == lockText
    }

    def 'one level transitive test'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            implementation 'test.example:bar:1.+'
            implementation 'test.example:foobaz:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock.set new File(project.layout.buildDirectory.getAsFile().get(), 'dependencies.lock')
        task.configurationNames.set(['testRuntimeClasspath'])
        task.includeTransitives.set true
        task.configure { generateLockTask ->
            generateLockTask.conventionMapping.with {
                configurationResolutionData = lockableConfigurations(project, ['testRuntimeClasspath'] as Set<String>).findAll { it.isCanBeResolved() }.collect {
                    new ConfigurationResolutionData(
                            it.name,
                            it.incoming.resolutionResult.getAllDependencies(),
                            it.incoming.resolutionResult.rootComponent
                    )
                }
            }
        }
        when:
        task.lock()

        then:
        String lockText = '''\
            {
                "testRuntimeClasspath": {
                    "test.example:bar": {
                        "locked": "1.1.0"
                    },
                    "test.example:baz": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foobaz"
                        ]
                    },
                    "test.example:foo": {
                        "locked": "1.0.1",
                        "transitive": [
                            "test.example:bar",
                            "test.example:foobaz"
                        ]
                    },
                    "test.example:foobaz": {
                        "locked": "1.0.0"
                    }
                }
            }'''.stripIndent()
        task.dependenciesLock.get().text == lockText
    }

    def 'multi-level transitive test'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            implementation 'test.example:transitive:1.0.0'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock.set new File(project.layout.buildDirectory.getAsFile().get(), 'dependencies.lock')
        task.configurationNames.set(['testRuntimeClasspath'])
        task.includeTransitives.set true
        task.configure { generateLockTask ->
            generateLockTask.conventionMapping.with {
                configurationResolutionData = lockableConfigurations(project, ['testRuntimeClasspath'] as Set<String>).findAll { it.isCanBeResolved() }.collect {
                    new ConfigurationResolutionData(
                            it.name,
                            it.incoming.resolutionResult.getAllDependencies(),
                            it.incoming.resolutionResult.rootComponent
                    )
                }
            }
        }
        when:
        task.lock()

        then:
        String lockText = '''\
            {
                "testRuntimeClasspath": {
                    "test.example:bar": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:transitive"
                        ]
                    },
                    "test.example:baz": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foobaz"
                        ]
                    },
                    "test.example:foo": {
                        "locked": "1.0.1",
                        "transitive": [
                            "test.example:bar",
                            "test.example:foobaz"
                        ]
                    },
                    "test.example:foobaz": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:transitive"
                        ]
                    },
                    "test.example:transitive": {
                        "locked": "1.0.0"
                    }
                }
            }'''.stripIndent()
        task.dependenciesLock.get().text == lockText
    }

    @Unroll
    def '#methodName is applied'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            implementation 'test.example:foo:2.+'
            testImplementation 'test.example:baz:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock.set new File(project.layout.buildDirectory.getAsFile().get(), 'dependencies.lock')
        task.configurationNames.set(['testRuntimeClasspath'])
        task.filter.set(filter as Closure)
        task.configure { generateLockTask ->
            generateLockTask.conventionMapping.with {
                configurationResolutionData = lockableConfigurations(project, ['testRuntimeClasspath'] as Set<String>).findAll { it.isCanBeResolved() }.collect {
                    new ConfigurationResolutionData(
                            it.name,
                            it.incoming.resolutionResult.getAllDependencies(),
                            it.incoming.resolutionResult.rootComponent
                    )
                }
            }
        }

        when:
        task.lock()

        then:
        task.dependenciesLock.get().text == lockText

        where:
        methodName << ["negative filter", "positive filter"]
        filter << [{ group, artifact, version -> false }, { group, artifact, version -> artifact == 'foo' }]
        lockText << ['{\n    \n}', '''\
            {
                "testRuntimeClasspath": {
                    "test.example:foo": {
                        "locked": "2.0.1"
                    }
                }
            }'''.stripIndent()]
    }
}
