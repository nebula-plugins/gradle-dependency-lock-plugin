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

import nebula.plugin.BaseIntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder
import spock.lang.Ignore
import spock.lang.Subject
import spock.lang.Unroll

@Subject(DependencyResolutionVerifierKt)
class DependencyResolutionVerifierTest extends BaseIntegrationTestKitSpec {
    def mavenrepo

    def setup() {
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
        disableConfigurationCache() // DependencyResolutionVerifier does not support config cache
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
        tasks                                                   | description
        ['dependencies', '--configuration', 'compileClasspath'] | 'explicitly resolve dependencies'
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
        assertResolutionFailureMessage(results.output)
        assertResolutionFailureForDependency(results.output, "not.available:a:1.0.0")

        where:
        tasks                                                                                | description
        ['build']                                                                            | 'resolve dependencies naturally'
        ['dependencies', '--configuration', 'compileClasspath']                              | 'explicitly resolve dependencies'
        ['dependencies', '--configuration', 'compileClasspath', 'build', 'buildEnvironment'] | 'explicitly resolve as part of task chain'
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
        assertResolutionFailureMessage(results.output)
        assertResolutionFailureForDependency(results.output, "transitive.not.available:a:1.0.0")

        where:
        tasks                                                                                | description
        ['build']                                                                            | 'resolve dependencies naturally'
        ['dependencies', '--configuration', 'compileClasspath']                              | 'explicitly resolve dependencies'
        ['dependencies', '--configuration', 'compileClasspath', 'build', 'buildEnvironment'] | 'explicitly resolve as part of task chain'
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
        assertResolutionFailureMessage(results.output)
        assertResolutionFailureForDependency(results.output, "test.nebula:c")
        assertResolutionFailureForDependency(results.output, "test.nebula:d", 2)
        results.output.contains("The following dependencies are missing a version: test.nebula:c, test.nebula:d")
        results.output.contains("If you have been using a BOM")

        where:
        tasks                                                                                | description
        ['build']                                                                            | 'resolve dependencies naturally'
        ['dependencies', '--configuration', 'compileClasspath']                              | 'explicitly resolve dependencies'
        ['dependencies', '--configuration', 'compileClasspath', 'build', 'buildEnvironment'] | 'explicitly resolve as part of task chain'
    }

    @Unroll
    def 'error displayed when a dependency is not found and needed at compilation - #description'() {
        given:
        setupSingleProject()
        buildFile << """
            dependencies {
                implementation 'junit:junit:999.99.9' // version is invalid yet needed for compilation
            }
            """.stripIndent()
        writeUnitTest() // valid version of the junit library is not in the dependency declaration

        when:
        def results = runTasksAndFail(*tasks)

        then:
        results.output.contains('FAILURE')
        results.output.contains('Execution failed for task')
        assertResolutionFailureMessage(results.output)
        assertResolutionFailureForDependency(results.output, "junit:junit:999.99.9")

        where:
        tasks                                                                                | description
        ['build']                                                                            | 'resolve dependencies naturally'
        ['dependencies', '--configuration', 'compileClasspath']                              | 'explicitly resolve dependencies'
        ['dependencies', '--configuration', 'compileClasspath', 'build', 'buildEnvironment'] | 'explicitly resolve as part of task chain'
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
        assertResolutionFailureMessage(results.output)
        assertResolutionFailureForDependencyForProject(results.output, "not.available:apricot:1.0.0", "sub1")

        where:
        tasks                                                                                      | description
        ['build']                                                                                  | 'resolve dependencies naturally'
        ['dependenciesForAll', '--configuration', 'compileClasspath']                              | 'explicitly resolve dependencies'
        ['dependenciesForAll', '--configuration', 'compileClasspath', 'build', 'buildEnvironment'] | 'explicitly resolve as part of task chain'
    }

    @Unroll
    def 'multiproject: handles worker threads from spotbugs - #description'() {
        given:
        buildFile << """
            buildscript {
                repositories { maven { url = "https://plugins.gradle.org/m2/" } }
                dependencies {
                     classpath "com.github.spotbugs:com.github.spotbugs.gradle.plugin:6.4.8"
                }
            }            
            """.stripIndent()
        setupMultiProject()
        buildFile << """
            subprojects {
                apply plugin: "com.github.spotbugs"
                repositories {
                    mavenCentral()
                }
                configurations.named('spotbugs').configure {
                  resolutionStrategy.eachDependency { DependencyResolveDetails details ->
                       if (details.requested.group == 'org.ow2.asm') {
                            details.useVersion '9.9'
                            details.because "Asm 9.9 is required for JDK 25 support"
                      }
                  }
                }
            }
            """.stripIndent()

        new File(projectDir, 'sub1/build.gradle') << """ \
            dependencies {
                testImplementation 'junit:junit:4.12'
            }
            """.stripIndent()

        new File(projectDir, 'sub2/build.gradle') << """ \
            dependencies {
                testImplementation 'junit:junit:4.12'
            }
            """.stripIndent()

        when:
        def results = runTasks(*tasks, '--warning-mode', 'all')

        then:
        !results.output.contains('FAILURE')
        !results.output.contains('was resolved without accessing the project in a safe manner')

        where:
        tasks                                                         | description
        ['spotbugsMain']                                              | 'calling spotbugsMain'
        ['build']                                                     | 'resolve dependencies naturally'
        ['dependenciesForAll', '--configuration', 'compileClasspath'] | 'explicitly resolve dependencies'
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
        assertResolutionFailureMessage(results.output)
        assertResolutionFailureForDependencyForProject(results.output, "not.available:apricot:1.0.0", "sub1")
        assertResolutionFailureForDependencyForProject(results.output, "not.available:banana-leaf:2.0.0", "sub2")

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
        assertNoResolutionFailureMessage(results.output)

        where:
        tasks                                                                                | description
        ['build']                                                                            | 'resolve dependencies naturally'
        ['dependencies', '--configuration', 'compileClasspath', 'build', 'buildEnvironment'] | 'explicitly resolve as part of task chain'
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
        def tasks = ['dependencies', '--configuration', 'compileClasspath']
        if (setupStyle == 'command line') {
            tasks += '-PdependencyResolutionVerifier.configurationsToExclude=specialConfig,otherSpecialConfig'
        }
        def results = runTasks(*tasks)

        then:
        assertNoResolutionFailureMessage(results.output)

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
        results.output.findAll("Failed to resolve the following dependencies:\n" +
                "  1. Failed to resolve 'not.available:a' for project").size() == 1

        where:
        tasks << [['build'], ['build', '--parallel']]
    }


    @Unroll
    def 'handles task configuration issue due to #failureType'() {
        given:
        setupSingleProject()
        setupTaskThatRequiresResolvedConfiguration(buildFile)

        buildFile << """
            dependencies {
                implementation '$dependency'
            }
            """.stripIndent()

        when:
        def results = runTasksAndFail('build')

        then:
        assert results.output.contains('FAILURE')
        assertResolutionFailureMessage(results.output)
        assertResolutionFailureForDependency(results.output, actualMissingDep ?: dependency)

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
        assertResolutionFailureMessage(results.output)
        assert assertResolutionFailureForDependencyForProject(results.output, actualMissingDep ?: dependency, "sub1")
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
        assertResolutionFailureMessage(results.output)
        assert assertResolutionFailureForDependencyForProject(results.output, actualMissingDep ?: dependency, "sub1")
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

        def configurationName = description == 'configurationsToExclude'
                ? 'myConfig' : 'implementation'

        buildFile << """
            configurations { myConfig }
            dependencies {
                $configurationName 'not.available:a'
            }
            import nebula.plugin.dependencyverifier.DependencyResolutionVerifierExtension
            plugins.withId('com.netflix.nebula.dependency-lock') {
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
            assert assertResolutionFailureMessage(results.output)
            assert assertResolutionFailureForDependency(results.output, "not.available:a")
        } else {
            assert assertNoResolutionFailureMessage(results.output)
        }

        where:
        extensionSetting                                                                  | description                      | willFail | seeErrors
        'extension.missingVersionsMessageAddition = "You can find additional help at..."' | 'missingVersionsMessageAddition' | true     | true
        'extension.shouldFailTheBuild = false'                                            | 'shouldFailTheBuild'             | false    | true
        "list.addAll('myConfig')\n\textension.configurationsToExclude = list"             | 'configurationsToExclude'        | false    | false
        "list.addAll('dependencies')\n\textension.tasksToExclude = list"                  | 'tasksToExclude'                 | false    | false
    }

    def 'handles root and subproject of the same name'() {
        given:
        setupMultiProject()

        def sub1BuildFile = new File(projectDir, 'sub1/build.gradle')
        sub1BuildFile << """
        dependencies {
            implementation 'not.available:a:1.0.0' // dependency is not found
        }
        """.stripIndent()

        settingsFile.createNewFile()
        settingsFile.text = """
            rootProject.name='sub1'
            include "sub1"
            include "sub2"
            """.stripIndent()

        when:
        def results = runTasksAndFail('build')

        then:
        results.output.contains('FAILURE')
        results.output.contains('Execution failed for task')
        assertResolutionFailureMessage(results.output)
        results.output.findAll("Failed to resolve the following dependencies:\n" +
                "  1. Failed to resolve 'not.available:a:1.0.0' for project").size() == 1
        assertResolutionFailureForDependencyForProject(results.output, "not.available:a:1.0.0", "sub1")
    }

    def 'handles build failure from task configuration issue'() {
        given:
        setupSingleProject()
        buildFile << """
            dependencies {
                implementation 'not.available:a:1.0.0' // dependency is not found
            }
            task goodbye {
                println "Goodbye!"
            }
            build.finalizedBy project.tasks.named('goodbye') onlyIf {
                project.configurations.compileClasspath.resolvedConfiguration.resolvedArtifacts.collect {it.name}.contains('bananan')
            }
            """.stripIndent()

        when:
        def results = runTasksAndFail('build')

        then:
        results.output.contains('FAILURE')
        results.output.contains('Execution failed for task')
        assertResolutionFailureMessage(results.output)
        results.output.findAll("Failed to resolve the following dependencies:\n" +
                "  1. Failed to resolve 'not.available:a:1.0.0' for project").size() == 1
        assertResolutionFailureForDependency(results.output, "not.available:a:1.0.0")
    }

    def 'handles task that requires resolved configuration with no issues'() {
        given:
        setupSingleProject()

        buildFile << """
            ${taskThatRequiresConfigurationDependencies()}
            """.stripIndent()

        when:
        def results = runTasks('build')

        then:
        assert !results.output.contains('FAILURE')
    }

    @Unroll
    def 'handles task that requires resolved configuration with an issue due to #failureType'() {
        given:
        setupSingleProject()

        buildFile << """
            dependencies {
                implementation '$dependency'
            }
            ${taskThatRequiresConfigurationDependencies()}
            """.stripIndent()

        when:
        def results = runTasksAndFail('build')

        then:
        assert results.output.contains('FAILURE')
        assert assertResolutionFailureMessage(results.output)
        assert assertResolutionFailureForDependency(results.output, actualMissingDep ?: dependency)

        where:
        failureType                | dependency                       | actualMissingDep
        'missing version'          | 'not.available:a'                | null
        'direct dep not found'     | 'not.available:a:1.0.0'          | null
        'transitive dep not found' | 'has.missing.transitive:a:1.0.0' | 'transitive.not.available:a:1.0.0'
    }

    @Unroll
    def 'handles task that requires resolved configuration with an issue due to #failureType - multiproject'() {
        given:
        setupMultiProject()

        def sub1BuildFile = new File(projectDir, 'sub1/build.gradle')
        def sub2BuildFile = new File(projectDir, 'sub2/build.gradle')

        sub1BuildFile << """ \
        dependencies {
            implementation '$dependency'
        }
        ${taskThatRequiresConfigurationDependencies()}
        """.stripIndent()

        def sub2Dependency = dependency.replace(':a', ':b')
        sub2BuildFile << """
        dependencies {
            implementation '$sub2Dependency'
        }
        ${taskThatRequiresConfigurationDependencies()}
        """.stripIndent()

        when:
        def results = runTasksAndFail('build')

        then:
        assert results.output.contains('FAILURE')
        assert results.output.contains('Failed to resolve the following dependencies:')
        assert assertResolutionFailureForDependencyForProject(results.output, actualMissingDep ?: dependency, "sub1")
        assert !results.output.contains("for project 'sub2'")

        where:
        failureType                | dependency                       | actualMissingDep
        'missing version'          | 'not.available:a'                | null
        'direct dep not found'     | 'not.available:a:1.0.0'          | null
        'transitive dep not found' | 'has.missing.transitive:a:1.0.0' | 'transitive.not.available:a:1.0.0'
    }

    @Unroll
    def 'handles task that requires resolved configuration with an issue due to #failureType - multiproject and parallel'() {
        given:
        setupMultiProject()

        def sub1BuildFile = new File(projectDir, 'sub1/build.gradle')
        def sub2BuildFile = new File(projectDir, 'sub2/build.gradle')

        sub1BuildFile << """
        dependencies {
            implementation '$dependency'
        }
        ${taskThatRequiresConfigurationDependencies()}
        """.stripIndent()

        def sub2Dependency = dependency.replace(':a', ':b')
        sub2BuildFile << """
        dependencies {
            implementation '$sub2Dependency'
        }
        ${taskThatRequiresConfigurationDependencies()}
        """.stripIndent()

        when:
        def results = runTasksAndFail('build')

        then:
        assert results.output.contains('FAILURE')
        assertResolutionFailureMessage(results.output)
        assert assertResolutionFailureForDependencyForProject(results.output, actualMissingDep ?: dependency, "sub1")
        assert !results.output.contains("for project 'sub2'")

        where:
        failureType                | dependency                       | actualMissingDep
        'missing version'          | 'not.available:a'                | null
        'direct dep not found'     | 'not.available:a:1.0.0'          | null
        'transitive dep not found' | 'has.missing.transitive:a:1.0.0' | 'transitive.not.available:a:1.0.0'
    }

    @Unroll
    def 'resolved versions are not equal to locked versions because locked versions are not aligned - core alignment #coreAlignment - core locking #coreLocking'() {
        given:
        setupSingleProjectWithLockedVersionsThatAreNotAligned()

        when:
        def flags = ["-Dnebula.features.coreAlignmentSupport=${coreAlignment}", "-Dnebula.features.coreLockingSupport=${coreLocking}"]
        def insightResults = runTasksAndFail('dependencyInsight', '--dependency', 'test.nebula', *flags)
        def results = runTasksAndFail('dependencies', '--configuration', 'compileClasspath', *flags)

        then:
        insightResults.output.contains('test.nebula:a:1.1.0 -> 1.2.0\n')
        insightResults.output.contains('test.nebula:b:1.2.0\n')
        insightResults.output.contains('test.nebula:c:1.1.0 -> 1.2.0\n')

        insightResults.output.contains('FAILED')

        insightResults.output.contains("Dependency lock state is out of date:")
        insightResults.output.contains("Resolved 'test.nebula:a:1.2.0' instead of locked version '1.1.0' for project")
        insightResults.output.contains("Resolved 'test.nebula:c:1.2.0' instead of locked version '1.1.0' for project")
        insightResults.output.contains("Resolved 'test.nebula:d:1.2.0' instead of locked version '1.1.0' for project")
        insightResults.output.contains('Please update your dependency locks or your build file constraints.')

        results.output.contains('FAILED')

        results.output.contains("Dependency lock state is out of date:")
        results.output.contains("Resolved 'test.nebula:a:1.2.0' instead of locked version '1.1.0' for project")
        results.output.contains("Resolved 'test.nebula:c:1.2.0' instead of locked version '1.1.0' for project")
        results.output.contains("Resolved 'test.nebula:d:1.2.0' instead of locked version '1.1.0' for project")
        results.output.contains('Please update your dependency locks or your build file constraints.')

        where:
        coreAlignment | coreLocking
        true          | false
    }

    @Unroll
    def 'resolved versions are not equal to locked versions - handles unlocked transitives - core alignment #coreAlignment - core locking #coreLocking'() {
        given:
        setupSingleProjectWithLockedVersionsThatAreNotAligned()

        when:
        def flags = ["-Dnebula.features.coreAlignmentSupport=${coreAlignment}", "-Dnebula.features.coreLockingSupport=${coreLocking}"]
        runTasks('generateLock', 'saveLock', *flags) // without locking transitives
        def results = runTasks('dependencies', '--configuration', 'compileClasspath', *flags)

        then:
        results.output.contains('SUCCESS')

        where:
        coreAlignment | coreLocking
        true          | false
    }

    @Unroll
    def 'resolved versions are not equal to locked versions with override file - core alignment #coreAlignment - core locking #coreLocking'() {
        given:
        setupSingleProjectWithLockedVersionsThatAreNotAligned()

        def graph = new DependencyGraphBuilder()
                .addModule(new ModuleBuilder('test.nebula:a:1.3.0').addDependency('test.nebula:d:1.3.0').build())
                .addModule('test.nebula:b:1.3.0')
                .addModule('test.nebula:c:1.3.0')
                .build()
        def updatedmavenrepo = new GradleDependencyGenerator(graph, "$projectDir/testrepogen")
        updatedmavenrepo.generateTestMavenRepo()

        def dependenciesLockOverride = new File(projectDir, 'override.lock')
        dependenciesLockOverride << '''
            { "test.nebula:a": "1.3.0" }
            '''.stripIndent()

        when:
        def flags = ['-PdependencyLock.overrideFile=override.lock', "-Dnebula.features.coreAlignmentSupport=${coreAlignment}", "-Dnebula.features.coreLockingSupport=${coreLocking}"]
        def insightResults = runTasksAndFail('dependencyInsight', '--dependency', 'test.nebula', *flags)
        def results = runTasksAndFail('dependencies', '--configuration', 'compileClasspath', *flags)

        then:
        insightResults.output.contains('using override file: override.lock')

        insightResults.output.contains('test.nebula:a:1.1.0 -> 1.3.0\n')
        insightResults.output.contains('test.nebula:b:1.3.0\n')
        insightResults.output.contains('test.nebula:c:1.1.0 -> 1.3.0\n')
        insightResults.output.contains('test.nebula:d:1.3.0\n')

        insightResults.output.contains('FAILED')

        insightResults.output.contains("Dependency lock state is out of date:")
        !insightResults.output.contains("Resolved 'test.nebula:a:1.3.0' instead of locked version")
        insightResults.output.contains("Resolved 'test.nebula:b:1.3.0' instead of locked version '1.2.0' for project")
        insightResults.output.contains("Resolved 'test.nebula:c:1.3.0' instead of locked version '1.1.0' for project")
        insightResults.output.contains("Resolved 'test.nebula:d:1.3.0' instead of locked version '1.1.0' for project")
        insightResults.output.contains('Please update your dependency locks or your build file constraints.')

        results.output.contains('FAILED')

        results.output.contains("Dependency lock state is out of date:")
        !results.output.contains("Resolved 'test.nebula:a:1.3.0' instead of locked version")
        results.output.contains("Resolved 'test.nebula:b:1.3.0' instead of locked version '1.2.0' for project")
        results.output.contains("Resolved 'test.nebula:c:1.3.0' instead of locked version '1.1.0' for project")
        results.output.contains("Resolved 'test.nebula:d:1.3.0' instead of locked version '1.1.0' for project")
        results.output.contains('Please update your dependency locks or your build file constraints.')

        where:
        coreAlignment | coreLocking
        true          | false
    }

    @Unroll
    def 'resolved versions are not equal to locked versions - can configure extension messaging - core alignment #coreAlignment - core locking #coreLocking'() {
        given:
        setupSingleProjectWithLockedVersionsThatAreNotAligned()
        def extensionMessage = 'You may see this after changing from Nebula alignment to core Gradle alignment for the first time in this repository'
        buildFile << """
            import nebula.plugin.dependencyverifier.DependencyResolutionVerifierExtension
            plugins.withId('com.netflix.nebula.dependency-lock') {
                def extension = extensions.getByType(DependencyResolutionVerifierExtension.class)
                extension.resolvedVersionDoesNotEqualLockedVersionMessageAddition = '$extensionMessage'
            }
        """.stripIndent()

        when:
        def flags = ["-Dnebula.features.coreAlignmentSupport=${coreAlignment}", "-Dnebula.features.coreLockingSupport=${coreLocking}"]
        def results = runTasksAndFail('dependencies', '--configuration', 'compileClasspath', *flags)

        then:
        results.output.contains('Please update your dependency locks or your build file constraints.')
        results.output.contains(extensionMessage)

        where:
        coreAlignment | coreLocking
        true          | false
    }

    @Unroll
    def 'resolved versions are not equal to locked versions because locked versions are not aligned - multiproject - core alignment #coreAlignment - core locking #coreLocking'() {
        given:
        setupMultiProjectWithLockedVersionsThatAreNotAligned()

        when:
        def flags = ["-Dnebula.features.coreAlignmentSupport=${coreAlignment}", "-Dnebula.features.coreLockingSupport=${coreLocking}"]
        def results = runTasksAndFail('dependenciesForAll', '--configuration', 'compileClasspath', *flags)

        then:
        results.output.contains('FAILED')

        results.output.contains("Dependency lock state is out of date:")
        results.output.contains("Resolved 'test.nebula:a:1.2.0' instead of locked version '1.1.0' for project 'sub1'")
        results.output.contains("Resolved 'test.nebula:c:1.2.0' instead of locked version '1.1.0' for project 'sub1'")
        results.output.contains("Resolved 'test.nebula:d:1.2.0' instead of locked version '1.1.0' for project 'sub1'")
        results.output.contains('Please update your dependency locks or your build file constraints.')

        where:
        coreAlignment | coreLocking
        true          | false
    }

    @Unroll
    def 'resolved versions are not equal to locked versions because locked versions are not aligned - multiproject and parallel - core alignment #coreAlignment - core locking #coreLocking'() {
        given:
        setupMultiProjectWithLockedVersionsThatAreNotAligned()

        when:
        def flags = ["-Dnebula.features.coreAlignmentSupport=${coreAlignment}", "-Dnebula.features.coreLockingSupport=${coreLocking}"]
        def results = runTasksAndFail('dependenciesForAll', '--configuration', 'compileClasspath', *flags, '--parallel')

        then:
        results.output.contains('FAILED')

        results.output.contains("Dependency lock state is out of date:")

        results.output.contains("Resolved 'test.nebula:a:1.2.0' instead of locked version '1.1.0' for project 'sub1'")
        results.output.contains("Resolved 'test.nebula:c:1.2.0' instead of locked version '1.1.0' for project 'sub1'")
        results.output.contains("Resolved 'test.nebula:d:1.2.0' instead of locked version '1.1.0' for project 'sub1'")

        results.output.contains("Resolved 'test.nebula:e:3.2.0' instead of locked version '3.1.0' for project 'sub2'")
        results.output.contains("Resolved 'test.nebula:g:3.2.0' instead of locked version '3.1.0' for project 'sub2'")
        results.output.contains("Resolved 'test.nebula:h:3.2.0' instead of locked version '3.1.0' for project 'sub2'")

        results.output.contains('Please update your dependency locks or your build file constraints.')

        where:
        coreAlignment | coreLocking
        true          | false
    }

    @Unroll
    @Ignore
    def 'resolved versions are not equal to locked versions because locked versions are not aligned - multiproject with global locks'() {
        given:
        setupMultiProjectWithLockedVersionsThatAreNotAligned()

        new File(projectDir, 'dependencies.lock').delete()
        new File(projectDir, 'sub1/dependencies.lock').delete()
        new File(projectDir, 'sub2/dependencies.lock').delete()

        String globalLockText = """\
            {
                "_global_": {
                    "${moduleName}:sub1": {
                        "project": true
                    },
                    "${moduleName}:sub2": {
                        "project": true
                    },
                    "test.nebula:a": {
                        "firstLevelTransitive": [
                            "${moduleName}:sub1"
                        ],
                        "locked": "1.1.0"
                    },
                    "test.nebula:b": {
                        "firstLevelTransitive": [
                            "${moduleName}:sub1"
                        ],
                        "locked": "1.2.0"
                    },
                    "test.nebula:c": {
                        "firstLevelTransitive": [
                            "${moduleName}:sub1"
                        ],
                        "locked": "1.1.0"
                    },
                    "test.nebula:d": {
                        "locked": "1.1.0",
                        "transitive": [
                            "test.nebula:a"
                        ]
                    },
                    "test.nebula:e": {
                        "firstLevelTransitive": [
                            "${moduleName}:sub2"
                        ],
                        "locked": "3.1.0"
                    },
                    "test.nebula:f": {
                        "firstLevelTransitive": [
                            "${moduleName}:sub2"
                        ],
                        "locked": "3.2.0"
                    },
                    "test.nebula:g": {
                        "firstLevelTransitive": [
                            "${moduleName}:sub2"
                        ],
                        "locked": "3.1.0"
                    },
                    "test.nebula:h": {
                        "locked": "3.1.0",
                        "transitive": [
                            "test.nebula:e"
                        ]
                    }
                }
            }""".stripIndent()
        new File(projectDir, 'global.lock').text = globalLockText

        when:
        def flags = ["-Dnebula.features.coreAlignmentSupport=true", "-Dnebula.features.coreLockingSupport=false"]
        def results = runTasksAndFail('dependenciesForAll', '--configuration', 'compileClasspath', *flags)

        then:
        results.output.contains('FAILED')

        results.output.contains("Dependency lock state is out of date:")

        results.output.contains("Resolved 'test.nebula:a:1.2.0' instead of locked version '1.1.0' for project 'sub1'")
        results.output.contains("Resolved 'test.nebula:c:1.2.0' instead of locked version '1.1.0' for project 'sub1'")
        results.output.contains("Resolved 'test.nebula:d:1.2.0' instead of locked version '1.1.0' for project 'sub1'")

        results.output.contains('Please update your dependency locks or your build file constraints.')

        when:
        def updateGlobalLocks = runTasks('deleteGlobalLock', 'generateGlobalLock', 'saveGlobalLock', '-PdependencyLock.includeTransitives=true', *flags)
        def updatedDependencies = runTasks('dependenciesForAll', '--configuration', 'compileClasspath', *flags)

        then:
        updatedDependencies.output.contains('SUCCESS')

        updatedDependencies.output.contains('test.nebula:a:1.1.0 -> 1.2.0\n')
        updatedDependencies.output.contains('test.nebula:b:1.2.0\n')
        updatedDependencies.output.contains('test.nebula:c:1.1.0 -> 1.2.0\n')
        updatedDependencies.output.contains('test.nebula:d:1.2.0\n')
    }

    @Unroll
    def 'resolved versions are equal to locked versions - with a substitution in place - core alignment #coreAlignment - core locking #coreLocking'() {
        // Note: this scenario had been failing with `Multiple forces on different versions for virtual platform` when we used the resolutionStrategy.dependencySubstitution dsl for dependency locking
        given:
        def rulesJsonFile = new File(projectDir, 'rules.json')
        rulesJsonFile << '''\
            {
                "deny": [], "reject": [], "substitute": [
                    {
                        "module": "com.fasterxml.jackson.core:jackson-databind:[2.9.9,2.9.9.3)",
                        "with": "com.fasterxml.jackson.core:jackson-databind:2.9.9.3",
                        "reason": "There is a vulnerability, see...",
                        "author": "Example Person <person@example.org>",
                        "date": "2016-03-17T20:21:20.368Z"
                    }
                ], "replace": [],
                "align": [
                    {
                        "name": "basic-align-jackson-libraries",
                        "group": "com\\\\.fasterxml\\\\.jackson\\\\.(core|dataformat|datatype|jaxrs|jr|module)",
                        "reason": "Align jackson dependencies",
                        "author": "Example Person <person@example.org>",
                        "date": "2016-03-17T20:21:20.368Z"
                    }
                ]
            }
        '''.stripIndent()

        def graph = new DependencyGraphBuilder()
                .addModule(new ModuleBuilder('some.dependency:a:1.1.0')
                        .addDependency('com.fasterxml.jackson.core:jackson-databind:2.9.9')
                        .addDependency('com.fasterxml.jackson.core:jackson-core:2.9.9')
                        .build())
                .build()
        def mavenrepo = new GradleDependencyGenerator(graph, "$projectDir/testrepogen")
        mavenrepo.generateTestMavenRepo()

        definePluginOutsideOfPluginBlock = true
        buildFile << """\
            buildscript {
                repositories { mavenCentral() }
                dependencies {
                    classpath 'com.netflix.nebula:gradle-resolution-rules-plugin:latest.release'
                }
            }
            apply plugin: 'com.netflix.nebula.resolution-rules'
            apply plugin: 'com.netflix.nebula.dependency-lock'
            apply plugin: 'java'
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
                mavenCentral()
            }
            dependencies {
                resolutionRules files('$rulesJsonFile')
                implementation 'some.dependency:a:1.1.0'
            }
            """.stripIndent()

        when:
        def basicLock = runTasks('generateLock', 'saveLock', '-PdependencyLock.includeTransitives=true') // with Nebula alignment, Nebula locking
        def basicDependencies = runTasks('dependencies', '--configuration', 'compileClasspath') // with Nebula alignment, Nebula locking

        then:
        basicDependencies.output.contains('SUCCESS')
        basicDependencies.output.contains('com.fasterxml.jackson.core:jackson-databind:2.9.9 -> 2.9.9.3\n')
        basicDependencies.output.contains('com.fasterxml.jackson.core:jackson-annotations:2.9.0 -> 2.9.9\n')
        basicDependencies.output.contains('com.fasterxml.jackson.core:jackson-core:2.9.9\n')

        when:
        // going down the code path with the verifier
        def flags = ["-Dnebula.features.coreAlignmentSupport=${coreAlignment}", "-Dnebula.features.coreLockingSupport=${coreLocking}"]
        def results = runTasks('dependencies', '--configuration', 'compileClasspath', *flags)
        def insightResults = runTasks('dependencyInsight', '--dependency', 'jackson', *flags)

        then:
        insightResults.output.contains('Selected by rule: substituted com.fasterxml.jackson.core:jackson-databind')
        insightResults.output.contains('Selected by rule: com.fasterxml.jackson.core:jackson-databind locked')

        results.output.contains('com.fasterxml.jackson.core:jackson-databind:2.9.9 -> 2.9.9.3\n')
        results.output.contains('com.fasterxml.jackson.core:jackson-annotations:2.9.0 -> 2.9.9\n')
        results.output.contains('com.fasterxml.jackson.core:jackson-core:2.9.9\n')

        where:
        coreAlignment | coreLocking
        true          | false
    }

    @Unroll
    def 'works with executing tasks from included composite builds with no errors'() {
        given:
        setupCompositeBuildProjects()

        when:
        def results = runTasks(':included-project:sub1:dependencies', '--configuration', 'compileClasspath')

        then:
        assert results.output.contains('> Task :included-project:sub1:dependencies')
        assert !results.output.contains('FAIL')
    }

    @Unroll
    def 'works with executing tasks from included composite builds with errors'() {
        given:
        setupCompositeBuildProjects()
        buildFile << """
                dependencies {
                    implementation 'not.available:a:1.0.0' // dependency is not found
                }
                """.stripIndent()

        def sub1Dir = new File(projectDir, "included-project/sub1")
        sub1Dir.mkdirs()
        def sub1BuildFile = new File(sub1Dir, "build.gradle")
        sub1BuildFile << """
                dependencies {
                    implementation 'not.available:b:1.0.0' // dependency is not found either
                }
                """.stripIndent()

        when:
        def results = runTasksAndFail(':included-project:sub1:dependencies', '--configuration', 'compileClasspath')

        then:
        assert results.output.contains('> Task :included-project:sub1:dependencies')
        assert assertResolutionFailureForDependencyForProject(results.output, "not.available:b:1.0.0", "sub1")
        assert results.output.contains('FAIL')
    }

    private static boolean assertResolutionFailureMessage(String resultsOutput) {
        return resultsOutput.contains('Failed to resolve the following dependencies:')
    }

    private static boolean assertResolutionFailureForDependency(String resultsOutput, String dependency) {
        return assertResolutionFailureForDependency(resultsOutput, dependency, 1)
    }

    private static boolean assertResolutionFailureForDependency(String resultsOutput, String dependency, int index) {
        return resultsOutput.contains("${index}. Failed to resolve '" + dependency + "' for project")
    }

    private static boolean assertResolutionFailureForDependencyForProject(String resultsOutput, String dependency, String projectName) {
        return resultsOutput.contains("1. Failed to resolve '" + dependency + "' for project '" + projectName + "'")
    }

    private static boolean assertNoResolutionFailureMessage(String resultsOutput) {
        return !resultsOutput.contains('Failed to resolve the following dependencies:')
    }

    private static String taskThatRequiresConfigurationDependencies() {
        return """
            task taskWithConfigurationDependencies {
                def compileClasspath = configurations.compileClasspath
                inputs.files compileClasspath
                doLast { compileClasspath.each { } }
            }
            if(project.tasks.findByName('dependenciesForAll') != null) {
                project.tasks.getByName('dependenciesForAll').dependsOn project.tasks.named('taskWithConfigurationDependencies')
            }
            project.tasks.getByName('dependencies').dependsOn project.tasks.named('taskWithConfigurationDependencies')
            project.tasks.getByName('build').dependsOn project.tasks.named('taskWithConfigurationDependencies')
            """.stripIndent()
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
                id 'com.netflix.nebula.dependency-lock'
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
                id 'com.netflix.nebula.dependency-lock'
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

    private void setupCompositeBuildProjects() {
        setupSingleProject()
        settingsFile << "\nincludeBuild('included-project')"

        def includedProjectDir = new File(projectDir, "included-project")
        includedProjectDir.mkdirs()
        def includedProjectBuildFile = new File(includedProjectDir, "build.gradle")
        includedProjectBuildFile.createNewFile()
        includedProjectBuildFile.text = """
            plugins {
                id 'java'
            }
            """.stripIndent()
        def includedProjectSettingsFile = new File(includedProjectDir, "settings.gradle")
        includedProjectSettingsFile.createNewFile()
        includedProjectSettingsFile.text = """
            rootProject.name = 'included-project'
            
            include "sub1"
            include "sub2"
            """.stripIndent()
        def includedSub1Dir = new File(includedProjectDir, "sub1")
        includedSub1Dir.mkdirs()
        def includedSub1BuildFiles = new File(includedSub1Dir, "build.gradle")
        includedSub1BuildFiles.text = buildFile.text
        def includedSub2Dir = new File(includedProjectDir, "sub2")
        includedSub2Dir.mkdirs()
        def includedSub2BuildFiles = new File(includedSub2Dir, "build.gradle")
        includedSub2BuildFiles.text = buildFile.text
    }

    void setupSingleProjectWithLockedVersionsThatAreNotAligned() {
        def (GradleDependencyGenerator mavenrepo, File rulesJsonFile, String dependencyLockFileContents) = commonSetupForProjectWithLockedVersionsThatAreNotAligned()
        def dependencyLock = new File(projectDir, 'dependencies.lock')
        dependencyLock << dependencyLockFileContents

        buildFile << """\
            buildscript {
                repositories { mavenCentral() }
                dependencies {
                    classpath 'com.netflix.nebula:gradle-resolution-rules-plugin:latest.release'
                }
            }
            apply plugin: 'com.netflix.nebula.resolution-rules'
            apply plugin: 'com.netflix.nebula.dependency-lock'
            apply plugin: 'java'
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                resolutionRules files('$rulesJsonFile')
                implementation 'test.nebula:a:1.1.0'
                implementation 'test.nebula:b:1.2.0'
                implementation 'test.nebula:c:1.1.0'
            }
            """.stripIndent()
    }

    void setupMultiProjectWithLockedVersionsThatAreNotAligned() {
        def (GradleDependencyGenerator mavenrepo, File rulesJsonFile, String dependencyLockFileContents) = commonSetupForProjectWithLockedVersionsThatAreNotAligned()

        def graph = new DependencyGraphBuilder()
                .addModule(new ModuleBuilder('test.nebula:e:3.1.0').addDependency('test.nebula:h:3.1.0').build())
                .addModule(new ModuleBuilder('test.nebula:e:3.2.0').addDependency('test.nebula:h:3.2.0').build())
                .addModule('test.nebula:f:3.1.0')
                .addModule('test.nebula:f:3.2.0')
                .addModule('test.nebula:g:3.1.0')
                .addModule('test.nebula:g:3.2.0')
                .build()
        def updatedmavenrepo = new GradleDependencyGenerator(graph, "$projectDir/testrepogen")
        updatedmavenrepo.generateTestMavenRepo()

        buildFile << """\
            buildscript {
                repositories { mavenCentral() }
                dependencies {
                    classpath 'com.netflix.nebula:gradle-resolution-rules-plugin:latest.release'
                }
            }
            subprojects {
                task dependenciesForAll(type: DependencyReportTask) {}
            }
            allprojects {
                apply plugin: 'com.netflix.nebula.resolution-rules'
                apply plugin: 'com.netflix.nebula.dependency-lock'
                apply plugin: 'java'
                repositories {
                    ${mavenrepo.mavenRepositoryBlock}
                }
                dependencies {
                    resolutionRules files('$rulesJsonFile')
                }
            }
      
            """.stripIndent()

        def subProject1BuildFileContent = """
            dependencies {
                implementation 'test.nebula:a:1.1.0'
                implementation 'test.nebula:b:1.2.0'
                implementation 'test.nebula:c:1.1.0'
            }
            """.stripIndent()

        def subProject2BuildFileContent = """
            dependencies {
                implementation 'test.nebula:e:3.1.0'
                implementation 'test.nebula:f:3.2.0'
                implementation 'test.nebula:g:3.1.0'
            }
            """.stripIndent()

        addSubproject("sub1", subProject1BuildFileContent)
        addSubproject("sub2", subProject2BuildFileContent)

        def dependencyLock = new File(projectDir, 'dependencies.lock')
        def dependencyLockSubproject1 = new File(projectDir, 'sub1/dependencies.lock')
        def dependencyLockSubproject2 = new File(projectDir, 'sub2/dependencies.lock')
        dependencyLock << "{ }"
        dependencyLockSubproject1 << dependencyLockFileContents

        def dependencyLockFileContentsSubproject2 = '''\
        {
            "compileClasspath": {
                "test.nebula:e": { "locked": "3.1.0" },
                "test.nebula:f": { "locked": "3.2.0" },
                "test.nebula:g": { "locked": "3.1.0" },
                "test.nebula:h": {
                    "locked": "3.1.0",
                    "transitive": [
                        "test.nebula:e"
                    ]
                }
            }
        }
        '''.stripIndent()
        dependencyLockSubproject2 << dependencyLockFileContentsSubproject2
    }

    private List commonSetupForProjectWithLockedVersionsThatAreNotAligned() {
        // uses direct and transitive dependencies
        definePluginOutsideOfPluginBlock = true
        def graph = new DependencyGraphBuilder()
                .addModule(new ModuleBuilder('test.nebula:a:1.1.0').addDependency('test.nebula:d:1.1.0').build())
                .addModule(new ModuleBuilder('test.nebula:a:1.2.0').addDependency('test.nebula:d:1.2.0').build())
                .addModule('test.nebula:b:1.1.0')
                .addModule('test.nebula:b:1.2.0')
                .addModule('test.nebula:c:1.1.0')
                .addModule('test.nebula:c:1.2.0')
                .build()
        def mavenrepo = new GradleDependencyGenerator(graph, "$projectDir/testrepogen")
        mavenrepo.generateTestMavenRepo()

        def rulesJsonFile = new File(projectDir, 'rules.json')
        rulesJsonFile << '''\
            {
                "deny": [], "reject": [], "substitute": [], "replace": [],
                "align": [
                    {
                        "name": "testNebula",
                        "group": "test.nebula",
                        "reason": "Align test.nebula dependencies",
                        "author": "Example Person <person@example.org>",
                        "date": "2016-03-17T20:21:20.368Z"
                    }
                ]
            }
        '''.stripIndent()

        def dependencyLockFileContents = '''\
        {
            "compileClasspath": {
                "test.nebula:a": { "locked": "1.1.0" },
                "test.nebula:b": { "locked": "1.2.0" },
                "test.nebula:c": { "locked": "1.1.0" },
                "test.nebula:d": {
                    "locked": "1.1.0",
                    "transitive": [
                        "test.nebula:a"
                    ]
                }
            }
        }
        '''.stripIndent()
        [mavenrepo, rulesJsonFile, dependencyLockFileContents]
    }
}
