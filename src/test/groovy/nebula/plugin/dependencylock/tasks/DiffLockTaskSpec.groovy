package nebula.plugin.dependencylock.tasks

import nebula.plugin.dependencylock.util.LockGenerator
import nebula.test.ProjectSpec

class DiffLockTaskSpec extends ProjectSpec {
    def 'should diff single project no skew between configurations'() {
        given:
        def existingLock = new File(projectDir, 'dependencies.lock')
        existingLock.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                }
                '''.stripIndent())

        def buildDir = new File(projectDir, "build")
        buildDir.mkdirs()
        def newLock = new File(buildDir, "dependencies.lock")
        newLock.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.1.0",
                    "requested": "1.+"
                }
                '''.stripIndent())
        def task = project.tasks.register("diffLock", DiffLockTask)
        task.configure {
            it.existingLockFile = existingLock
            it.updatedLockFile = newLock
        }

        when:
        def realizedTask = task.get()
        realizedTask.diffLocks()

        then:
        String expected = '''\
            updated:
              test.nebula:a: 1.0.0 -> 1.1.0
            '''.stripIndent()
        realizedTask.diffFile.text == expected
    }

    def 'should handle new dependency'() {
        given:
        def existingLock = new File(projectDir, 'dependencies.lock')
        existingLock.text = LockGenerator.duplicateIntoConfigs(
                '''\
                '''.stripIndent())

        def buildDir = new File(projectDir, "build")
        buildDir.mkdirs()
        def newLock = new File(buildDir, "dependencies.lock")
        newLock.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                }
                '''.stripIndent())
        def task = project.tasks.register("diffLock", DiffLockTask)
        task.configure {
            it.existingLockFile = existingLock
            it.updatedLockFile = newLock
        }

        when:
        def realizedTask = task.get()
        realizedTask.diffLocks()

        then:
        String expected = '''\
            new:
              test.nebula:a: 1.0.0
            '''.stripIndent()
        realizedTask.diffFile.text == expected
    }

    def 'should handle no existing locks'() {
        given:
        def existingLock = new File(projectDir, 'dependencies.lock')

        def buildDir = new File(projectDir, "build")
        buildDir.mkdirs()
        def newLock = new File(buildDir, "dependencies.lock")
        newLock.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                }
                '''.stripIndent())
        def task = project.tasks.register("diffLock", DiffLockTask)
        task.configure {
            it.existingLockFile = existingLock
            it.updatedLockFile = newLock
        }

        when:
        def realizedTask = task.get()
        realizedTask.diffLocks()

        then:
        realizedTask.diffFile.text == '''\
            new:
              test.nebula:a: 1.0.0
            '''.stripIndent()
    }

    def 'should handle removed dependency'() {
        given:
        def existingLock = new File(projectDir, 'dependencies.lock')
        existingLock.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                }
                '''.stripIndent())

        def buildDir = new File(projectDir, "build")
        buildDir.mkdirs()
        def newLock = new File(buildDir, "dependencies.lock")
        newLock.text = LockGenerator.duplicateIntoConfigs(
                '''\
                '''.stripIndent())
        def task = project.tasks.register("diffLock", DiffLockTask)
        task.configure {
            it.existingLockFile = existingLock
            it.updatedLockFile = newLock
        }

        when:
        def realizedTask = task.get()
        realizedTask.diffLocks()

        then:
        realizedTask.diffFile.text == '''\
            removed:
              test.nebula:a
            '''.stripIndent()
    }

    def 'should handle multiple configurations'() {
        given:
        def existingLock = new File(projectDir, 'dependencies.lock')
        existingLock.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                }
                '''.stripIndent(), ['compile', 'compileClasspath', 'default', 'runtime', 'runtimeClasspath'],
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                },
                "test.nebula:testlib": {
                    "locked": "2.0.0",
                    "requested": "2.+"
                }
                '''.stripIndent(), ['testCompile', 'testCompileClasspath', 'testRuntime', 'testRuntimeClasspath'])

        def buildDir = new File(projectDir, "build")
        buildDir.mkdirs()
        def newLock = new File(buildDir, "dependencies.lock")
        newLock.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.1.0",
                    "requested": "1.+"
                }
                '''.stripIndent(), ['compile', 'compileClasspath', 'default', 'runtime', 'runtimeClasspath'],
                '''\
                "test.nebula:a": {
                    "locked": "1.1.0",
                    "requested": "1.+"
                },
                "test.nebula:testlib": {
                    "locked": "2.0.2",
                    "requested": "2.+"
                }
                '''.stripIndent(), ['testCompile', 'testCompileClasspath', 'testRuntime', 'testRuntimeClasspath'])
        def task = project.tasks.register("diffLock", DiffLockTask)
        task.configure {
            it.existingLockFile = existingLock
            it.updatedLockFile = newLock
        }

        when:
        def realizedTask = task.get()
        realizedTask.diffLocks()

        then:
        realizedTask.diffFile.text == '''\
            updated:
              test.nebula:a: 1.0.0 -> 1.1.0
              test.nebula:testlib: 2.0.0 -> 2.0.2
            '''.stripIndent()
    }

    def 'should handle inconsistent configurations'() {
        given:
        def existingLock = new File(projectDir, 'dependencies.lock')
        existingLock.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                }
                '''.stripIndent())

        def buildDir = new File(projectDir, "build")
        buildDir.mkdirs()
        def newLock = new File(buildDir, "dependencies.lock")
        newLock.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.1.0",
                    "requested": "1.+"
                }
                '''.stripIndent(), ['compile', 'compileClasspath', 'default', 'runtime', 'runtimeClasspath'],
                '''\
                "test.nebula:a": {
                    "locked": "1.1.1",
                    "requested": "1.+"
                }
                '''.stripIndent(), ['testCompile', 'testCompileClasspath', 'testRuntime', 'testRuntimeClasspath'])
        def task = project.tasks.register("diffLock", DiffLockTask)
        task.configure {
            it.existingLockFile = existingLock
            it.updatedLockFile = newLock
        }

        when:
        def realizedTask = task.get()
        realizedTask.diffLocks()

        then:
        String expected = '''\
            inconsistent:
              test.nebula:a:
                1.0.0 -> 1.1.0 [compile,compileClasspath,default,runtime,runtimeClasspath]
                1.0.0 -> 1.1.1 [testCompile,testCompileClasspath,testRuntime,testRuntimeClasspath]
            '''.stripIndent()
        realizedTask.diffFile.text == expected
    }

    def 'should handle being run when no new locks exist'() {
        given:
        def existingLock = new File(projectDir, 'dependencies.lock')
        existingLock.text = LockGenerator.duplicateIntoConfigs(
                '''\
                '''.stripIndent())
        def buildDir = new File(projectDir, "build")
        buildDir.mkdirs()
        def newLock = new File(buildDir, "dependencies.lock")

        def task = project.tasks.register("diffLock", DiffLockTask)
        task.configure {
            it.existingLockFile = existingLock
            it.updatedLockFile = newLock
        }

        when:
        def realizedTask = task.get()
        realizedTask.diffLocks()

        then:
        realizedTask.diffFile.text == '''\
            --no updated locks to diff--
            '''.stripIndent()
    }
}
