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

import groovy.json.JsonSlurper
import nebula.test.ProjectSpec

class GenerateLockTaskSpec extends ProjectSpec {
    final String taskName = 'generateLock'

    def 'simple lock'() {
        project.apply plugin: 'java'

        project.repositories { mavenCentral() }
        project.dependencies {
            compile 'com.google.guava:guava:14.+'
            testCompile 'junit:junit:4.+'
        }

        GenerateLockTask task = project.tasks.create('lockTestTask', GenerateLockTask)
        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]

        when:
        task.execute()

        then:
        new JsonSlurper().parseText(task.dependenciesLock.text) == new JsonSlurper().parseText('''
            {
              "com.google.guava:guava": { "locked": "14.0.1" },
              "junit:junit": { "locked": "4.11" },
              "org.hamcrest:hamcrest-core": { "locked": "1.3" }
            }
        ''')
    }
}
