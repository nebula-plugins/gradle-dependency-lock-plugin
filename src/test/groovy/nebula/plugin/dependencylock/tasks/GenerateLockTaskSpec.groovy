/*
 * Copyright 2014 Netflix, Inc.
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
import nebula.test.ProjectSpec
import org.gradle.testfixtures.ProjectBuilder

class GenerateLockTaskSpec extends ProjectSpec {
    final String taskName = 'generateLock'

    def setupSpec() {
        Fixture.createFixtureIfNotCreated()
    }

    def 'simple lock'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'test.example:foo:2.+'
            testCompile 'test.example:baz:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        task.configurationNames = [ 'testRuntime' ]

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:baz": { "locked": "1.1.0", "requested": "1.+" },
              "test.example:foo": { "locked": "2.0.1", "requested": "2.+" }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
    }

    def 'skip dependencies via transitives when configured'() {
        project.apply plugin: 'java'
        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'test.example:foobaz:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        task.configurationNames = [ 'testRuntime' ]
        task.skippedDependencies = [ 'test.example:foo' ]
        task.includeTransitives = true

        String lockText = '''\
            {
              "test.example:baz": { "locked": "1.0.0", "transitive": [ "test.example:foobaz" ] },
              "test.example:foobaz": { "locked": "1.0.0", "requested": "1.+" }
            }
        '''.stripIndent()

        when:
        task.execute()

        then:
        task.dependenciesLock.text == lockText
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
            compile app.project(':common')
            compile 'test.example:foo:2.+'
        }

        GenerateLockTask task = app.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(app.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:foo": { "locked": "2.0.1", "requested": "2.+" },
              "test.nebula:common": { "project": true }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
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
            compile lib.project(':common')
        }

        app.dependencies {
            compile app.project(':lib')
        }

        GenerateLockTask task = app.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(app.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]
        task.includeTransitives = true

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.nebula:common": { "project": true, "transitive": [ "test.nebula:lib" ] },
              "test.nebula:lib": { "project": true }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
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
            compile 'test.example:foo:2.+'
            compile 'test.example:baz:2.+'
        }

        lib.dependencies {
            compile lib.project(':common')
            compile 'test.example:baz:1.+'
        }

        app.dependencies {
            compile app.project(':lib')
        }

        GenerateLockTask task = app.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(app.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:baz": { "locked": "2.0.0", "firstLevelTransitive": [ "test.nebula:common", "test.nebula:lib" ] },
              "test.example:foo": { "locked": "2.0.1", "firstLevelTransitive": [ "test.nebula:common" ] },
              "test.nebula:common": { "project": true, "firstLevelTransitive": [ "test.nebula:lib" ] },
              "test.nebula:lib": { "project": true }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
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
            compile 'test.example:foo:2.+'
        }

        common.dependencies {
            compile common.project(':model')
        }

        lib.dependencies {
            compile lib.project(':model')
            compile lib.project(':common')
        }

        app.dependencies {
            compile app.project(':model')
            compile app.project(':common')
            compile app.project(':lib')
        }

        GenerateLockTask task = app.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(app.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:foo": { "locked": "2.0.1", "firstLevelTransitive": [ "test.nebula:model" ] },
              "test.nebula:common": { "project": true, "firstLevelTransitive": [ "test.nebula:lib" ] },
              "test.nebula:lib": { "project": true },
              "test.nebula:model": { "project": true, "firstLevelTransitive": [ "test.nebula:common", "test.nebula:lib" ] }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
    }

    def 'simple transitive lock'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'test.example:bar:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]
        task.includeTransitives = true

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:bar": { "locked": "1.1.0", "requested": "1.+" },
              "test.example:foo": { "locked": "1.0.1", "transitive": [ "test.example:bar" ] }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
    }

    def 'check circular dependency does not loop infinitely'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'circular:a:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]
        task.includeTransitives = true

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "circular:a": { "locked": "1.0.0", "requested": "1.+", "transitive": [ "circular:b" ] },
              "circular:b": { "locked": "1.0.0", "transitive": [ "circular:a" ] }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
    }

    def 'check for deeper circular dependency'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'circular:oneleveldeep:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]
        task.includeTransitives = true

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "circular:a": { "locked": "1.0.0", "transitive": [ "circular:b", "circular:oneleveldeep" ] },
              "circular:b": { "locked": "1.0.0", "transitive": [ "circular:a" ] },
              "circular:oneleveldeep": { "locked": "1.0.0", "requested": "1.+" }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
    }

    def 'one level transitive test'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'test.example:bar:1.+'
            compile 'test.example:foobaz:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]
        task.includeTransitives = true

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:bar": { "locked": "1.1.0", "requested": "1.+" },
              "test.example:baz": { "locked": "1.0.0", "transitive": [ "test.example:foobaz" ] },
              "test.example:foo": { "locked": "1.0.1", "transitive": [ "test.example:bar", "test.example:foobaz" ] },
              "test.example:foobaz": { "locked": "1.0.0", "requested": "1.+" }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
    }

    def 'multi-level transitive test'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'test.example:transitive:1.0.0'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]
        task.includeTransitives = true

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:bar": { "locked": "1.0.0", "transitive": [ "test.example:transitive" ] },
              "test.example:baz": { "locked": "1.0.0", "transitive": [ "test.example:foobaz" ] },
              "test.example:foo": { "locked": "1.0.1", "transitive": [ "test.example:bar", "test.example:foobaz" ] },
              "test.example:foobaz": { "locked": "1.0.0", "transitive": [ "test.example:transitive" ] },
              "test.example:transitive": { "locked": "1.0.0", "requested": "1.0.0" }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
    }

    def 'filter is applied'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'test.example:foo:2.+'
            testCompile 'test.example:baz:1.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        task.configurationNames = [ 'testRuntime' ]
        task.filter = filter as Closure

        when:
        task.execute()

        then:
        task.dependenciesLock.text == lockText

        where:
        filter                                            || lockText
        { group, artifact, version -> false }             || '{\n\n}\n'
        { group, artifact, version -> artifact == 'foo' } || '''\
            {
              "test.example:foo": { "locked": "2.0.1", "requested": "2.+" }
            }
        '''.stripIndent()
    }
}
