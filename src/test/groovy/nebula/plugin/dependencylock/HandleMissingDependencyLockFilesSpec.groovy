/*
 * Copyright 2014-2021 Netflix, Inc.
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


import nebula.test.IntegrationTestKitSpec
import spock.lang.Subject
import spock.lang.Unroll

@Subject(DependencyLockPlugin)
class HandleMissingDependencyLockFilesSpec extends IntegrationTestKitSpec {
    static def LOCKFILE_STATUS_PROPERTY = "nebula.features.dependencyLock.lockfileStatus"
    static def MISSING_LOCKFILES_MESSAGE = "It is important to lock your project dependencies."

    def setup() {
        buildFile << """
            plugins {
                id 'nebula.dependency-lock'
                id 'java-library'
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation 'com.google.guava:guava:19.0'
            }
            """.stripIndent()
    }

    @Unroll
    def 'by default, do not require lock files - task(s) #tasks'() {
        when:
        def result = runTasks(*tasks)

        then:
        !result.output.contains(MISSING_LOCKFILES_MESSAGE)

        where:
        tasks << [['clean'], ['build']]
    }

    @Unroll
    def 'warn users to lock their dependencies for task(s) as configured in #configuredIn'() {
        given:
        buildFile << """
            dependencyLock {
                $extensionConfig
            }
            """.stripIndent()

        def propertiesFile = new File(projectDir, "gradle.properties")
        propertiesFile << propertyConfig

        when:
        def result = runTasks(task)

        then:
        result.output.contains(MISSING_LOCKFILES_MESSAGE)

        where:
        task    | configuredIn | extensionConfig                                   | propertyConfig
        'build' | "extension"  | "lockfileStatus = 'REQUIRE_LOCKFILE_VIA_WARNING'" | ""
        'build' | "property"   | ""                                                | "$LOCKFILE_STATUS_PROPERTY=requireLockfileViaWarning"
    }

    @Unroll
    def 'does not warn when lockfiles exist - #type'() {
        given:
        buildFile << """
            dependencyLock {
                lockfileStatus = 'REQUIRE_LOCKFILE_VIA_WARNING'
            }
            """.stripIndent()

        if (lockingTasks.contains('--write-locks')) {
            def propertiesFile = new File(projectDir, "gradle.properties")
            propertiesFile << "systemProp.nebula.features.coreLockingSupport=true"
        }

        when:
        runTasks(*lockingTasks)
        def result = runTasks('build')

        then:
        !result.output.contains(MISSING_LOCKFILES_MESSAGE)

        where:
        type                 | lockingTasks
        'nebula lock'        | ['generateLock', 'saveLock']
        'nebula global lock' | ['generateGlobalLock', 'saveGlobalLock']
        'gradle lock'        | ['dependencies', '--write-locks']
    }

    def 'configure message'() {
        given:
        def message = 'It is important to lock your project dependencies. See more information at <abc>'
        buildFile << """
            dependencyLock {
                lockfileStatus = 'REQUIRE_LOCKFILE_VIA_WARNING'
                missingLockfileMessage = '$message'
            }
            """.stripIndent()

        when:
        def result = runTasks('build')

        then:
        result.output.contains(MISSING_LOCKFILES_MESSAGE)
        result.output.contains(message)
    }

    @Unroll
    def 'fail task(s) as configured in #configuredIn'() {
        given:
        buildFile << """
            dependencyLock {
                $extensionConfig
            }
            """.stripIndent()

        def propertiesFile = new File(projectDir, "gradle.properties")
        propertiesFile << propertyConfig

        when:
        def result = runTasksAndFail(task)

        then:
        result.output.contains(MISSING_LOCKFILES_MESSAGE)

        where:
        task    | configuredIn | extensionConfig                                   | propertyConfig
        'build' | "extension"  | "lockfileStatus = 'REQUIRE_LOCKFILE_VIA_FAILURE'" | ""
        'build' | "property"   | ""                                                | "$LOCKFILE_STATUS_PROPERTY=requireLockfileViaFailure"
    }

    @Unroll
    def 'do not warn users to lock their dependencies for task(s) #tasks'() {
        given:
        buildFile << """
            dependencyLock {
                lockfileStatus = 'REQUIRE_LOCKFILE_VIA_WARNING'
           }
            """.stripIndent()

        if (tasks.contains('--write-locks')) {
            def propertiesFile = new File(projectDir, "gradle.properties")
            propertiesFile << "systemProp.nebula.features.coreLockingSupport=true"
        }

        when:
        def result = runTasks(*tasks)

        then:
        !result.output.contains(MISSING_LOCKFILES_MESSAGE)

        where:
        tasks                             | reason
        []                                | 'project configuration only'
        ['clean']                         | 'clean task alone'
        ['generateLock', 'saveLock']      | 'actively locking dependencies now'
        ['dependencies', '--write-locks'] | 'actively locking dependencies now'
    }

    @Unroll
    def 'property configuration takes priority over extension configuration - configured to #status'() {
        given:
        buildFile << """
            dependencyLock {
                $extensionConfig
            }
            """.stripIndent()

        def propertiesFile = new File(projectDir, "gradle.properties")
        propertiesFile << propertyConfig

        when:
        def result = runTasks(task)

        then:
        if (propertyConfig.contains("notEnforced")) {
            assert !result.output.contains(MISSING_LOCKFILES_MESSAGE)
        } else {
            assert result.output.contains(MISSING_LOCKFILES_MESSAGE)
        }

        where:
        task    | status                      | extensionConfig                                   | propertyConfig
        'build' | 'notEnforced'               | "lockfileStatus = 'REQUIRE_LOCKFILE_VIA_WARNING'" | "$LOCKFILE_STATUS_PROPERTY=notEnforced"
        'build' | 'requireLockfileViaWarning' | "lockfileStatus = 'REQUIRE_LOCKFILE_VIA_FAILURE'" | "$LOCKFILE_STATUS_PROPERTY=requireLockfileViaWarning"
    }
}
