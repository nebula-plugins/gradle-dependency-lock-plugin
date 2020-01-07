/**
 *
 *  Copyright 2019 Netflix, Inc.
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

import nebula.test.IntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder
import spock.lang.Subject
import spock.lang.Unroll

@Subject(DependencyResolutionVerifier)
class DependencyResolutionVerifierTest extends IntegrationTestKitSpec {
    def mavenrepo

    def setup() {
        keepFiles = true
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:a:1.0.0')
                .addModule('test.nebula:b:1.0.0')
                .addModule('test.nebula:c:1.1.0')
                .addModule(new ModuleBuilder('has.missing.transitive:a:1.0.0').addDependency('transitive.not.available:a:1.0.0').build())
                .build()

        mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()

        def transitiveNotAvailableDep = new File(mavenrepo.getMavenRepoDir(), "transitive/not/available/a")
        transitiveNotAvailableDep.deleteDir() // to create a missing transitive dependency

        debug = true // if you want to debug with IntegrationTestKit, this is needed
    }

    @Unroll
    def 'no verification errors - #description'() {
        given:
        setupSingleProject()

        when:
        def results = runTasks(*tasks)

        then:
        !results.output.contains('FAILURE')

        where:
        tasks            | description
        ['dependencies'] | 'explicitly resolve dependencies'
    }

    @Unroll
    def 'error displayed when direct dependency is missing - #description'() {
        given:
        setupSingleProject()

        buildFile << """
            dependencies {
                implementation 'not.available:a:1.0.0' // dependency is not found
            }
            """.stripIndent()

        when:
        def results = runTasksAndFail(*tasks)

        then:
        results.output.contains('FAILURE')
        results.output.contains('Execution failed for task')
        results.output.contains('> Failed to resolve the following dependencies:')
        results.output.contains("1. Failed to resolve 'not.available:a:1.0.0' for project")

        where:
        tasks                                         | description
        ['build']                                     | 'resolve dependencies naturally'
        ['dependencies']                              | 'explicitly resolve dependencies'
        ['dependencies', 'build', 'buildEnvironment'] | 'explicitly resolve as part of task chain'
    }

    @Unroll
    def 'error displayed when transitive dependency is missing - #description'() {
        given:
        setupSingleProject()

        buildFile << """
            dependencies {
                implementation 'has.missing.transitive:a:1.0.0' // transitive dependency is missing
            }
            """.stripIndent()

        when:
        def results = runTasksAndFail(*tasks)

        then:
        results.output.contains('FAILURE')
        results.output.contains('Execution failed for task')
        results.output.contains('> Failed to resolve the following dependencies:')
        results.output.contains("1. Failed to resolve 'transitive.not.available:a:1.0.0' for project")

        where:
        tasks                                         | description
        ['build']                                     | 'resolve dependencies naturally'
        ['dependencies']                              | 'explicitly resolve dependencies'
        ['dependencies', 'build', 'buildEnvironment'] | 'explicitly resolve as part of task chain'
    }

    @Unroll
    def 'error displayed when version is missing - #description'() {
        given:
        setupSingleProject()

        buildFile << """
            dependencies {
                implementation 'test.nebula:c' // version is missing
            }
            """.stripIndent()

        when:
        def results = runTasksAndFail(*tasks)

        then:
        results.output.contains('FAILURE')
        results.output.contains('Execution failed for task')
        results.output.contains('> Failed to resolve the following dependencies:')
        results.output.contains("1. Failed to resolve 'test.nebula:c' for project")

        where:
        tasks                                         | description
        ['build']                                     | 'resolve dependencies naturally'
        ['dependencies']                              | 'explicitly resolve dependencies'
        ['dependencies', 'build', 'buildEnvironment'] | 'explicitly resolve as part of task chain'
    }

    @Unroll
    def 'error displayed when a dependency is not found and needed at compilation - #description'() {
        given:
        setupSingleProject()
        buildFile << """
            dependencies {
                testImplementation 'junit:junit:999.99.9' // version is invalid yet needed for compilation
            }
            """.stripIndent()
        writeUnitTest() // valid version of the junit library is not in the dependency declaration

        when:
        def results = runTasksAndFail(*tasks)

        then:
        results.output.contains('FAILURE')
        results.output.contains('Execution failed for task')
        results.output.contains('> Failed to resolve the following dependencies:')
        results.output.contains("1. Failed to resolve 'junit:junit:999.99.9' for project")

        where:
        tasks                                         | description
        ['build']                                     | 'resolve dependencies naturally'
        ['dependencies']                              | 'explicitly resolve dependencies'
        ['dependencies', 'build', 'buildEnvironment'] | 'explicitly resolve as part of task chain'
    }

    @Unroll
    def 'multiproject: missing direct dependencies - #description'() {
        given:
        setupMultiProject()

        new File(projectDir, 'sub1/build.gradle') << """ \
        dependencies {
            implementation 'not.available:apricot:1.0.0' // dependency is not found
        }
        """.stripIndent()

        new File(projectDir, 'sub2/build.gradle') << """ \
        dependencies {
            implementation 'not.available:banana-leaf:2.0.0' // dependency is not found
        }
        """.stripIndent()

        when:
        def results = runTasksAndFail(*tasks)

        then:
        results.output.contains('FAILURE')
        results.output.contains('Execution failed for task')
        results.output.contains('> Failed to resolve the following dependencies:')
        results.output.contains("1. Failed to resolve 'not.available:apricot:1.0.0' for project 'sub1'")

        if (tasks != ['build']) {
            // the `dependencies` task does not normally fail on resolution failures
            // the `build` task will fail on resolution failures
            // when a task fails, then the project will not continue to a subsequent task
            results.output.contains("1. Failed to resolve 'not.available:banana-leaf:2.0.0' for project 'sub2'")
        }

        where:
        tasks                                               | description
        ['build']                                           | 'resolve dependencies naturally'
        ['dependenciesForAll']                              | 'explicitly resolve dependencies'
        ['dependenciesForAll', 'build', 'buildEnvironment'] | 'explicitly resolve as part of task chain'
    }

    @Unroll
    def 'multiproject: works for parallel builds - #description'() {
        given:
        setupMultiProject()

        new File(projectDir, 'sub1/build.gradle') << """ \
        dependencies {
            implementation 'not.available:apricot:1.0.0' // dependency is not found
        }
        """.stripIndent()

        new File(projectDir, 'sub2/build.gradle') << """ \
        dependencies {
            implementation 'not.available:banana-leaf:2.0.0' // dependency is not found
        }
        """.stripIndent()

        when:
        def results = runTasksAndFail(*tasks, '--parallel')

        then:
        results.output.contains('FAILURE')
        results.output.contains('Execution failed for task')
        results.output.contains('> Failed to resolve the following dependencies:')
        results.output.contains("1. Failed to resolve 'not.available:apricot:1.0.0' for project 'sub1'")
        results.output.contains("1. Failed to resolve 'not.available:banana-leaf:2.0.0' for project 'sub2'")

        where:
        tasks                                               | description
        ['build']                                           | 'resolve dependencies naturally'
        ['dependenciesForAll']                              | 'explicitly resolve dependencies'
        ['dependenciesForAll', 'build', 'buildEnvironment'] | 'explicitly resolve as part of task chain'
    }

    @Unroll
    def 'missing a dependency declaration is not caught - #description'() {
        given:
        setupSingleProject()
        writeUnitTest() // junit library is not in the dependency declaration

        when:
        def results = runTasksAndFail(*tasks)

        then:
        results.output.contains('FAILURE')
        !results.output.contains('> Failed to resolve the following dependencies:')

        where:
        tasks                                         | description
        ['build']                                     | 'resolve dependencies naturally'
        ['dependencies', 'build', 'buildEnvironment'] | 'explicitly resolve as part of task chain'
    }

    def setupSingleProject() {
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java-library'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                implementation 'test.nebula:b:1.+'
            }
        """.stripIndent()

        writeHelloWorld()
    }

    def setupMultiProject() {
        buildFile.delete()
        buildFile.createNewFile()
        buildFile << """
            plugins {
                id 'java'
            }
            subprojects {
                task dependenciesForAll(type: DependencyReportTask) {}
            }
            """.stripIndent()

        def subProjectBuildFileContent = """
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                implementation 'test.nebula:b:1.+'
            }
            """.stripIndent()

        addSubproject("sub1", subProjectBuildFileContent)
        addSubproject("sub2", subProjectBuildFileContent)

        writeHelloWorld(new File(projectDir, 'sub1'))
        writeHelloWorld(new File(projectDir, 'sub2'))
        writeUnitTest(new File(projectDir, 'sub1'))
        writeUnitTest(new File(projectDir, 'sub2'))
    }

}
