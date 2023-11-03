package nebula.plugin.dependencylock.tasks

import nebula.plugin.dependencylock.util.LockGenerator
import nebula.test.ProjectSpec
import spock.lang.Unroll

class DiffLockTaskSpec extends ProjectSpec {
    def 'should diff single project no skew between configurations'() {
        given:
        def existingLock = new File(projectDir, 'dependencies.lock')
        existingLock.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0"
                }
                '''.stripIndent())

        def buildDir = new File(projectDir, "build")
        buildDir.mkdirs()
        def newLock = new File(buildDir, "dependencies.lock")
        newLock.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.1.0"
                }
                '''.stripIndent())
        def task = project.tasks.register("diffLock", DiffLockTask)
        task.configure {
            it.existingLockFile.set(existingLock)
            it.updatedLockFile.set(newLock)
            it.outputFile.set(getDiffFile())
        }

        when:
        def realizedTask = task.get()
        realizedTask.diffLocks()

        then:
        String expected = '''\
            updated:
              test.nebula:a: 1.0.0 -> 1.1.0
            '''.stripIndent()
        realizedTask.outputFile.get().text == expected
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
                    "locked": "1.0.0"
                }
                '''.stripIndent())
        def task = project.tasks.register("diffLock", DiffLockTask)
        task.configure {
            it.existingLockFile.set(existingLock)
            it.updatedLockFile.set(newLock)
            it.outputFile.set(getDiffFile())
        }

        when:
        def realizedTask = task.get()
        realizedTask.diffLocks()

        then:
        String expected = '''\
            new:
              test.nebula:a: 1.0.0
            '''.stripIndent()
        realizedTask.outputFile.get().text == expected
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
                    "locked": "1.0.0"
                }
                '''.stripIndent())
        def task = project.tasks.register("diffLock", DiffLockTask)
        task.configure {
            it.existingLockFile.set(existingLock)
            it.updatedLockFile.set(newLock)
            it.outputFile.set(getDiffFile())
        }

        when:
        def realizedTask = task.get()
        realizedTask.diffLocks()

        then:
        realizedTask.outputFile.get().text == '''\
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
                    "locked": "1.0.0"
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
            it.existingLockFile.set(existingLock)
            it.updatedLockFile.set(newLock)
            it.outputFile.set(getDiffFile())
        }

        when:
        def realizedTask = task.get()
        realizedTask.diffLocks()

        then:
        realizedTask.outputFile.get().text == '''\
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
                    "locked": "1.0.0"
                }
                '''.stripIndent(), ['compile', 'compileClasspath', 'default', 'runtime', 'runtimeClasspath'],
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0"
                },
                "test.nebula:testlib": {
                    "locked": "2.0.0"
                }
                '''.stripIndent(), ['testCompile', 'testCompileClasspath', 'testRuntime', 'testRuntimeClasspath'])

        def buildDir = new File(projectDir, "build")
        buildDir.mkdirs()
        def newLock = new File(buildDir, "dependencies.lock")
        newLock.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.1.0"
                }
                '''.stripIndent(), ['compile', 'compileClasspath', 'default', 'runtime', 'runtimeClasspath'],
                '''\
                "test.nebula:a": {
                    "locked": "1.1.0"
                },
                "test.nebula:testlib": {
                    "locked": "2.0.2"
                }
                '''.stripIndent(), ['testCompile', 'testCompileClasspath', 'testRuntime', 'testRuntimeClasspath'])
        def task = project.tasks.register("diffLock", DiffLockTask)
        task.configure {
            it.existingLockFile.set(existingLock)
            it.updatedLockFile.set(newLock)
            it.outputFile.set(getDiffFile())
        }

        when:
        def realizedTask = task.get()
        realizedTask.diffLocks()

        then:
        realizedTask.outputFile.get().text == '''\
            updated:
              test.nebula:a: 1.0.0 -> 1.1.0
              test.nebula:testlib: 2.0.0 -> 2.0.2
            '''.stripIndent()
    }

    @Unroll
    def 'should handle inconsistent configurations'() {
        given:
        def existingLock = new File(projectDir, 'dependencies.lock')
        existingLock.text = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly(
                '''\
                "test.nebula:a": {
                    "locked": "1.0.0"
                }
                '''.stripIndent())

        def buildDir = new File(projectDir, "build")
        buildDir.mkdirs()
        def newLock = new File(buildDir, "dependencies.lock")
        newLock.text = LockGenerator.duplicateIntoConfigs(
                '''\
                "test.nebula:a": {
                    "locked": "1.1.0"
                }
                '''.stripIndent(), ['compileClasspath', 'runtimeClasspath'],
                '''\
                "test.nebula:a": {
                    "locked": "1.1.1"
                }
                '''.stripIndent(), ['testCompileClasspath', 'testRuntimeClasspath'])
        def task = project.tasks.register("diffLock", DiffLockTask)
        task.configure {
            it.existingLockFile.set(existingLock)
            it.updatedLockFile.set(newLock)
            it.outputFile.set(getDiffFile())
        }

        when:
        def realizedTask = task.get()
        realizedTask.diffLocks()

        then:
        String expected = '''\
            inconsistent:
              test.nebula:a:
                1.0.0 -> 1.1.0 [compileClasspath,runtimeClasspath]
                1.0.0 -> 1.1.1 [testCompileClasspath,testRuntimeClasspath]
            '''.stripIndent()
        realizedTask.outputFile.get().text == expected
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
            it.existingLockFile.set(existingLock)
            it.updatedLockFile.set(newLock)
            it.outputFile.set(getDiffFile())
        }

        when:
        def realizedTask = task.get()
        realizedTask.diffLocks()

        then:
        realizedTask.outputFile.get().text == '''\
            --no updated locks to diff--
            '''.stripIndent()
    }

    private File getDiffFile() {
        File dependencyLockFolder = new File(project.layout.buildDirectory.getAsFile().get(), "dependency-lock")
        dependencyLockFolder.mkdirs()
        return new File(dependencyLockFolder, "lockdiff.txt")
    }
}
