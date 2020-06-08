package nebula.plugin.dependencylock.tasks

import nebula.plugin.dependencylock.AbstractDependencyLockPluginSpec
import nebula.plugin.dependencylock.util.LockGenerator
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder
import org.gradle.util.GradleVersion
import spock.lang.Unroll

class MigrateToCoreLocksTaskSpec extends AbstractDependencyLockPluginSpec {
    def setup() {
        def graph = new DependencyGraphBuilder()
                .addModule('third-party:a:1.0.0')
                .addModule('third-party:b:1.0.0')
                .addModule(new ModuleBuilder('test.nebula:some-dep:1.0.0').addDependency('third-party:a:1.0.0').build())
                .addModule(new ModuleBuilder('test.nebula:some-other-dep:1.0.0').addDependency('third-party:b:1.0.0').build())
                .addModule(new ModuleBuilder('test.nebula:e:1.0.0').addDependency('test.nebula:some-dep:1.0.0').build())
                .addModule(new ModuleBuilder('test.nebula:e:1.1.0').addDependency('test.nebula:some-other-dep:1.0.0').build())
                .build()
        mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()
    }

    def 'migration to core locks'() {
        given:
        def legacyLockFile = new File(projectDir, 'dependencies.lock')
        legacyLockFile.text = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                },
                "test.nebula:b": {
                    "locked": "1.1.0",
                    "requested": "1.+"
                }'''.stripIndent())

        when:
        def result = runTasks('migrateToCoreLocks', '--warning-mode', 'all')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compileClasspath.lockfile')
        lockFile.text.contains('test.nebula:a:1.0.0')
        lockFile.text.contains('test.nebula:b:1.1.0')

        !legacyLockFile.exists()

        when:
        def dependenciesResult = runTasks('dependencies')

        then:
        dependenciesResult.output.contains('dependency constraint')
        !dependenciesResult.output.contains('FAILED')
    }

    def 'updating migrated locks'() {
        given:
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:f:1.0.0')
                .build()
        mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()

        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            
            dependencies {
                implementation 'test.nebula:b:1.+'
                implementation 'test.nebula:f:1.+'
            }
        """.stripIndent()

        when:
        def lockResults = runTasks('dependencies', '--write-locks')

        then:
        !lockResults.output.contains('FAIL')

        when:
        def updatedGraph = new DependencyGraphBuilder()
                .addModule('test.nebula:f:1.1.0')
                .build()
        mavenrepo = new GradleDependencyGenerator(updatedGraph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()

        def mismatchedDependenciesResult = runTasks('dependencies')

        then:
        !mismatchedDependenciesResult.output.contains('FAIL')

        when:
        def updateLocksResult = runTasks('dependencies', '--update-locks', 'test.nebula:f')

        then:
        !updateLocksResult.output.contains('FAIL')

        when:
        def updatedDependencies = runTasks('dependencies')

        then:
        updatedDependencies.output.contains('test.nebula:f:1.+ -> 1.1.0')
        updatedDependencies.output.contains('dependency constraint')
        !updatedDependencies.output.contains('FAIL')
    }

    def 'migration with transitives'() {
        given:
        buildFile << """
            dependencies {
                implementation 'test.nebula:c:1.+'
            }""".stripIndent()
        def legacyLockFile = new File(projectDir, 'dependencies.lock')
        def expectedNebulaLockText = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                },
                "test.nebula:b": {
                    "locked": "1.1.0",
                    "requested": "1.+"
                },
                "test.nebula:c": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                },
                "test.nebula:d": {
                    "locked": "1.0.0",
                    "transitive": [
                        "test.nebula:c"
                    ]
                }'''.stripIndent())
        legacyLockFile.text = expectedNebulaLockText

        when:
        def result = runTasks('migrateToCoreLocks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compileClasspath.lockfile')
        lockFile.text.contains('test.nebula:a:1.0.0')
        lockFile.text.contains('test.nebula:b:1.1.0')
        lockFile.text.contains('test.nebula:c:1.0.0')
        lockFile.text.contains('test.nebula:d:1.0.0')

        !legacyLockFile.exists()
    }

    def 'migration with previously unlocked transitives'() {
        given:
        buildFile << """
            dependencies {
                implementation 'test.nebula:c:1.+'
                implementation 'test.nebula:e:1.+'
            }""".stripIndent()
        def legacyLockFile = new File(projectDir, 'dependencies.lock')
        def expectedNebulaLockText = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                },
                "test.nebula:b": {
                    "locked": "1.1.0",
                    "requested": "1.+"
                },
                "test.nebula:c": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                },
                "test.nebula:e": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                }'''.stripIndent())
        legacyLockFile.text = expectedNebulaLockText

        when:
        def result = runTasks('migrateToCoreLocks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compileClasspath.lockfile')
        lockFile.text.contains('test.nebula:a:1.0.0')
        lockFile.text.contains('test.nebula:b:1.1.0')
        lockFile.text.contains('test.nebula:c:1.0.0')
        lockFile.text.contains('test.nebula:d:1.0.0')
        lockFile.text.contains('test.nebula:e:1.0.0')
        lockFile.text.contains('test.nebula:some-dep:1.0.0')

        when:
        def verify = runTasks('dependencies')

        then:
        !verify.output.contains('Failure')
    }

    def 'migration with multiproject setup'() {
        given:
        buildFile.text = """\
            plugins {
                id 'java'
            }
            """.stripIndent()

        addSubproject('sub1', """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:a:1.0.0'
            }
        """.stripIndent())

        addSubproject('sub2', """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:c:1.0.0'
            }
        """.stripIndent())

        def sub1LegacyLockFile = new File(projectDir, 'sub1/dependencies.lock')
        sub1LegacyLockFile.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                }'''.stripIndent())

        def sub2LegacyLockFile = new File(projectDir, 'sub2/dependencies.lock')
        sub2LegacyLockFile.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:c": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                },
                "test.nebula:d": {
                    "locked": "1.0.0",
                    "transitive": [
                        "test.nebula:c"
                    ]
                }'''.stripIndent())

        when:
        def result = runTasks('migrateToCoreLocks')

        then:
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')

        def sub1ActualLocks = new File(projectDir, 'sub1/gradle/dependency-locks/').list().toList()
        sub1ActualLocks.containsAll(expectedLocks)
        def sub1LockFile = new File(projectDir, 'sub1/gradle/dependency-locks/compileClasspath.lockfile')
        sub1LockFile.text.contains('test.nebula:a:1.0.0')
        !sub1LegacyLockFile.exists()

        def sub2ActualLocks = new File(projectDir, 'sub2/gradle/dependency-locks/').list().toList()
        sub2ActualLocks.containsAll(expectedLocks)
        def sub2LockFile = new File(projectDir, 'sub2/gradle/dependency-locks/compileClasspath.lockfile')
        sub2LockFile.text.contains('test.nebula:c:1.0.0')
        sub2LockFile.text.contains('test.nebula:d:1.0.0')
        !sub2LegacyLockFile.exists()
    }

    def 'migration with project dependency'() {
        given:
        buildFile.text = """\
            plugins {
                id 'java'
            }
            """.stripIndent()

        addSubproject('sub1', """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:a:1.0.0'
            }
        """.stripIndent())

        addSubproject('sub2', """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:c:1.0.0'
                implementation project(":sub1") 
            }
        """.stripIndent())

        def sub1LegacyLockFile = new File(projectDir, 'sub1/dependencies.lock')
        sub1LegacyLockFile.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                }'''.stripIndent())

        def sub2LegacyLockFile = new File(projectDir, 'sub2/dependencies.lock')
        sub2LegacyLockFile.text = LockGenerator.duplicateIntoConfigs(
                """\
                "${projectName}:sub1": {
                    "project": true
                },
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "transitive": [
                        "${projectName}:sub1"
                    ]
                },
                "test.nebula:c": {
                    "locked": "1.0.0",
                    "requested": "1.0.0"
                },
                "test.nebula:d": {
                    "locked": "1.0.0",
                    "transitive": [
                        "test.nebula:c"
                    ]
                }""".stripIndent())

        when:
        def result = runTasks('migrateToCoreLocks', '-i')

        then:
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')
        result.output.contains("No locked version for '${projectName}:sub1' to migrate in configuration ':sub2:compileClasspath'")

        def sub1ActualLocks = new File(projectDir, 'sub1/gradle/dependency-locks/').list().toList()
        sub1ActualLocks.containsAll(expectedLocks)
        def sub1LockFile = new File(projectDir, 'sub1/gradle/dependency-locks/compileClasspath.lockfile')
        sub1LockFile.text.contains('test.nebula:a:1.0.0')
        !sub1LegacyLockFile.exists()

        def sub2ActualLocks = new File(projectDir, 'sub2/gradle/dependency-locks/').list().toList()
        sub2ActualLocks.containsAll(expectedLocks)
        def sub2LockFile = new File(projectDir, 'sub2/gradle/dependency-locks/compileClasspath.lockfile')
        sub2LockFile.text.contains('test.nebula:a:1.0.0')
        sub2LockFile.text.contains('test.nebula:c:1.0.0')
        sub2LockFile.text.contains('test.nebula:d:1.0.0')
        !sub2LegacyLockFile.exists()
    }

    def 'migration omits repeated dependency'() {
        given:
        buildFile.text = """\
            plugins {
                id 'java'
            }
            """.stripIndent()

        addSubproject('sub1', """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:a:1.0.0'
            }
        """.stripIndent())

        addSubproject('sub2', """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:a:1.0.0'
                implementation 'test.nebula:c:1.0.0'
                implementation project(":sub1") 
            }
        """.stripIndent())

        def sub1LegacyLockFile = new File(projectDir, 'sub1/dependencies.lock')
        sub1LegacyLockFile.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                }'''.stripIndent())

        def sub2LegacyLockFile = new File(projectDir, 'sub2/dependencies.lock')
        sub2LegacyLockFile.text = LockGenerator.duplicateIntoConfigs(
                """\
                "${projectName}:sub1": {
                    "project": true
                },
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.0.0",
                    "transitive": [
                        "${projectName}:sub1"
                    ]
                },
                "test.nebula:c": {
                    "locked": "1.0.0",
                    "requested": "1.0.0"
                },
                "test.nebula:d": {
                    "locked": "1.0.0",
                    "transitive": [
                        "test.nebula:c"
                    ]
                }""".stripIndent())

        when:
        def result = runTasks('migrateToCoreLocks')

        then:
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')

        def sub1ActualLocks = new File(projectDir, 'sub1/gradle/dependency-locks/').list().toList()
        sub1ActualLocks.containsAll(expectedLocks)
        def sub1LockFile = new File(projectDir, 'sub1/gradle/dependency-locks/compileClasspath.lockfile')
        sub1LockFile.text.contains('test.nebula:a:1.0.0')
        !sub1LegacyLockFile.exists()

        def sub2ActualLocks = new File(projectDir, 'sub2/gradle/dependency-locks/').list().toList()
        sub2ActualLocks.containsAll(expectedLocks)
        def sub2LockFile = new File(projectDir, 'sub2/gradle/dependency-locks/compileClasspath.lockfile')
        sub2LockFile.text.contains('test.nebula:a:1.0.0')
        sub2LockFile.text.findAll('test.nebula:a:1.0.0').size() == 1
        sub2LockFile.text.contains('test.nebula:c:1.0.0')
        sub2LockFile.text.contains('test.nebula:d:1.0.0')
        !sub2LegacyLockFile.exists()
    }

    def 'previously partial lockfiles must include all dependencies'() {
        given:
        def legacyLockFile = new File(projectDir, 'dependencies.lock')
        legacyLockFile.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                }'''.stripIndent())

        when:
        def result = runTasks('migrateToCoreLocks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compileClasspath.lockfile')
        lockFile.text.contains('test.nebula:a:1.0.0')
        lockFile.text.contains('test.nebula:b:1.1.0')
    }

    @Unroll
    def 'migrating with facet #facet'() {
        def legacyLockFile = new File(projectDir, 'dependencies.lock')
        legacyLockFile.text = createFacetLockfileText(facet)

        def sourceSetConfig
        if (setParentSourceSet) {
            sourceSetConfig = """{
                parentSourceSet = 'test'
            }""".stripIndent()
        } else {
            sourceSetConfig = ''
        }

        buildFile.text = """
            buildscript {
              repositories {
                maven {
                  url "https://plugins.gradle.org/m2/"
                }
              }
              dependencies {
                classpath "com.netflix.nebula:nebula-project-plugin:7.0.4"
              }
            }
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            apply plugin: '$plugin'
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
                mavenCentral()
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                testImplementation 'junit:junit:4.12'
            }
            facets {
                $facet $sourceSetConfig
            }
            """.stripIndent()

        def facetTestFile = createFile("${projectDir}/src/${facet}/java/${facet.capitalize()}.java")
        facetTestFile.text = """
            import org.junit.Test;
            public class ${facet.capitalize()} {
                @Test
                public void helloWorld${facet.capitalize()}() {
                    System.out.println("Hello World");
                }
            }
            """.stripIndent()

        when:
        def result = runTasks('migrateToCoreLocks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        def facetLockfiles = [
                "${facet}AnnotationProcessor.lockfile".toString(),
                "${facet}CompileClasspath.lockfile".toString(),
                "${facet}RuntimeClasspath.lockfile".toString()
        ]
        def updatedExpectedLocks = expectedLocks + facetLockfiles
        updatedExpectedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }

        def lockFile = new File(projectDir, "/gradle/dependency-locks/${facet}CompileClasspath.lockfile")
        assert lockFile.text.contains('test.nebula:a:1.0.0')
        assert lockFile.text.contains('junit:junit:4.12')
        assert lockFile.text.contains('org.hamcrest:hamcrest-core:1.3')

        where:
        facet       | plugin             | setParentSourceSet
        'integTest' | 'nebula.integtest' | false
        'smokeTest' | 'nebula.facet'     | true
        'examples'  | 'nebula.facet'     | true
    }

    @Unroll
    def 'migrating with previously unlocked facet #facet can cause dependencies to skew across configurations'() {
        def legacyLockFile = new File(projectDir, 'dependencies.lock')
        legacyLockFile.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                }
                '''.stripIndent())

        def sourceSetConfig
        if (setParentSourceSet) {
            sourceSetConfig = """{
                parentSourceSet = 'test'
            }""".stripIndent()
        } else {
            sourceSetConfig = ''
        }

        buildFile.text = """
            buildscript {
              repositories {
                maven {
                  url "https://plugins.gradle.org/m2/"
                }
              }
              dependencies {
                classpath "com.netflix.nebula:nebula-project-plugin:7.0.4"
              }
            }
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            apply plugin: '$plugin'
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
                mavenCentral()
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
            }
            facets {
                $facet $sourceSetConfig
            }
            """.stripIndent()

        when:
        def result = runTasks('migrateToCoreLocks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        def facetLockfiles = [
                "${facet}AnnotationProcessor.lockfile".toString(),
                "${facet}CompileClasspath.lockfile".toString(),
                "${facet}RuntimeClasspath.lockfile".toString()
        ]
        def updatedExpectedLocks = expectedLocks + facetLockfiles
        updatedExpectedLocks.each {
            assert actualLocks.contains(it): "There is a missing lockfile: $it"
        }

        // implementation lock came from json lockfile
        def compileClasspathLockFile = new File(projectDir, "/gradle/dependency-locks/compileClasspath.lockfile")
        compileClasspathLockFile.text.contains('test.nebula:a:1.0.0')

        // facet lock had been unlocked & resolved to different version
        def facetLockFile = new File(projectDir, "/gradle/dependency-locks/${facet}CompileClasspath.lockfile")
        assert facetLockFile.text.contains('test.nebula:a:1.1.0')

        where:
        facet       | plugin             | setParentSourceSet
        'integTest' | 'nebula.integtest' | false
        'smokeTest' | 'nebula.facet'     | true
        'examples'  | 'nebula.facet'     | true
    }

    def 'migration does not lock all configurations by default'() {
        given:
        def legacyLockFile = new File(projectDir, 'dependencies.lock')
        legacyLockFile.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                },
                "test.nebula:b": {
                    "locked": "1.1.0",
                    "requested": "1.+"
                }'''.stripIndent())

        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
                id 'jacoco'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
                mavenCentral()
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                implementation 'test.nebula:b:1.+'
            }
        """.stripIndent()

        when:
        def result = runTasks('migrateToCoreLocks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/jacocoAgent.lockfile')
        !lockFile.exists()

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')
    }


    def 'migration locks all configurations via property'() {
        given:
        def legacyLockFile = new File(projectDir, 'dependencies.lock')
        legacyLockFile.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                },
                "test.nebula:b": {
                    "locked": "1.1.0",
                    "requested": "1.+"
                }'''.stripIndent())

        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
                id 'jacoco'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
                mavenCentral()
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
                implementation 'test.nebula:b:1.+'
            }
        """.stripIndent()

        when:
        def result = runTasks('migrateToCoreLocks', '-PlockAllConfigurations=true')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/jacocoAgent.lockfile')
        lockFile.exists()

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')
    }

    def 'migration fails when there is no lockfile to migrate from'() {
        given:
        def legacyLockFile = new File(projectDir, 'dependencies.lock')
        legacyLockFile.delete()

        when:
        def result = runTasksAndFail('migrateToCoreLocks')

        then:
        result.output.contains('Stopping migration')
    }

    def 'fails migrating global locks'() {
        given:
        buildFile.text = """
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                  ${mavenrepo.mavenRepositoryBlock}
            }
            """.stripIndent()
        addSubproject('sub1', """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                  ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:a:1.0.0'
            }
            """.stripIndent())
        addSubproject('sub2', """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                  ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:c:1.0.0'
            }
            """.stripIndent())

        def legacyGlobalLockFile = new File(projectDir, 'global.lock')
        legacyGlobalLockFile.text = """\
            {
                "_global_": {
                    "${projectName}:sub1": {
                        "project": true
                    },
                    "${projectName}:sub2": {
                        "project": true
                    },
                    "test.nebula:a": {
                        "firstLevelTransitive": [
                            "${projectName}:sub1"
                        ],
                        "locked": "1.0.0"
                    },
                    "test.nebula:c": {
                        "firstLevelTransitive": [
                            "${projectName}:sub2"
                        ],
                        "locked": "1.0.0"
                    }
                }
            }
            """.stripIndent()

        when:
        def result = runTasksAndFail('migrateToCoreLocks')

        then:
        result.output.contains("Legacy global locks are not supported with core locking")
        assertFailureOccursAtPluginLevel(result.output)
        legacyGlobalLockFile.exists()
    }

    def 'task appears'() {
        when:
        def result = runTasks('tasks')

        then:
        result.output.contains('migrateToCoreLocks')
    }

    private static String createFacetLockfileText(String facet) {
        def compileBasedConfigs = GradleVersion.current().baseVersion < GradleVersion.version("6.0")
                ? ['compile', 'compileClasspath', 'default', 'runtime', 'runtimeClasspath']
                : ['compileClasspath', 'default', 'runtimeClasspath']
        def testCompileBaseConfigs = GradleVersion.current().baseVersion < GradleVersion.version("6.0")
                ? [
                "testCompile", "testCompileClasspath", "testDefault", "testRuntime", "testRuntimeClasspath",
                "${facet}Compile".toString(), "${facet}CompileClasspath".toString(), "${facet}Default".toString(), "${facet}Runtime".toString(), "${facet}RuntimeClasspath".toString()
        ]
                : [
                "testCompileClasspath", "testDefault", "testRuntimeClasspath",
                "${facet}CompileClasspath".toString(), "${facet}Default".toString(), "${facet}RuntimeClasspath".toString()
        ]
        def locks = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.0.0"
                }'''.stripIndent(),
                compileBasedConfigs,
                '''\
                "junit:junit": {
                    "locked": "4.12",
                    "requested": "4.12"
                },
                "org.hamcrest:hamcrest-core": {
                    "locked": "1.3",
                    "transitive": [
                        "junit:junit"
                    ]
                },
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.0.0"
                }'''.stripIndent(),
                testCompileBaseConfigs)
        return locks
    }

    private static void assertFailureOccursAtPluginLevel(String text) {
        assert text.contains("Failed to apply plugin [id 'nebula.dependency-lock']") ||
                text.contains("Failed to apply plugin 'nebula.dependency-lock'")
    }
}
