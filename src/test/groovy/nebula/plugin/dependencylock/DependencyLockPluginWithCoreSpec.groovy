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
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compile.lockfile').text
        lockFile.contains('test.nebula:a:1.1.0')
        lockFile.contains('test.nebula:b:1.1.0')
    }

    def 'fails when generating Nebula locks and writing core locks together'() {
        when:
        def result = runTasksAndFail('dependencies', '--write-locks', 'generateLock', 'saveLock')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compile.lockfile').text
        lockFile.contains('test.nebula:a:1.1.0')
        lockFile.contains('test.nebula:b:1.1.0')

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
