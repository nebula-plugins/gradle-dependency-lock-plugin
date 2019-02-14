package nebula.plugin.dependencylock

import nebula.plugin.dependencylock.util.LockGenerator
import nebula.test.IntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import spock.lang.Unroll

class DependencyLockPluginWithCoreSpec extends IntegrationTestKitSpec {
    def expectedLocks = [
            'compile.lockfile', 'archives.lockfile', 'testCompileClasspath.lockfile', 'compileOnly.lockfile',
            'annotationProcessor.lockfile', 'runtime.lockfile', 'compileClasspath.lockfile', 'testCompile.lockfile',
            'default.lockfile', 'testAnnotationProcessor.lockfile', 'testRuntime.lockfile',
            'testRuntimeClasspath.lockfile', 'testCompileOnly.lockfile', 'runtimeClasspath.lockfile'
    ] as String[]
    def expectedNebulaLockText = LockGenerator.duplicateIntoConfigs(
            '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.0.0"
                },
                "test.nebula:b": {
                    "locked": "1.1.0",
                    "requested": "1.1.0"
                }'''.stripIndent())

    def setup() {
        keepFiles = true
        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.coreLockingSupport=true"

        settingsFile << """\
            rootProject.name = '${getProjectDir().getName().replaceAll(/_\d+/, '')}'
        """.stripIndent()

        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:a:1.0.0')
                .addModule('test.nebula:a:1.1.0')
                .addModule('test.nebula:b:1.0.0')
                .addModule('test.nebula:b:1.1.0')
                .build()
        def mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen")
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
                compile 'test.nebula:a:1.0.0'
                compile 'test.nebula:b:1.1.0'
            }
        """.stripIndent()
    }

    def 'generate core lock file'() {
        when:
        def result = runTasks('dependencies', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compile.lockfile').text
        lockFile.contains('test.nebula:a:1.0.0')
        lockFile.contains('test.nebula:b:1.1.0')
    }

    def 'migrate to core lock when legacy lock is present and writing locks'() {
        given:
        def legacyLockFile = new File(projectDir, 'dependencies.lock')
        legacyLockFile.text = expectedNebulaLockText

        when:
        def result = runTasks('dependencies', '--write-locks')

        then:
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compile.lockfile').text
        lockFile.contains('test.nebula:a:1.0.0')
        lockFile.contains('test.nebula:b:1.1.0')

        !legacyLockFile.exists()
    }

    def 'migrate to core lock when legacy lock is present and writing locks with custom task'() {
        given:
        buildFile << '''
            task resolveAndLockAll {
                doFirst {
                    assert gradle.startParameter.writeDependencyLocks
                }
                doLast {
                    configurations.findAll {
                        // Add any custom filtering on the configurations to be resolved
                        it.canBeResolved
                    }.each { it.resolve() }
                }
            }'''.stripIndent()

        def legacyLockFile = new File(projectDir, 'dependencies.lock')
        legacyLockFile.text = expectedNebulaLockText

        when:
        def result = runTasks('resolveAndLockAll', '--write-locks')

        then:
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compile.lockfile').text
        lockFile.contains('test.nebula:a:1.0.0')
        lockFile.contains('test.nebula:b:1.1.0')

        !legacyLockFile.exists()
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

    @Unroll
    def 'fail if legacy global lock is present with core lock when running #taskGroup with error at plugin level'() {
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
        def result = runTasksAndFail(*tasks)

        then:
        result.output.contains("Legacy global locks are not supported with core locking")
        assertFailureOccursAtPluginLevel(result.output)
        legacyGlobalLockFile.exists()

        where:
        taskGroup                    | tasks
        'dependencies & write locks' | ['dependencies', '--write-locks']
        'clean build'                | ['clean', 'build']
    }

    def 'fail if legacy lock is present with core lock and not writing locks with error at plugin level'() {
        given:
        def legacyLockFile = new File(projectDir, 'dependencies.lock')
        legacyLockFile.text = expectedNebulaLockText

        when:
        def result = runTasksAndFail('dependencies')

        then:
        result.output.contains("Legacy locks are not supported with core locking")
        result.output.contains("If you wish to migrate with the current locked dependencies")
        assertFailureOccursAtPluginLevel(result.output)
        legacyLockFile.exists()
    }

    def 'fail if legacy lock is present with core lock when running generateLock with error at task level'() {
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
        result.output.contains("Please use `./gradlew dependencies --write-locks`")
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
