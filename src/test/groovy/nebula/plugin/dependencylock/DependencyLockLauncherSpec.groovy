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

import nebula.plugin.dependencylock.dependencyfixture.Fixture
import nebula.test.IntegrationSpec
import org.gradle.BuildResult

class DependencyLockLauncherSpec extends IntegrationSpec {
    static final String SPECIFIC_BUILD_GRADLE = """\
        apply plugin: 'java'
        apply plugin: 'gradle-dependency-lock'
        repositories { maven { url '${Fixture.repo}' } }
        dependencies {
            compile 'test.example:foo:1.0.1'
        }
    """.stripIndent()

    static final String BUILD_GRADLE = """\
        apply plugin: 'java'
        apply plugin: 'gradle-dependency-lock'
        repositories { maven { url '${Fixture.repo}' } }
        dependencies {
            compile 'test.example:foo:1.+'
        }
    """.stripIndent()

    static final String NEW_BUILD_GRADLE = """\
        apply plugin: 'java'
        apply plugin: 'gradle-dependency-lock'
        repositories { maven { url '${Fixture.repo}' } }
        dependencies {
            compile 'test.example:foo:2.+'
        }
    """.stripIndent()

    static final String OLD_GUAVA_LOCK = '''\
        {
          "test.example:foo": { "locked": "1.0.0", "requested": "1.+" }
        }
    '''.stripIndent()

    static final String GUAVA_LOCK = '''\
        {
          "test.example:foo": { "locked": "1.0.1", "requested": "1.+" }
        }
    '''.stripIndent()

    static final String NEW_GUAVA_LOCK = '''\
        {
          "test.example:foo": { "locked": "2.0.1", "requested": "1.+", "viaOverride": "2.0.1" }
        }
    '''.stripIndent()

    def setupSpec() {
        Fixture.createFixtureIfNotCreated()
    }

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
        standardOutput.contains 'test.example:foo:1.0.1 -> 1.0.0'
    }

    def 'override lock file is applied'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_GUAVA_LOCK

        def testOverride = new File(projectDir, 'test.override')
        testOverride << GUAVA_LOCK

        def gradleProperties = new File(projectDir, 'gradle.properties')
        gradleProperties << 'dependencyLock.lockFile = \'test.override\''

        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('dependencies')

        then:
        standardOutput.contains 'test.example:foo:1.+ -> 1.0.1'
    }

    def 'create lock'() {
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('generateLock')

        then:
        new File(projectDir, 'build/dependencies.lock').text == GUAVA_LOCK
    }

    def 'update lock'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_GUAVA_LOCK
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == GUAVA_LOCK
    }

    def 'trigger failure with bad lock file'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << '''\
            {
              key: {}
            }
        '''.stripIndent()
        buildFile << BUILD_GRADLE

        when:
        BuildResult result = runTasksWithFailure('build')

        then:
        result.failure.message.contains('unreadable or invalid json')
    }

    def 'existing lock ignored while updating lock'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << NEW_GUAVA_LOCK
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == GUAVA_LOCK    
    }

    def 'command line override respected while updating lock'() {
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('-PdependencyLock.override=test.example:foo:2.0.1', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == NEW_GUAVA_LOCK    
    }

    def 'command line override file respected while updating lock'() {
        def testLock = new File(projectDir, 'test.lock')
        testLock << NEW_GUAVA_LOCK
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('-PdependencyLock.overrideFile=test.lock', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == NEW_GUAVA_LOCK    
    }

    def 'multiple runs each generate a lock'() {
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('saveLock')

        then:
        def savedLock = new File(projectDir, 'dependencies.lock')
        savedLock.text == GUAVA_LOCK

        buildFile << NEW_BUILD_GRADLE

        when:
        runTasksSuccessfully('generateLock')

        then:
        new File(projectDir, 'build/dependencies.lock').text != savedLock.text
    }

    def 'multiple runs of save lock works'() {
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('saveLock')

        then:
        def savedLock = new File(projectDir, 'dependencies.lock')
        def firstRun = savedLock.text
        firstRun == GUAVA_LOCK

        buildFile << NEW_BUILD_GRADLE

        when:
        runTasksSuccessfully('saveLock')

        then:
        savedLock.text != firstRun
    }
}
