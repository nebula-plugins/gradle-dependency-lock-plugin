package nebula.plugin.dependencylock

import nebula.plugin.dependencylock.util.LockGenerator
import nebula.test.IntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder
import spock.lang.Unroll

class DependencyLockPluginWithCoreSpec extends IntegrationTestKitSpec {
    def expectedLocks = [
            'compile.lockfile', 'archives.lockfile', 'testCompileClasspath.lockfile', 'compileOnly.lockfile',
            'annotationProcessor.lockfile', 'runtime.lockfile', 'compileClasspath.lockfile', 'testCompile.lockfile',
            'default.lockfile', 'testAnnotationProcessor.lockfile', 'testRuntime.lockfile',
            'testRuntimeClasspath.lockfile', 'testCompileOnly.lockfile', 'runtimeClasspath.lockfile'
    ] as String[]
    def mavenrepo
    def projectName

    def setup() {
        keepFiles = true
        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.coreLockingSupport=true"

        projectName = getProjectDir().getName().replaceAll(/_\d+/, '')
        settingsFile << """\
            rootProject.name = '${projectName}'
        """.stripIndent()

        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:a:1.0.0')
                .addModule('test.nebula:a:1.1.0')
                .addModule('test.nebula:b:1.0.0')
                .addModule('test.nebula:b:1.1.0')
                .addModule('test.nebula:d:1.0.0')
                .addModule('test.nebula:d:1.1.0')
                .addModule(new ModuleBuilder('test.nebula:c:1.0.0').addDependency('test.nebula:d:1.0.0').build())
                .addModule(new ModuleBuilder('test.nebula:c:1.1.0').addDependency('test.nebula:d:1.1.0').build())
                .build()
        mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()

        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                compile 'test.nebula:a:1.+'
                compile 'test.nebula:b:1.+'
            }
        """.stripIndent()

        debug = true // if you want to debug with IntegrationTestKit, this is needed
    }

    def 'generate core lock file'() {
        when:
        def result = runTasks('dependencies', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compile.lockfile')
        lockFile.text.contains('test.nebula:a:1.1.0')
        lockFile.text.contains('test.nebula:b:1.1.0')

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')
    }

    def 'generate and update core lock file'() {
        when:
        def result = runTasks('dependencies', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compile.lockfile')
        lockFile.text.contains('test.nebula:a:1.1.0')
        lockFile.text.contains('test.nebula:b:1.1.0')

        when:
        // update build file, so it no longer matches locks
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                compile 'test.nebula:a:1.+'
                compile 'test.nebula:b:1.+'
                compile 'test.nebula:d:1.+'
            }
        """.stripIndent()
        def updateLocksResult = runTasks('dependencies', '--update-locks', 'test.nebula:d')

        then:
        lockFile.text.contains('test.nebula:d:1.1.0')

        when:
        def cleanBuildResults = runTasks('clean', 'build')

        then:
        !cleanBuildResults.output.contains('FAILURE')
    }

    def 'generate core lock file while locking all configurations via property'() {
        given:
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
                compile 'test.nebula:a:1.+'
                compile 'test.nebula:b:1.+'
            }
        """.stripIndent()

        when:
        def result = runTasks('dependencies', '--write-locks', '-PlockAllConfigurations=true')

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

    def 'generate core lock file but do not all configurations by default'() {
        given:
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
                compile 'test.nebula:a:1.+'
                compile 'test.nebula:b:1.+'
            }
        """.stripIndent()

        when:
        def result = runTasks('dependencies', '--write-locks')

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

    @Unroll
    def 'generate core lock file with #facet facet configurations'() {
        given:
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
                classpath "com.netflix.nebula:nebula-project-plugin:6.0.0"
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
                compile 'test.nebula:a:1.+'
                compile 'test.nebula:b:1.+'
                testCompile 'junit:junit:4.12'
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
        def result = runTasks('dependencies', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        def facetLockfiles = [
                "${facet}AnnotationProcessor.lockfile".toString(),
                "${facet}Compile.lockfile".toString(),
                "${facet}CompileClasspath.lockfile".toString(),
                "${facet}CompileOnly.lockfile".toString(),
                "${facet}Runtime.lockfile".toString(),
                "${facet}RuntimeClasspath.lockfile".toString()
        ]
        def updatedExpectedLocks = expectedLocks + facetLockfiles
        updatedExpectedLocks.each {
            assert actualLocks.contains(it)
        }
        def lockFile = new File(projectDir, "/gradle/dependency-locks/${facet}Compile.lockfile")
        lockFile.text.contains('test.nebula:a:1.1.0')
        lockFile.text.contains('test.nebula:b:1.1.0')
        lockFile.text.contains('junit:junit:4.12')
        lockFile.text.contains('org.hamcrest:hamcrest-core:1.3')

        where:
        facet       | plugin             | setParentSourceSet
        'integTest' | 'nebula.integtest' | false
//        'smokeTest' | 'nebula.facet' | true
//        'examples'  | 'nebula.facet' | true
    }

    def 'fails when generating Nebula locks and writing core locks together'() {
        when:
        def result = runTasksAndFail('dependencies', '--write-locks', 'generateLock', 'saveLock')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compile.lockfile')
        lockFile.text.contains('test.nebula:a:1.1.0')
        lockFile.text.contains('test.nebula:b:1.1.0')

        result.output.contains("> Task :generateLock FAILED")
    }

    @Unroll
    def 'fail if legacy global lock is present with core lock when running #task with error at task level'() {
        given:
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            """

        new File(projectDir, 'global.lock').text = """{}"""

        when:
        def result = runTasksAndFail(task)

        then:
        result.output.contains("Legacy global locks are not supported with core locking")
        result.output.contains("> Task :${task} FAILED")
        assertNoErrorsOnAParticularBuildLine(result.output)

        where:
        task << ['generateGlobalLock', 'updateGlobalLock']
    }

    def 'fail if legacy global lock is present with core lock when running non-locking tasks with error at plugin level'() {
        given:
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            """

        def legacyGlobalLockFile = new File(projectDir, 'global.lock')
        legacyGlobalLockFile.text = """{}"""

        when:
        def result = runTasksAndFail('dependencies')

        then:
        result.output.contains("Legacy global locks are not supported with core locking")
        assertFailureOccursAtPluginLevel(result.output)
        legacyGlobalLockFile.exists()
    }

    def 'fail if legacy lock is present with core lock when running non-locking tasks with error at plugin level'() {
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

        when:
        def result = runTasksAndFail('dependencies')

        then:
        result.output.contains("Legacy locks are not supported with core locking")
        result.output.contains("If you wish to migrate with the current locked dependencies")
        assertFailureOccursAtPluginLevel(result.output)
        legacyLockFile.exists()
    }

    def 'fail if legacy lock is present with core lock when running locking task with error at task level'() {
        given:
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            """

        when:
        def result = runTasksAndFail('generateLock')

        then:
        result.output.contains("generateLock is not supported with core locking")
        result.output.contains("migration with `./gradlew ${DependencyLockPlugin.MIGRATE_TO_CORE_LOCK_TASK_NAME}`")
        result.output.contains("> Task :generateLock FAILED")
        assertNoErrorsOnAParticularBuildLine(result.output)
    }

    def 'fail if legacy lock is present with core lock when running updateLock with error at task level'() {
        given:
        buildFile.text = """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            """

        when:
        def result = runTasksAndFail('updateLock')

        then:
        result.output.contains("updateLock is not supported with core locking")
        result.output.contains("Please use `./gradlew dependencies --update-locks group1:module1,group2:module2`")
        result.output.contains("> Task :updateLock FAILED")
        assertNoErrorsOnAParticularBuildLine(result.output)
    }

    private static void assertNoErrorsOnAParticularBuildLine(String text) {
        assert !text.contains("* Where:")
    }

    private static void assertFailureOccursAtPluginLevel(String text) {
        assert text.contains("Failed to apply plugin [id 'nebula.dependency-lock']")
    }

}
