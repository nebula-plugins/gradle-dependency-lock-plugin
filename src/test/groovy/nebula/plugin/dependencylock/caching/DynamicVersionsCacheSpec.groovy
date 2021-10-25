package nebula.plugin.dependencylock.caching

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.stubbing.ServeEvent

/**
 * Tests resolution of dynamic versions when used with Gradle core locking
 * mavenLocal is not used as a repository as it is a local file repository and will bypass the dependency cache
 * These tests resolve dependencies via HTTP to verify caching behavior, such as cacheDynamicVersionsFor
 */
class DynamicVersionsCacheSpec extends AbstractCachingAndDependencyLockingFeatureFlagsSpec {
    def setup() {
        buildFile << """
            dependencies {
                implementation 'test.dynamic:z-$uniqueId:latest.release'
            }
            """.stripIndent()
    }

    def 'dynamic versions should cache for 0 seconds when resolving and locking'() {
        given:
        setupBaseDependencyAndMockedResponses(uniqueId, "dynamic")

        when:
        def result = runTasks('dependencies', '--write-locks', '--configuration', 'compileClasspath',)

        updateDynamicDependencyAndMockedResponses(uniqueId)

        then:
        result.output.contains("\\--- test.nebula:a-$uniqueId:1.0.0")

        def updatedLockedResults = runTasks('dependencies', '--write-locks', '--configuration', 'compileClasspath') // should cache for 0 seconds

        then:
        updatedLockedResults.output.contains("\\--- test.nebula:a-$uniqueId:1.1.1")

        List<ServeEvent> allServeEvents = WireMock.getAllServeEvents()
        WireMock.verify(WireMock.exactly(2), WireMock.getRequestedFor(WireMock.urlEqualTo("/${parseGroup('test.dynamic')}/z-${uniqueId}/maven-metadata.xml")))
        WireMock.verify(WireMock.exactly(1), WireMock.getRequestedFor(WireMock.urlEqualTo('/' + filePathFor('test.dynamic', "z-$uniqueId", '1.0.0', 'pom'))))
        WireMock.verify(WireMock.exactly(1), WireMock.getRequestedFor(WireMock.urlEqualTo('/' + filePathFor('test.nebula', "a-$uniqueId", '1.0.0', 'pom'))))

        WireMock.verify(WireMock.exactly(1), WireMock.headRequestedFor(WireMock.urlEqualTo("/${parseGroup('test.dynamic')}/z-${uniqueId}/maven-metadata.xml")))

        WireMock.verify(WireMock.exactly(1), WireMock.getRequestedFor(WireMock.urlEqualTo('/' + filePathFor('test.dynamic', "z-$uniqueId", '2.0.0', 'pom'))))
        WireMock.verify(WireMock.exactly(1), WireMock.getRequestedFor(WireMock.urlEqualTo('/' + filePathFor('test.nebula', "a-$uniqueId", '1.1.1', 'pom'))))
        assert allServeEvents.size() == 7
    }
}
