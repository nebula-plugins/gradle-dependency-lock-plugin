package nebula.plugin.dependencylock

import nebula.test.ProjectSpec

class DependencyLockReaderSpec extends ProjectSpec {
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
                        "locked": "1.6.5",
                        "requested": "1.+"
                    }
                }
            }
            '''.stripIndent()

        when:
        def map = reader.readLocks(project.configurations.compile, globalLock, ['test:baz'])

        then:
        noExceptionThrown()
    }
}
