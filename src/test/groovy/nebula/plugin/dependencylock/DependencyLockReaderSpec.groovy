package nebula.plugin.dependencylock

import org.gradle.api.GradleException

class DependencyLockReaderSpec extends FreshProjectSpec {

    def 'parseLockFile with null JSON content throws GradleException rather than silently treating locks as empty'() {
        project.plugins.apply('java')
        DependencyLockReader reader = new DependencyLockReader(project)
        File lock = new File(projectDir, 'dependencies.lock')
        lock.text = 'null'

        when:
        reader.readLocks(project.configurations.compileClasspath, lock, [:])

        then:
        def ex = thrown(GradleException)
        ex.message.contains('dependencies.lock')
    }

    def 'read global lock with an extraneous transitive that is not in the lock due to manual editing'() {
        project.plugins.apply('java')
        DependencyLockReader reader = new DependencyLockReader(project)
        File globalLock = new File(projectDir, 'myglobal.lock')
        globalLock.text = '''\
            {
                "_global_": {
                    "mytest:foo": {
                        "locked": "1.6.5",
                        "transitive": [
                            "notinlock:bar"
                        ]
                    },
                    "mytest:foobar": {
                        "locked": "1.6.5"
                    }
                }
            }
            '''.stripIndent()

        when:
        reader.readLocks(project.configurations.compileClasspath, globalLock, new HashMap<>(), ['test:baz'])

        then:
        noExceptionThrown()
    }

}
