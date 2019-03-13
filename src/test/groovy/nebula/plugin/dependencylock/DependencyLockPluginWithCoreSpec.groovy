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
    def expectedNebulaLockText = LockGenerator.duplicateIntoConfigs(
            '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                },
                "test.nebula:b": {
                    "locked": "1.1.0",
                    "requested": "1.+"
                }'''.stripIndent())
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

    def 'migration to core locks'() {
        given:
        def legacyLockFile = new File(projectDir, 'dependencies.lock')
        legacyLockFile.text = expectedNebulaLockText

        when:
        def result = runTasks('-PmigrateLocks=true')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compile.lockfile').text
        lockFile.contains('test.nebula:a:1.0.0')
        lockFile.contains('test.nebula:b:1.1.0')

        !legacyLockFile.exists()

        when:
        def dependenciesResult = runTasks('dependencies')

        then:
        dependenciesResult.output.contains('dependency constraint')
        !dependenciesResult.output.contains('FAILED')
    }

    def 'updating migrated locks'() {
        given:
        def depLocksDirectory = new File(projectDir, '/gradle/dependency-locks/')
        if (!depLocksDirectory.mkdirs()) {
            throw new Exception("failed to create directory at ${depLocksDirectory}")
        }
        expectedLocks.each {
            def confLockFile = new File("${depLocksDirectory.path}/${it}")
            confLockFile.createNewFile()
            if (it.contains("compile") || it.contains("runtime")) {
                confLockFile.text = """
                    # This is a file for dependency locking, migrated from Nebula locks.
                    test.nebula:a:1.0.0
                    test.nebula:b:1.1.0
                    """.stripIndent()
            } else {
                confLockFile.text = """
                    # This is a file for dependency locking, migrated from Nebula locks.
                    """.stripIndent()
            }
        }

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
                compile 'test.nebula:a:1.1.0'
                compile 'test.nebula:b:1.+'
            }
        """.stripIndent()

        when:
        def mismatchedDependenciesResult = runTasks('dependencies')

        then:
        mismatchedDependenciesResult.output.contains('FAILED')

        when:
        runTasks('dependencies', '--update-locks', 'test.nebula:a')
        def updatedDependencies = runTasks('dependencies')

        then:
        updatedDependencies.output.contains('a:1.1.0')
        updatedDependencies.output.contains('dependency constraint')
        !updatedDependencies.output.contains('FAILED')
    }

    def 'migration with transitives'() {
        given:
        buildFile << """
dependencies {
    compile 'test.nebula:c:1.+'
}"""
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
        def result = runTasks('-PmigrateLocks=true')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compile.lockfile').text
        lockFile.contains('test.nebula:a:1.0.0')
        lockFile.contains('test.nebula:b:1.1.0')
        lockFile.contains('test.nebula:c:1.0.0')
        lockFile.contains('test.nebula:d:1.0.0')

        !legacyLockFile.exists()
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
                compile 'test.nebula:a:1.0.0'
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
                compile 'test.nebula:c:1.0.0'
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
        def result = runTasks('-PmigrateLocks=true')

        then:
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')

        def sub1ActualLocks = new File(projectDir, 'sub1/gradle/dependency-locks/').list().toList()
        sub1ActualLocks.containsAll(expectedLocks)
        def sub1LockFile = new File(projectDir, 'sub1/gradle/dependency-locks/compile.lockfile').text
        sub1LockFile.contains('test.nebula:a:1.0.0')
        !sub1LegacyLockFile.exists()

        def sub2ActualLocks = new File(projectDir, 'sub2/gradle/dependency-locks/').list().toList()
        sub2ActualLocks.containsAll(expectedLocks)
        def sub2LockFile = new File(projectDir, 'sub2/gradle/dependency-locks/compile.lockfile').text
        sub2LockFile.contains('test.nebula:c:1.0.0')
        sub2LockFile.contains('test.nebula:d:1.0.0')
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
                compile 'test.nebula:a:1.0.0'
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
                compile 'test.nebula:c:1.0.0'
                compile project(":sub1") 
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
        def result = runTasks('-PmigrateLocks=true', '-i')

        then:
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')
        result.output.contains("No locked version for '${projectName}:sub1' to migrate in configuration ':sub2:compile'")

        def sub1ActualLocks = new File(projectDir, 'sub1/gradle/dependency-locks/').list().toList()
        sub1ActualLocks.containsAll(expectedLocks)
        def sub1LockFile = new File(projectDir, 'sub1/gradle/dependency-locks/compile.lockfile').text
        sub1LockFile.contains('test.nebula:a:1.0.0')
        !sub1LegacyLockFile.exists()

        def sub2ActualLocks = new File(projectDir, 'sub2/gradle/dependency-locks/').list().toList()
        sub2ActualLocks.containsAll(expectedLocks)
        def sub2LockFile = new File(projectDir, 'sub2/gradle/dependency-locks/compile.lockfile').text
        sub2LockFile.contains('test.nebula:a:1.0.0')
        sub2LockFile.contains('test.nebula:c:1.0.0')
        sub2LockFile.contains('test.nebula:d:1.0.0')
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
                compile 'test.nebula:a:1.0.0'
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
                compile 'test.nebula:a:1.0.0'
                compile 'test.nebula:c:1.0.0'
                compile project(":sub1") 
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
        def result = runTasks('-PmigrateLocks=true')

        then:
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')

        def sub1ActualLocks = new File(projectDir, 'sub1/gradle/dependency-locks/').list().toList()
        sub1ActualLocks.containsAll(expectedLocks)
        def sub1LockFile = new File(projectDir, 'sub1/gradle/dependency-locks/compile.lockfile').text
        sub1LockFile.contains('test.nebula:a:1.0.0')
        !sub1LegacyLockFile.exists()

        def sub2ActualLocks = new File(projectDir, 'sub2/gradle/dependency-locks/').list().toList()
        sub2ActualLocks.containsAll(expectedLocks)
        def sub2LockFile = new File(projectDir, 'sub2/gradle/dependency-locks/compile.lockfile').text
        sub2LockFile.contains('test.nebula:a:1.0.0')
        sub2LockFile.findAll('test.nebula:a:1.0.0').size() == 1
        sub2LockFile.contains('test.nebula:c:1.0.0')
        sub2LockFile.contains('test.nebula:d:1.0.0')
        !sub2LegacyLockFile.exists()
    }

    def 'previously partial lockfiles must include all dependencies'() {
        given:
        def legacyLockFile = new File(projectDir, 'dependencies.lock')
        legacyLockFile.text = '''
            {
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                }
            }'''.stripIndent()

        when:
        def result = runTasks('-PmigrateLocks=true')

        then:
        result.output.contains('coreLockingSupport feature enabled')
        !result.output.contains('not supported')
        result.output.contains('Migrating legacy locks')
        def actualLocks = new File(projectDir, '/gradle/dependency-locks/').list().toList()

        actualLocks.containsAll(expectedLocks)
        def lockFile = new File(projectDir, '/gradle/dependency-locks/compile.lockfile')
        lockFile.text.contains('test.nebula:a:1.0.0')

        !legacyLockFile.exists()

        when:
        def mismatchedDependenciesResult = runTasks('dependencies')

        then:
        mismatchedDependenciesResult.output.contains('FAILED')

        when:
        runTasks('dependencies', '--update-locks', 'test.nebula:b')
        def updatedDependencies = runTasks('dependencies')

        then:
        updatedDependencies.output.contains('test.nebula:a:1.+ -> 1.0.0')
        updatedDependencies.output.contains('test.nebula:b:1.+ -> 1.1.0')
        updatedDependencies.output.contains('dependency constraint')
        !updatedDependencies.output.contains('FAILED')

        lockFile.text.contains('test.nebula:a:1.0.0')
        lockFile.text.contains('test.nebula:b:1.1.0')
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
                compile 'test.nebula:a:1.0.0'
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
                compile 'test.nebula:c:1.0.0'
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
        def result = runTasksAndFail('-PmigrateLocks=true')

        then:
        result.output.contains("Legacy global locks are not supported with core locking")
        assertFailureOccursAtPluginLevel(result.output)
        legacyGlobalLockFile.exists()
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
        legacyLockFile.text = expectedNebulaLockText

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
        result.output.contains("migration with ${DependencyLockPlugin.MIGRATE_LOCK_TASK_TO_RUN}")
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
