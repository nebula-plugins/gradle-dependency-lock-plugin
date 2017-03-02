package nebula.plugin.dependencylock.tasks

import groovy.json.JsonSlurper
import nebula.plugin.dependencylock.dependencyfixture.Fixture
import nebula.plugin.dependencylock.model.GradleDependency
import nebula.plugin.dependencylock.wayback.WaybackProvider
import nebula.test.ProjectSpec
import org.gradle.api.artifacts.Configuration

class GenerateLockTaskWithWaybackSpec extends ProjectSpec {
    final String taskName = 'generateLock'
    GenerateLockTask task

    def setup() {
        Fixture.createFixtureIfNotCreated()

        project.with {
            apply plugin: 'java'
            repositories { maven { url Fixture.repo } }
            configurations { integTestCompile }
            dependencies {
                compile 'test.example:foo:2.+'
                testCompile 'test.example:baz:1.+'
            }
        }

        task = project.tasks.create(taskName, GenerateLockTask)
        task.configurationNames = [ 'compile', 'testCompile', 'integTestCompile' ]
        task.waybackProvider = new WaybackProvider(project) {
            @Override
            Set<GradleDependency> wayback(String selector, Configuration configuration) {
                if(configuration.name == 'compile') {
                    [new GradleDependency('test.example', 'foo', 'WAYBACK')] as Set
                }
                else if(configuration.name == 'testCompile') {
                    [new GradleDependency('test.example', 'baz', 'WAYBACK')] as Set
                }
                else []
            }
        }

        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        project.buildDir.mkdirs()

        // simulate user input via -PwaybackTo=previous with an extension property
        project.ext.waybackTo = 'previous'
    }

    def 'lock both configurations using wayback data'() {
        when:
        task.execute()
        def locks = new JsonSlurper().parse(task.dependenciesLock)

        then:
        locks.compile.'test.example:foo'.locked == 'WAYBACK'
        locks.testCompile.'test.example:baz'.locked == 'WAYBACK'
    }

    def 'update previously locked configurations using wayback data'() {
        setup:
        task.dependenciesLock.text = '''
            |{
            |    "testCompile": {
            |        "test.example:baz": {
            |            "locked": "ORIGINAL",
            |            "requested": "1.+"
            |        }
            |    }
            |}
        '''.stripMargin()

        when:
        task.execute()
        def locks = new JsonSlurper().parse(task.dependenciesLock)

        then:
        locks.testCompile.'test.example:baz'.locked == 'WAYBACK'
    }

    def "don't update previously locked configurations that have no wayback input"() {
        setup:
        task.dependenciesLock.text = '''
            |{
            |    "integTestCompile": {
            |        "test.example:baz": {
            |            "locked": "ORIGINAL",
            |            "requested": "1.+"
            |        }
            |    }
            |}
        '''.stripMargin()

        when:
        task.execute()
        def locks = new JsonSlurper().parse(task.dependenciesLock)

        then:
        // this is a configuration for which our notional wayback provider does not have any information,
        // leave existing locks in place
        locks.integTestCompile.'test.example:baz'.locked == 'ORIGINAL'
    }
}
