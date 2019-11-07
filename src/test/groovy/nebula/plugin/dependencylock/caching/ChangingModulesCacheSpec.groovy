/**
 *
 *  Copyright 2019 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package nebula.plugin.dependencylock.caching

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

/**
 * Tests resolution of changing modules when used with Gradle core locking
 * mavenLocal is not used as a repository as it is a local file repository and will bypass the dependency cache
 * These tests resolve dependencies via HTTP to verify caching behavior, such as cacheChangingModulesFor
 */
class ChangingModulesCacheSpec extends AbstractCachingAndCoreLockingSpec {
    private static final Logger LOGGER = Logging.getLogger(ChangingModulesCacheSpec.class)

    def setup() {
        buildFile << """
            dependencies {
                implementation group: 'test.changing', name:'z-$uniqueId', version: '1.0.0', changing: true
            }
            """.stripIndent()
    }

    def 'changing modules should cache for 0 seconds when resolving and locking'() {
        given:
        setupBaseDependencyAndMockedResponses(uniqueId, "changing")

        when:
        def result = runTasks('dependencies', '--write-locks', '--configuration', 'compileClasspath',)

        then:
        result.output.contains("\\--- test.nebula:a-$uniqueId:1.0.0")

        when:
        updateChangingDependencyAndMockedResponses(uniqueId)

        def updatedLockedResults = runTasks('dependencies', '--write-locks', '--configuration', 'compileClasspath') // should cache for 0 seconds

        then:
        List<ServeEvent> allServeEvents = WireMock.getAllServeEvents()
        WireMock.verify(WireMock.exactly(2), WireMock.getRequestedFor(WireMock.urlEqualTo('/' + filePathFor('test.changing', "z-$uniqueId", '1.0.0', 'pom'))))
        WireMock.verify(WireMock.exactly(1), WireMock.getRequestedFor(WireMock.urlEqualTo('/' + filePathFor('test.changing', "z-$uniqueId", '1.0.0', 'pom.sha1'))))
        WireMock.verify(WireMock.exactly(1), WireMock.headRequestedFor(WireMock.urlEqualTo('/' + filePathFor('test.changing', "z-$uniqueId", '1.0.0', 'pom'))))

        WireMock.verify(WireMock.exactly(1), WireMock.getRequestedFor(WireMock.urlEqualTo('/' + filePathFor('test.nebula', "a-$uniqueId", '1.0.0', 'pom'))))
        WireMock.verify(WireMock.exactly(1), WireMock.getRequestedFor(WireMock.urlEqualTo('/' + filePathFor('test.nebula', "a-$uniqueId", '1.1.1', 'pom'))))
        assert allServeEvents.size() == 6

        updatedLockedResults.output.contains("\\--- test.nebula:a-$uniqueId:1.1.1")
    }

    def 'changing modules with updated transitive dependencies cause resolution failure until dependencies are updated'() {
        given:
        setupBaseDependencyAndMockedResponses(uniqueId, "changing")

        when:
        LOGGER.warn('============ List dependencies and write initial locks ============')
        def result = runTasks('dependencies', '--write-locks', '--configuration', 'compileClasspath')

        then:
        result.output.contains("\\--- test.nebula:a-$uniqueId:1.0.0")

        when:
        updateChangingDependencyAndMockedResponses(uniqueId)

        LOGGER.warn('============ List dependencies ============')
        def dependenciesResult = runTasks('dependencies', '--configuration', 'compileClasspath')

        then:
        dependenciesResult.output.contains("\\--- test.nebula:a-$uniqueId:1.0.0")

        when:
        setupMockedResponsesForRefreshingDependencies(uniqueId)

        LOGGER.warn('============ List dependencies with refresh ============')
        def refreshDependenciesResult = runTasksAndFail('dependencyInsight', '--dependency', 'test.nebula', '--refresh-dependencies') // this shows unexpected changes from Gradle 5.6.4 to 6.0.0-rc.x
//        def refreshDependenciesResult = runTasksAndFail('dependencies', '--configuration', 'compileClasspath', '--refresh-dependencies')

        then:
        List<ServeEvent> allServeEvents = WireMock.getAllServeEvents()
        WireMock.verify(WireMock.exactly(2), WireMock.getRequestedFor(WireMock.urlEqualTo('/' + filePathFor('test.changing', "z-$uniqueId", '1.0.0', 'pom'))))
        WireMock.verify(WireMock.exactly(1), WireMock.getRequestedFor(WireMock.urlEqualTo('/' + filePathFor('test.changing', "z-$uniqueId", '1.0.0', 'pom.sha1'))))
        WireMock.verify(WireMock.exactly(1), WireMock.headRequestedFor(WireMock.urlEqualTo('/' + filePathFor('test.changing', "z-$uniqueId", '1.0.0', 'pom'))))

        WireMock.verify(WireMock.exactly(1), WireMock.getRequestedFor(WireMock.urlEqualTo('/' + filePathFor('test.nebula', "a-$uniqueId", '1.0.0', 'pom'))))
        WireMock.verify(WireMock.exactly(1), WireMock.getRequestedFor(WireMock.urlEqualTo('/' + filePathFor('test.nebula', "a-$uniqueId", '1.1.1', 'pom'))))
        assert allServeEvents.size() == 6


        refreshDependenciesResult.output.contains("""
Execution failed for task ':dependencies'.
> Failed to resolve the following dependencies:
    1. Failed to resolve 'test.nebula:a-$uniqueId:1.1.1' for project '$projectName\'
    2. Failed to resolve 'test.nebula:a-$uniqueId:{strictly 1.0.0}' for project '$projectName\'
""")
    }

}
