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

import static nebula.plugin.VerifierOutputAssertionsBase.assertConfigurationCacheStateCouldNotBeStored
import static nebula.plugin.VerifierOutputAssertionsBase.assertConfigurationCachingIsNotMentioned
import static nebula.plugin.dependencyverifier.DependencyResolutionVerifierTest.OutputAssertions.assertExecutionFailedForTask
import static nebula.plugin.dependencyverifier.DependencyResolutionVerifierTest.OutputAssertions.assertFailureMessageIsDisplayedOnce
import static nebula.plugin.dependencyverifier.DependencyResolutionVerifierTest.OutputAssertions.assertNoConfigurationCacheStoringIssues
import static nebula.plugin.dependencyverifier.DependencyResolutionVerifierTest.OutputAssertions.assertNoResolutionFailureMessage
import static nebula.plugin.dependencyverifier.DependencyResolutionVerifierTest.OutputAssertions.assertResolutionFailureForDependency
import static nebula.plugin.dependencyverifier.DependencyResolutionVerifierTest.OutputAssertions.assertResolutionFailureForDependencyForProject
import static nebula.plugin.dependencyverifier.DependencyResolutionVerifierTest.OutputAssertions.assertResolutionFailureForMissingVersionDependencies
import static nebula.plugin.dependencyverifier.DependencyResolutionVerifierTest.OutputAssertions.assertResolutionFailureForOneOfTheseDependencies
import static nebula.plugin.dependencyverifier.DependencyResolutionVerifierTest.OutputAssertions.assertResolutionFailureMessage
import static nebula.plugin.dependencyverifier.DependencyResolutionVerifierTest.OutputAssertions.dependencyProjectPair

            import nebula.plugin.dependencyverifier.DependencyResolutionVerifierExtension
            import nebula.plugin.dependencyverifier.DependencyResolutionVerifierExtension
import nebula.plugin.BaseIntegrationTestKitSpec
import nebula.plugin.VerifierOutputAssertionsBase
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder
import spock.lang.Ignore
import spock.lang.Subject
import spock.lang.Unroll
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

        results.output.contains('Dependency lock state is out of date:')
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

        results.output.contains('Dependency lock state is out of date:')

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
        def flags = ['-Dnebula.features.coreAlignmentSupport=true', '-Dnebula.features.coreLockingSupport=false']
        def results = runTasksAndFail('dependenciesForAll', '--configuration', 'compileClasspath', *flags)

        then:
        results.output.contains('FAILED')

        results.output.contains('Dependency lock state is out of date:')

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

        def sub1Dir = new File(projectDir, 'included-project/sub1')
        sub1Dir.mkdirs()
        def sub1BuildFile = new File(sub1Dir, 'build.gradle')
        sub1BuildFile << """
                dependencies {
                    implementation 'not.available:b:1.0.0' // dependency is not found either
                }
                """.stripIndent()

        when:
        def results = runTasksAndFail(':included-project:sub1:dependencies', '--configuration', 'compileClasspath')

        then:
        assert results.output.contains('> Task :included-project:sub1:dependencies')
        assertResolutionFailureForDependencyForProject(results.output, 'not.available:b:1.0.0', 'sub1')
        assert results.output.contains('FAIL')
    }

    @Unroll
    def 'verifier is scoped to the queried configuration: #description task succeeds'() {
        given:
        singleProjectWithConsistentResolutionSetup()

        when: 'configuration with no conflicting constraints resolves foo:2.0.0 successfully'
        def noIssuesResult = runTasks(*tasks, '--configuration', 'runtimeClasspath')

        and: 'configuration with conflicting constraints cannot resolve successfully'
        def conflictsResult = runTasksAndFail(*tasks, '--configuration', 'compileClasspath')

        then: 'configuration with no conflicting constraints shows the correct resolved version'
        noIssuesResult.output.contains('test.nebula:foo:2.0.0\n')

        and: 'verifier does not fail when selected config has an issue, even if other configs do have an issue'
        verifyAll {
            !noIssuesResult.output.contains('FAILURE')
            !noIssuesResult.output.contains('verifyDependencyResolution FAILED')

            !noIssuesResult.output.contains('Failed to resolve')
        }

        and: 'in particular, the synthetic {strictly 2.0.0} constraint is not falsely reported as unresolved when looking at a different configuration altogether'
        !noIssuesResult.output.contains("Failed to resolve 'test.nebula:foo:{strictly 2.0.0}'")

        and: 'verifier fails when selected configs have an issue, even in reporting tasks'
        verifyAll {
            conflictsResult.output.contains('FAILURE')
            conflictsResult.output.contains('verifyDependencyResolution FAILED')

            // different output depending on report task type; same effect
            conflictsResult.output.contains('test.nebula:foo:{strictly 1.0.0} -> 1.0.0 FAILED') || conflictsResult.output.contains('test.nebula:foo:{strictly 1.0.0} FAILED')
            conflictsResult.output.contains('test.nebula:foo:{strictly 2.0.0} -> 2.0.0 FAILED') || conflictsResult.output.contains('test.nebula:foo:{strictly 2.0.0} FAILED')
        }

        where:
        tasks                                                    | description
        ['dependencyInsight', '--dependency', 'test.nebula:foo'] | 'dependency insight'
        ['dependencies']                                         | 'dependencies'
    }

    private static class OutputAssertions extends VerifierOutputAssertionsBase {

        static void assertResolutionFailureForMissingVersionDependencies(String resultsOutput, List<String> dependencyNames) {
            String missingList = dependencyNames.join(', ')
            boolean hasAResolutionFailureForDependency = dependencyNames.any { hasResolutionFailureForDependency(resultsOutput, it) }

            String missingDependenciesMessage = 'The following dependencies are missing a version: ' + missingList
            boolean verifierMessage = resultsOutput.contains(missingDependenciesMessage) &&
                    resultsOutput.contains('If you have been using a BOM')

            assert hasAResolutionFailureForDependency || verifierMessage, "Expected resolution failure or verifier missing-version message for ${missingList}"
        }

        /**
         * Asserts the resolution-failure message for this dependency appears exactly once in the output,
         * to catch duplicate or spammed error reporting. Uses canonical verifier or Gradle phrasing and counts occurrences.
         */
        static void assertFailureMessageIsDisplayedOnce(String resultsOutput, String dependency) {
            assert hasResolutionFailureForDependency(resultsOutput, dependency),
                    "Expected to see resolution failure for dependency '${dependency}'"

            String verifierExpectedText = """
                > Failed to resolve the following dependencies:
                1. $FAILED_RESOLVE_PREFIX '$dependency' for project
                """.stripIndent()
            String gradleExpectedText = '> ' + COULD_NOT_FIND + dependency
            int verifierCount = resultsOutput.findAll(verifierExpectedText).size()
            int gradleCount = resultsOutput.findAll(gradleExpectedText).size()

            if (verifierCount > 0) {
                assert verifierCount == 1,
                        "Resolution failure for '${dependency}' (verifier format) should appear exactly once, but appeared ${verifierCount} times"
            } else if (gradleCount > 0) {
                assert gradleCount == 1,
                        "Resolution failure for '${dependency}' (Gradle format) should appear exactly once, but appeared ${gradleCount} times"
            }
        }

        /**
         * Asserts that the build output shows a resolution failure for at least one of the given dependency–project pairs.
         * Use {@link #dependencyProjectPair(String, String)} to build each pair.
         *
         * @param resultsOutput build output (e.g. from runTasksAndFail)
         * @param pairList pairs of (dependency coordinate, project name); at least one must appear as a resolution failure in the output
         */
        static void assertResolutionFailureForOneOfTheseDependencies(String resultsOutput, List<DependencyProjectPair> pairList) {
            boolean anyMatch = pairList.any { hasResolutionFailureForDependencyForProject(resultsOutput, it.dependency, it.project) }
            String expected = pairList.collect { "${it.dependency} in ${it.project}" }.join(' or ')
            assert anyMatch, "Expected resolution failure for ${expected}"
        }

        static boolean hasResolutionFailureForDependencyForProject(String resultsOutput, String dependency, String projectName) {
            hasResolutionFailureForDependency(resultsOutput, dependency) &&
                    hasProjectContextInOutput(resultsOutput, projectName)
        }

        /** Value type pairing a dependency coordinate with a project name for assertResolutionFailureForDependencyForProjectOneOf. */
        private static final class DependencyProjectPair {

            final String dependency
            final String project

            DependencyProjectPair(String dependency, String project) {
                this.dependency = dependency
                this.project = project
            }

        }

        /** Pairs a dependency coordinate with a project name for use in assertResolutionFailureForDependencyForProjectOneOf. */
        static DependencyProjectPair dependencyProjectPair(String dependency, String projectName) {
            return new DependencyProjectPair(dependency, projectName)
        }

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

        addSubproject('sub1', subProjectBuildFileContent)
        addSubproject('sub2', subProjectBuildFileContent)

        writeHelloWorld(new File(projectDir, 'sub1'))
        writeHelloWorld(new File(projectDir, 'sub2'))
        writeUnitTest(new File(projectDir, 'sub1'))
        writeUnitTest(new File(projectDir, 'sub2'))
    }

    private void setupCompositeBuildProjects() {
        setupSingleProject()
        settingsFile << "\nincludeBuild('included-project')"

        def includedProjectDir = new File(projectDir, 'included-project')
        includedProjectDir.mkdirs()
        def includedProjectBuildFile = new File(includedProjectDir, 'build.gradle')
        includedProjectBuildFile.createNewFile()
        includedProjectBuildFile.text = """
            plugins {
                id 'java'
            }
            """.stripIndent()
        def includedProjectSettingsFile = new File(includedProjectDir, 'settings.gradle')
        includedProjectSettingsFile.createNewFile()
        includedProjectSettingsFile.text = """
            rootProject.name = 'included-project'

            include "sub1"
            include "sub2"
            """.stripIndent()
        def includedSub1Dir = new File(includedProjectDir, 'sub1')
        includedSub1Dir.mkdirs()
        def includedSub1BuildFiles = new File(includedSub1Dir, 'build.gradle')
        includedSub1BuildFiles.text = buildFile.text
        def includedSub2Dir = new File(includedProjectDir, 'sub2')
        includedSub2Dir.mkdirs()
        def includedSub2BuildFiles = new File(includedSub2Dir, 'build.gradle')
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

        addSubproject('sub1', subProject1BuildFileContent)
        addSubproject('sub2', subProject2BuildFileContent)

        def dependencyLock = new File(projectDir, 'dependencies.lock')
        def dependencyLockSubproject1 = new File(projectDir, 'sub1/dependencies.lock')
        def dependencyLockSubproject2 = new File(projectDir, 'sub2/dependencies.lock')
        dependencyLock << '{ }'
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

    private void singleProjectWithConsistentResolutionSetup() {
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:foo:1.0.0')
                .addModule('test.nebula:foo:2.0.0')
                .addModule(new ModuleBuilder('test.nebula:bar:1.0.0').addDependency('test.nebula:foo:2.0.0').build())
                .build()
        def localRepo = new GradleDependencyGenerator(graph, "${projectDir}/localrepo")
        localRepo.generateTestMavenRepo()

        buildFile << """\
            plugins {
                id 'com.netflix.nebula.dependency-lock'
                id 'java-library'
            }
            repositories {
                ${localRepo.mavenRepositoryBlock}
            }
            dependencies {
                // strictly 1.0.0 on compile-only scope
                compileOnlyApi('test.nebula:foo') {
                    version { strictly '1.0.0' }
                }
                // transitively brings test.nebula:foo:2.0.0 at runtime
                runtimeOnly 'test.nebula:bar:1.0.0'
            }
            // consistent resolution: compileClasspath follows runtimeClasspath versions,
            // which synthesizes a {strictly 2.0.0} constraint on compileClasspath —
            // conflicting with the user's {strictly 1.0.0}, so compileClasspath fails
            configurations.compileClasspath.shouldResolveConsistentlyWith(configurations.runtimeClasspath)
        """.stripIndent()

        writeHelloWorld()
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

    def 'verifyDependencyResolution task is registered before afterEvaluate runs'() {
        given:
        // Use init-script classpath injection so `apply plugin:` works outside the plugins {} block.
        // This lets us register an afterEvaluate callback before the plugin is applied, exercising
        // the constraint that registerVerificationTask() must be called eagerly (not deferred).
        definePluginOutsideOfPluginBlock = true

        // Register an afterEvaluate callback BEFORE applying the lock plugin.
        // Gradle fires afterEvaluate callbacks in registration order, so this fires before
        // the lock plugin's own afterEvaluate. If registerVerificationTask() is called
        // eagerly (synchronously in apply()), the task exists here; if it was deferred
        // inside afterEvaluate, it would not.
        buildFile << """\
            afterEvaluate {
                def t = tasks.findByName('verifyDependencyResolution')
                if (t == null) {
                    throw new IllegalStateException(
                        'verifyDependencyResolution was not registered before afterEvaluate ran')
                }
            }
            apply plugin: 'com.netflix.nebula.dependency-lock'
        """.stripIndent()

        when:
        def result = runTasks('help')

        then:
        !result.output.contains('verifyDependencyResolution was not registered')
        !result.output.contains('FAILURE')
    }

}
