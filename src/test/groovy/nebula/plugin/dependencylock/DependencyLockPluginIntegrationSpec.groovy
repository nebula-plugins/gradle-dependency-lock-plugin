package nebula.plugin.dependencylock

import nebula.plugin.BaseIntegrationTestKitSpec
import nebula.plugin.dependencylock.dependencyfixture.Fixture

class DependencyLockPluginIntegrationSpec extends BaseIntegrationTestKitSpec {
    def setupSpec() {
        Fixture.createFixtureIfNotCreated()
    }

    def 'eachDependency wins over force'() {
        buildFile << """\
            plugins {
                id 'java'
            }

            repositories { maven { url '${Fixture.repo}' } }

            dependencies {
                implementation 'test.example:foo:latest.release'
            }

            configurations.all {
                resolutionStrategy {
                    eachDependency { details ->
                        if (details.requested.group == 'test.example' && details.requested.name == 'foo') {
                            details.useTarget group: details.requested.group, name: details.requested.name, version: '1.0.1'
                        }
                    }
                    force 'test.example:foo:1.0.0'
                }
            }
        """.stripIndent()

        when:
        def result = runTasks('dependencies')

        then:
        result.output.contains('\\--- test.example:foo:latest.release -> 1.0.1\n')
    }

    def 'able to modify configuration in beforeResolve block'() {
        buildFile << """
            plugins {
                id 'java'
            }

            repositories { maven { url '${Fixture.repo}' } }

            configurations.all { configuration ->
                incoming.beforeResolve {
                    configuration.resolutionStrategy {
                        force 'test.example:foo:1.0.0'
                        eachDependency { details ->
                            if (details.requested.name == 'bar') {
                                details.useVersion '1.0.0'
                            }
                        }
                    }
                }
            }

            dependencies {
                implementation 'test.example:foo:latest.release'
                implementation 'test.example:bar:latest.release'
            }
            """.stripIndent()

        when:
        def result = runTasks('dependencies')

        then:
        result.output.contains('+--- test.example:foo:latest.release -> 1.0.0\n')
        result.output.contains('\\--- test.example:bar:latest.release -> 1.0.0\n')
    }
}
