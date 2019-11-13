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
import org.gradle.util.GradleVersion
import spock.lang.IgnoreIf

/**
 * Tests resolution of changing modules when used with Gradle core locking
 * mavenLocal is not used as a repository as it is a local file repository and will bypass the dependency cache
 * These tests resolve dependencies via HTTP to verify caching behavior, such as cacheChangingModulesFor
 */
class ChangingModulesCacheSpec extends AbstractCachingAndCoreLockingSpec {
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

    //Earlier versions of Gradle have different behavior. We are not using core lock yet so it stops making sense to test old behavior
    //Test will be enable only for newer Gradle versions.
    @IgnoreIf( { GradleVersion.current().baseVersion < GradleVersion.version("6.0") } )
    def 'changing modules with updated transitive dependencies cause resolution failure until dependencies are updated'() {
        given:
        setupBaseDependencyAndMockedResponses(uniqueId, "changing")

        when:
        def result = runTasks('dependencies', '--write-locks', '--configuration', 'compileClasspath')

        then:
        result.output.contains("\\--- test.nebula:a-$uniqueId:1.0.0")

        when:
        updateChangingDependencyAndMockedResponses(uniqueId)

        def dependenciesResult = runTasks('dependencies', '--configuration', 'compileClasspath')

        then:
        dependenciesResult.output.contains("\\--- test.nebula:a-$uniqueId:1.0.0")

        when:
        runTasks('dependencyInsight', '--dependency', 'test.nebula:a', '--configuration', 'compileClasspath', '--refresh-dependencies')

        then:
        //TODO Gradle 6.1 snapshot adds strange "Unknown" reason into selection reasons
        dependenciesResult.output.contains("\\--- test.nebula:a-$uniqueId:1.0.0")
    }

}
