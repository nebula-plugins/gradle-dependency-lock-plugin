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

class OfflineModeSpec extends AbstractCachingAndDependencyLockingFeatureFlagsSpec {
    private static final String OFFLINE_MODE_NOTIFICATION = 'offline mode enabled. Using cached dependencies'

    def setup() {
        buildFile << """
            dependencies {
                implementation 'test.offlineMode:z-$uniqueId:latest.release'
            }
            """.stripIndent()
    }

    def 'offline mode with core locking uses cached dependencies'() {
        given:
        setupBaseDependencyAndMockedResponses(uniqueId, "offlineMode")

        when:
        def result = runTasks('dependencies', '--write-locks', '--configuration', 'compileClasspath')

        then:
        result.output.contains("test.offlineMode:z-$uniqueId:latest.release -> 1.0.0")
        result.output.contains("\\--- test.nebula:a-$uniqueId:1.0.0")
        !result.output.contains(OFFLINE_MODE_NOTIFICATION)

        def lockFile = new File(projectDir, 'gradle.lockfile')
        lockFile.exists()

        List<ServeEvent> allServeEvents = WireMock.getAllServeEvents()
        WireMock.verify(WireMock.exactly(1), WireMock.getRequestedFor(WireMock.urlEqualTo("/${parseGroup('test.offlineMode')}/z-${uniqueId}/maven-metadata.xml")))
        WireMock.verify(WireMock.exactly(1), WireMock.getRequestedFor(WireMock.urlEqualTo('/' + filePathFor('test.offlineMode', "z-$uniqueId", '1.0.0', 'pom'))))
        WireMock.verify(WireMock.exactly(1), WireMock.getRequestedFor(WireMock.urlEqualTo('/' + filePathFor('test.nebula', "a-$uniqueId", '1.0.0', 'pom'))))
        assert allServeEvents.size() == 3

        when:
        def offlineResults = runTasks('dependencies', '--configuration', 'compileClasspath', '--offline')

        then:
        offlineResults.output.contains("test.offlineMode:z-$uniqueId:latest.release -> 1.0.0")
        offlineResults.output.contains("\\--- test.nebula:a-$uniqueId:1.0.0")
        offlineResults.output.contains(OFFLINE_MODE_NOTIFICATION)

        List<ServeEvent> allServeEventsUpdated = WireMock.getAllServeEvents()
        assert allServeEventsUpdated.size() == 3 // there are no new events because offline mode used the cache
    }
}
