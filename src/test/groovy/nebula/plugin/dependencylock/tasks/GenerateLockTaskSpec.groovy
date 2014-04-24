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

import nebula.test.ProjectSpec
import org.gradle.testfixtures.ProjectBuilder

class GenerateLockTaskSpec extends ProjectSpec {
    final String taskName = 'generateLock'

    def 'simple lock'() {
        project.apply plugin: 'java'

        project.repositories { mavenCentral() }
        project.dependencies {
            compile 'com.google.guava:guava:14.+'
            testCompile 'junit:junit:4.+'
        }

        GenerateLockTask task = project.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "com.google.guava:guava": { "locked": "14.0.1", "requested": "14.+" },
              "junit:junit": { "locked": "4.11", "requested": "4.+" }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
    }

    def 'multiproject inter-project dependencies should be excluded'() {
        def common = ProjectBuilder.builder().withName('common').withProjectDir(new File(projectDir, 'common')).withParent(project).build()
        project.subprojects.add(common)
        def app = ProjectBuilder.builder().withName('app').withProjectDir(new File(projectDir, 'app')).withParent(project).build()
        project.subprojects.add(app)

        project.subprojects {
            repositories { mavenCentral() }
        }

        common.apply plugin: 'java'
        app.apply plugin: 'java'
        app.dependencies {
            compile app.project(':common')
            compile 'com.google.guava:guava:14.+'
        }

        GenerateLockTask task = app.tasks.create(taskName, GenerateLockTask)
        task.dependenciesLock = new File(app.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "com.google.guava:guava": { "locked": "14.0.1", "requested": "14.+" }
            }
        '''.stripIndent()
        task.dependenciesLock.text == lockText
    }
}
