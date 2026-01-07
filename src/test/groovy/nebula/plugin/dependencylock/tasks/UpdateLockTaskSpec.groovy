/*
 * Copyright 2015-2019 Netflix, Inc.
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

import groovy.json.JsonSlurper
import nebula.plugin.dependencylock.dependencyfixture.Fixture
import nebula.plugin.dependencylock.util.LockGenerator
import org.gradle.testfixtures.ProjectBuilder

class UpdateLockTaskSpec extends LockTaskSpec {
    final String taskName = 'updateLock'

    def setupSpec() {
        Fixture.createFixtureIfNotCreated()
    }

    def setup() {
        project.apply plugin: 'java'
        project.repositories { maven { url Fixture.repo } }
    }

    UpdateLockTask createTask() {
        def task = project.tasks.create(taskName, UpdateLockTask)
        task.dependenciesLock.set(project.layout.buildDirectory.file('dependencies.lock'))
        task.configurationNames.set(LockGenerator.DEFAULT_CONFIG_NAMES)
        wireTaskProperties(task)
        task
    }

    /** Fresh project to avoid test pollution (shared project accumulates deps). */
    private org.gradle.api.Project createFreshProjectForUpdateLockTest() {
        def dir = new File(projectDir, "fresh-update-${UUID.randomUUID()}")
        dir.mkdirs()
        def proj = ProjectBuilder.builder().withName('updateLockTest').withProjectDir(dir).build()
        proj.apply plugin: 'java'
        proj.repositories { maven { url Fixture.repo } }
        proj
    }

    def 'transitives are automatically updated'() {
        def proj = createFreshProjectForUpdateLockTest()
        proj.dependencies {
            implementation 'test.example:bar:1.+'
            implementation 'test.example:qux:1.0.0'
        }

        def lockFile = new File(proj.projectDir, 'dependencies.lock')
        lockFile.parentFile.mkdirs()
        def lockText = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly(
                '''\
                    "test.example:bar": {
                        "locked": "1.0.0"
                    },
                    "test.example:qux": {
                        "locked": "1.0.0"
                    },
                    "test.example:foo": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:bar",
                            "test.example:qux"
                        ]
                    }'''.stripIndent()
        )
        lockFile.text = lockText

        def task = proj.tasks.create(taskName, UpdateLockTask)
        task.dependenciesLock.set(proj.layout.buildDirectory.file('dependencies.lock'))
        task.configurationNames.set(LockGenerator.DEFAULT_CONFIG_NAMES)
        task.includeTransitives.set(true)
        wireTaskProperties(task)

        def updatedLock = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly(
                '''\
                    "test.example:bar": {
                        "locked": "1.1.0"
                    },
                    "test.example:foo": {
                        "locked": "1.0.1",
                        "transitive": [
                            "test.example:bar",
                            "test.example:qux"
                        ]
                    },
                    "test.example:qux": {
                        "locked": "1.0.0"
                    }'''.stripIndent()
        )

        when:
        task.lock()

        then:
        def actual = new JsonSlurper().parseText(task.dependenciesLock.asFile.get().text)
        def expected = new JsonSlurper().parseText(updatedLock)
        actual == expected
    }
}
