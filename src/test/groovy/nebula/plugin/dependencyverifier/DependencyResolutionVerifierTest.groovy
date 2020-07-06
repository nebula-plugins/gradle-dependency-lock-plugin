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
                implementation 'test.nebula:d' // version is missing
            }
            """.stripIndent()

        when:
        def results = runTasksAndFail(*tasks)

        then:
        results.output.contains('FAILURE')
        results.output.contains('Execution failed for task')
        results.output.contains('> Failed to resolve the following dependencies:')
        results.output.contains("1. Failed to resolve 'test.nebula:c' for project")
        results.output.contains("2. Failed to resolve 'test.nebula:d' for project")
        results.output.contains("The following dependencies are missing a version: test.nebula:c, test.nebula:d")
        results.output.contains("If you have been using a BOM")

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

//        if (tasks != ['build']) {
//            // the `dependencies` task does not normally fail on resolution failures
//            // the `build` task will fail on resolution failures
//            // when a task fails, then the project will not continue to a subsequent task
//            assert results.output.contains("1. Failed to resolve 'not.available:banana-leaf:2.0.0' for project 'sub2'")
//        }

        where:
        tasks                                                         | description
        ['build']                                           | 'resolve dependencies naturally'
        ['dependenciesForAll', '--configuration', 'compileClasspath'] | 'explicitly resolve dependencies'
        ['dependenciesForAll', '--configuration', 'compileClasspath', 'build', 'buildEnvironment'] | 'explicitly resolve as part of task chain'
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
        tasks                                                                                      | description
        ['build']                                                                                  | 'resolve dependencies naturally'
        ['dependenciesForAll', '--configuration', 'compileClasspath']                              | 'explicitly resolve dependencies'
        ['dependenciesForAll', '--configuration', 'compileClasspath', 'build', 'buildEnvironment'] | 'explicitly resolve as part of task chain'
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

    @Unroll
    def 'specify configurations to ignore via property via #setupStyle'() {
        given:
        setupSingleProject()

        if (setupStyle == 'properties file') {
            def file = new File("${projectDir}/gradle.properties")
            file << """
                dependencyResolutionVerifier.configurationsToExclude=specialConfig,otherSpecialConfig
                """.stripIndent()
        }

        buildFile << """
            configurations {
                specialConfig
                otherSpecialConfig
            }
            dependencies {
                specialConfig 'not.available:apricot:1.0.0' // not available
                otherSpecialConfig 'not.available:banana-leaf:2.0.0' // not available
            }
            """.stripIndent()

        when:
        def tasks = ['dependencies']
        if (setupStyle == 'command line') {
            tasks += '-PdependencyResolutionVerifier.configurationsToExclude=specialConfig,otherSpecialConfig'
        }
        def results = runTasks(*tasks)

        then:
        !results.output.contains('> Failed to resolve the following dependencies:')

        where:
        setupStyle << ['command line', 'properties file']
    }

    @Unroll
    def 'displays error once with finalizers - #tasks'() {
        given:
        setupSingleProject()

        buildFile << """
            dependencies {
                implementation 'not.available:a'
            }
            task myFinalizingTask {
                println "Here's a great finalizing message"
            }
            task goodbye {
                println "Goodbye!"
            }
            project.tasks.configureEach { Task task ->
                if (task.name != 'myFinalizingTask' && task.name != 'clean') {
                    task.finalizedBy project.tasks.named('myFinalizingTask')
                }
                if (task.name != 'myFinalizingTask' && task.name != 'goodbye' && task.name != 'clean') {
                    task.finalizedBy project.tasks.named('goodbye')
                }
            }
            """.stripIndent()

        when:
        def results = runTasksAndFail(*tasks)

        then:
        results.output.contains('FAILURE')
        results.output.contains('Execution failed for task')
        results.output.findAll("> Failed to resolve the following dependencies:\n" +
                "    1. Failed to resolve 'not.available:a' for project").size() == 1

        where:
        tasks << [['build'], ['build', '--parallel']]
    }

    @Unroll
    def 'with Gradle version #gradleVersionToTest - expecting #expecting'() {
        given:
        gradleVersion = gradleVersionToTest
        setupSingleProject()

        if (expecting == 'error') {
            buildFile << """
                dependencies {
                    implementation 'not.available:a:1.0.0' // dependency is not found
                }
                """.stripIndent()
        }

        when:
        def results
        def tasks = ['dependencies']

        if (expecting == 'error') {
            results = runTasksAndFail(*tasks)
        } else {
            results = runTasks(*tasks)
        }

        then:
        results.output.contains('Task :dependencies')

        where:
        gradleVersionToTest | expecting
        '6.0.1'             | 'error'
        '6.0.1'             | 'no error'
        '5.6.4'             | 'error'
        '5.6.4'             | 'no error'
        '5.1'               | 'error'
        '5.1'               | 'no error'
        '4.10.3'            | 'error'
        '4.10.3'            | 'no error'
        '4.9'               | 'error'
        '4.9'               | 'no error'
    }

    @Unroll
    def 'handles task configuration issue due to #failureType'() {
        given:
        setupSingleProject()
        setupTaskThatRequiresResolvedConfiguration(buildFile)
        forwardOutput = true

        buildFile << """
            dependencies {
                implementation '$dependency'
            }
            """.stripIndent()

        when:
        def results = runTasksAndFail('build')

        then:
        assert results.output.contains('FAILURE')
        assert results.output.contains('> Failed to resolve the following dependencies:')
        assert results.output.contains("1. Failed to resolve '${actualMissingDep ?: dependency}' for project")

        where:
        failureType                | dependency                       | actualMissingDep
        'missing version'          | 'not.available:a'                | null
        'direct dep not found'     | 'not.available:a:1.0.0'          | null
        'transitive dep not found' | 'has.missing.transitive:a:1.0.0' | 'transitive.not.available:a:1.0.0'
    }

    @Unroll
    def 'handles task configuration issue due to #failureType - multiproject'() {
        given:
        setupMultiProject()

        def sub1BuildFile = new File(projectDir, 'sub1/build.gradle')
        def sub2BuildFile = new File(projectDir, 'sub2/build.gradle')

        setupTaskThatRequiresResolvedConfiguration(sub1BuildFile)
        setupTaskThatRequiresResolvedConfiguration(sub2BuildFile)

        sub1BuildFile << """ \
        dependencies {
            implementation '$dependency'
        }
        """.stripIndent()

        def sub2Dependency = dependency.replace(':a', ':b')
        sub2BuildFile << """
        dependencies {
            implementation '$sub2Dependency'
        }
        """.stripIndent()

        when:
        def results = runTasksAndFail('build')

        then:
        assert results.output.contains('FAILURE')
        assert results.output.contains('> Failed to resolve the following dependencies:')
        assert results.output.contains("1. Failed to resolve '${actualMissingDep ?: dependency}' for project 'sub1'")
        assert !results.output.contains("for project 'sub2'")

        where:
        failureType                | dependency                       | actualMissingDep
        'missing version'          | 'not.available:a'                | null
        'direct dep not found'     | 'not.available:a:1.0.0'          | null
        'transitive dep not found' | 'has.missing.transitive:a:1.0.0' | 'transitive.not.available:a:1.0.0'
    }

    @Unroll
    def 'handles task configuration issue due to #failureType - multiproject and parallel'() {
        given:
        setupMultiProject()

        def sub1BuildFile = new File(projectDir, 'sub1/build.gradle')
        def sub2BuildFile = new File(projectDir, 'sub2/build.gradle')

        setupTaskThatRequiresResolvedConfiguration(sub1BuildFile)
        setupTaskThatRequiresResolvedConfiguration(sub2BuildFile)

        sub1BuildFile << """
        dependencies {
            implementation '$dependency'
        }
        """.stripIndent()

        def sub2Dependency = dependency.replace(':a', ':b')
        sub2BuildFile << """
        dependencies {
            implementation '$sub2Dependency'
        }
        """.stripIndent()

        when:
        def results = runTasksAndFail('build')

        then:
        assert results.output.contains('FAILURE')
        assert results.output.contains('> Failed to resolve the following dependencies:')
        assert results.output.contains("1. Failed to resolve '${actualMissingDep ?: dependency}' for project 'sub1'")
        assert !results.output.contains("for project 'sub2'")

        where:
        failureType                | dependency                       | actualMissingDep
        'missing version'          | 'not.available:a'                | null
        'direct dep not found'     | 'not.available:a:1.0.0'          | null
        'transitive dep not found' | 'has.missing.transitive:a:1.0.0' | 'transitive.not.available:a:1.0.0'
    }

    @Unroll
    def 'uses extension for #description'() {
        given:
        setupSingleProject()
        forwardOutput = true

        def configurationName = description == 'configurationsToExclude'
                ? 'myConfig' : 'implementation'

        buildFile << """
            configurations { myConfig }
            dependencies {
                $configurationName 'not.available:a'
            }
            import nebula.plugin.dependencyverifier.DependencyResolutionVerifierExtension
            plugins.withId('nebula.dependency-lock') {
                def extension = extensions.getByType(DependencyResolutionVerifierExtension.class)
                def list = new ArrayList<>()
                $extensionSetting
            }
            """.stripIndent()

        when:
        def results
        if (willFail) {
            results = runTasksAndFail('dependencies')
        } else {
            results = runTasks('dependencies')
        }

        then:
        if (seeErrors) {
            assert results.output.contains('Failed to resolve the following dependencies:')
            assert results.output.contains("1. Failed to resolve 'not.available:a' for project")
        } else {
            assert !results.output.contains('Failed to resolve the following dependencies:')
        }

        where:
        extensionSetting                                                                  | description                      | willFail | seeErrors
        'extension.missingVersionsMessageAddition = "You can find additional help at..."' | 'missingVersionsMessageAddition' | true     | true
        'extension.shouldFailTheBuild = false'                                            | 'shouldFailTheBuild'             | false    | true
        "list.addAll('myConfig')\n\textension.configurationsToExclude = list"             | 'configurationsToExclude'        | false    | false
        "list.addAll('dependencies')\n\textension.tasksToExclude = list"                  | 'tasksToExclude'                 | false    | false
    }

    private static void setupTaskThatRequiresResolvedConfiguration(File specificBuildFile) {
        assert specificBuildFile != null

        specificBuildFile << """
            class MyTask extends DefaultTask {
                @Input
                List<String> dependenciesAsStrings = new ArrayList<>()
                public List<String> dependenciesAsStrings() {
                    return this.dependenciesAsStrings
                }
            }
            TaskProvider<MyTask> myTask = tasks.register('myTask', MyTask)
            myTask.configure { task ->
                doLast {
                    def resolvedArtifacts = project.configurations.compileClasspath.resolvedConfiguration.resolvedArtifacts.collect { it.toString() }
                    task.dependenciesAsStrings = resolvedArtifacts
                    
                    println("Resolved artifacts: \${resolvedArtifacts.join(', ')}")
                }
            }
            project.tasks.named('build').get().dependsOn(myTask)
            """.stripIndent()
    }

    private void setupSingleProject() {
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

    private void setupMultiProject() {
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
