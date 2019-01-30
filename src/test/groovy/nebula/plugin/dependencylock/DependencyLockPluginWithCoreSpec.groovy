package nebula.plugin.dependencylock

import nebula.test.IntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator

class DependencyLockPluginWithCoreSpec extends IntegrationTestKitSpec {

    def setup() {
        debug = true
        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.coreLockingSupport=true"

        settingsFile << '''\
            rootProject.name = 'locktest'
        '''.stripIndent()
    }

    def 'core lock file is being generated'() {
        given:
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

        when:
        def result = runTasks('dependencies', '--write-locks')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()
        def expectedLocks = [
                'compile.lockfile', 'archives.lockfile', 'testCompileClasspath.lockfile', 'compileOnly.lockfile',
                'annotationProcessor.lockfile', 'runtime.lockfile', 'compileClasspath.lockfile', 'testCompile.lockfile',
                'default.lockfile', 'testAnnotationProcessor.lockfile', 'testRuntime.lockfile',
                'testRuntimeClasspath.lockfile', 'testCompileOnly.lockfile', 'runtimeClasspath.lockfile'
        ] as String[]
        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compile.lockfile').text
        lockFile.contains('test.nebula:a:1.0.0')
        lockFile.contains('test.nebula:b:1.1.0')
    }

    def 'fail with core lock if legacy lock is present'() {
        given:
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            """

        new File(projectDir, 'dependencies.lock').text = """{}"""

        when:
        def result = runTasksAndFail('dependencies', '--write-locks')

        then:
        result.output.contains("Legacy locks are not supported with core locking")

    }

    def 'fail with core lock if legacy global lock is present'() {
        given:
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            """

        new File(projectDir, 'global.lock').text = """{}"""

        when:
        def result = runTasksAndFail('dependencies', '--write-locks')

        then:
        result.output.contains("Legacy global locks are not supported with core locking")

    }

    def 'fail with core lock if if you try to use legacy generateLock'() {
        given:
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            """

        when:
        def result = runTasksAndFail('generateLock')

        then:
        result.output.contains("generateLock is not supported with core locking")

    }

    def 'fail with core lock if if you try to use legacy updateLock'() {
        given:
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            """

        when:
        def result = runTasksAndFail('updateLock')

        then:
        result.output.contains("updateLock is not supported with core locking")

    }

}
