/**
 *
 *  Copyright 2026 Netflix, Inc.
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

package nebula.plugin.dependencyverifier

import nebula.plugin.dependencylock.AbstractDependencyLockPluginSpec
import spock.lang.Subject
import spock.lang.Unroll

@Subject(LockWriteVerification)
class LockWriteVerificationSpec extends AbstractDependencyLockPluginSpec {

    private static final String UNLOCKED_UNRESOLVABLE_CONFIGURATION = """
        configurations {
            customUnlocked {
                canBeConsumed = false
                canBeResolved = true
            }
        }
        dependencies {
            customUnlocked 'not.available:a:1.0.0'
        }
        """.stripIndent()

    private static final String POLICY_TASK = """
        def policyLocked = nebula.plugin.dependencyverifier.LockWriteVerification.lockedConfigurationsToVerify(project)
        def policyFail = nebula.plugin.dependencyverifier.LockWriteVerification.shouldFailTheBuild(project)
        def policyWriting = nebula.plugin.dependencyverifier.LockWriteVerification.isWritingOrUpdatingLocks(project)
        tasks.register('printLockWritePolicy') {
            doLast {
                println "WRITING=\$policyWriting"
                println "LOCKED=\${policyLocked.get()}"
                println "FAIL=\${policyFail.get()}"
            }
        }
        """.stripIndent()

    private static final String ALL_LOCKED = 'LOCKED=[annotationProcessor, compileClasspath, runtimeClasspath, ' +
            'testAnnotationProcessor, testCompileClasspath, testRuntimeClasspath]'

    @Unroll
    def 'dependencies #lockArgs ignores an unresolvable configuration that is not locked'() {
        given:
        runTasks('dependencies', '--write-locks')
        buildFile << UNLOCKED_UNRESOLVABLE_CONFIGURATION

        when:
        def result = runTasks('dependencies', *lockArgs)

        then:
        !result.output.contains('Failed to resolve the following dependencies')
        new File(projectDir, 'gradle.lockfile').text.contains('test.nebula:a:1.1.0=compileClasspath')
        !new File(projectDir, 'gradle.lockfile').text.contains('customUnlocked')

        where:
        lockArgs << [['--write-locks'], ['--update-locks', 'test.nebula:a']]
    }

    def 'normal builds still verify configurations that are not locked'() {
        given:
        runTasks('dependencies', '--write-locks')
        buildFile << UNLOCKED_UNRESOLVABLE_CONFIGURATION

        when:
        def result = runTasksAndFail('dependencies')

        then:
        result.output.contains('Failed to resolve the following dependencies')
        result.output.contains('not.available:a:1.0.0')
    }

    @Unroll
    def 'dependencies #lockArgs fails when a locked configuration cannot be resolved'() {
        given:
        runTasks('dependencies', '--write-locks')
        buildFile << """
            dependencies {
                implementation 'not.available:a:1.0.0'
            }
            """.stripIndent()

        when:
        def result = runTasksAndFail('dependencies', *lockArgs)

        then:
        result.output.contains('Failed to resolve the following dependencies')
        result.output.contains('not.available:a:1.0.0')

        where:
        lockArgs << [['--write-locks'], ['--update-locks', 'not.available:a']]
    }

    def 'lock writes skip excluded locked configurations'() {
        given:
        buildFile << """
            dependencies {
                testRuntimeOnly 'not.available:a:1.0.0'
            }
            """.stripIndent()

        when:
        def result = runTasks('dependencies', '--write-locks',
                '-PdependencyResolutionVerifier.configurationsToExclude=testRuntimeClasspath')

        then:
        !result.output.contains('Failed to resolve the following dependencies')
    }

    def 'policy lists the locked configurations and defaults to failing the build'() {
        given:
        buildFile << UNLOCKED_UNRESOLVABLE_CONFIGURATION
        buildFile << POLICY_TASK

        when:
        def result = runTasks('printLockWritePolicy', '--write-locks', '--no-configuration-cache')

        then:
        result.output.contains('WRITING=true')
        result.output.contains(ALL_LOCKED)
        result.output.contains('FAIL=true')
    }

    def 'policy reports that locks are not being written'() {
        given:
        buildFile << POLICY_TASK

        when:
        def result = runTasks('printLockWritePolicy', '--no-configuration-cache')

        then:
        result.output.contains('WRITING=false')
    }

    def 'policy reads the verifier extension'() {
        given:
        buildFile << """
            dependencyResolutionVerifierExtension {
                configurationsToExclude = ['compileClasspath']
                shouldFailTheBuild = false
            }
            """.stripIndent()
        buildFile << POLICY_TASK

        when:
        def result = runTasks('printLockWritePolicy', '--write-locks', '--no-configuration-cache')

        then:
        result.output.contains('LOCKED=[annotationProcessor, runtimeClasspath, testAnnotationProcessor, ' +
                'testCompileClasspath, testRuntimeClasspath]')
        result.output.contains('FAIL=false')
    }

    def 'policy prefers properties over the verifier extension'() {
        given:
        buildFile << """
            dependencyResolutionVerifierExtension {
                configurationsToExclude = ['compileClasspath']
                shouldFailTheBuild = true
            }
            """.stripIndent()
        buildFile << POLICY_TASK

        when:
        def result = runTasks('printLockWritePolicy', '--write-locks', '--no-configuration-cache',
                '-PdependencyResolutionVerifier.configurationsToExclude=testRuntimeClasspath, runtimeClasspath',
                '-PdependencyResolutionVerifier.unresolvedDependenciesFailTheBuild=false')

        then:
        result.output.contains('LOCKED=[annotationProcessor, compileClasspath, testAnnotationProcessor, ' +
                'testCompileClasspath]')
        result.output.contains('FAIL=false')
    }
}
