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
package nebula.plugin.dependencylock

import nebula.test.IntegrationSpec

class DependencyLockLauncherSpec extends IntegrationSpec {
    static final String SPECIFIC_BUILD_GRADLE = '''\
        apply plugin: 'java'
        apply plugin: 'gradle-dependency-lock'
        repositories { mavenCentral() }
        dependencies {
            compile 'com.google.guava:guava:14.0.1'
        }
    '''.stripIndent()

    static final String BUILD_GRADLE = '''\
        apply plugin: 'java'
        apply plugin: 'gradle-dependency-lock'
        repositories { mavenCentral() }
        dependencies {
            compile 'com.google.guava:guava:14.+'
        }
    '''.stripIndent()

    static final String OLD_GUAVA_LOCK = '''\
        {
          "com.google.guava:guava": { "locked": "14.0", "requested": "14.+" }
        }
    '''.stripIndent()

    static final String GUAVA_LOCK = '''\
        {
          "com.google.guava:guava": { "locked": "14.0.1", "requested": "14.+" }
        }
    '''.stripIndent()

    def 'plugin allows normal gradle operation'() {
        buildFile << SPECIFIC_BUILD_GRADLE

        when:
        runTasksSuccessfully('build')

        then:
        noExceptionThrown()
    }

    def 'lock file is applied'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_GUAVA_LOCK

        buildFile << SPECIFIC_BUILD_GRADLE

        when:
        runTasksSuccessfully('dependencies')

        then:
        standardOutput.contains 'com.google.guava:guava:14.0.1 -> 14.0'
    }

    def 'create lock'() {
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('lockDependencies')

        then:
        new File(projectDir, 'dependencies.lock').text == GUAVA_LOCK
    }

    def 'update lock'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_GUAVA_LOCK
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('lockDependencies')

        then:
        new File(projectDir, 'dependencies.lock').text == GUAVA_LOCK
    }
}
