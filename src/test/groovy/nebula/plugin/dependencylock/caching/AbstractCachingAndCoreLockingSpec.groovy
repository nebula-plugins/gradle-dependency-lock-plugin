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

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.junit.Stubbing
import nebula.test.IntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraph
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder

class AbstractCachingAndCoreLockingSpec extends IntegrationTestKitSpec {
    static WireMockServer wireMockServer
    static WireMock testClient
    static Stubbing wm
    String serverUrl

    def projectName
    def mavenrepo
    File repo
    String uniqueId

    def setupSpec() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            String serverUrl = wireMockServer.url('/').toString()
            println("shutting down the server at ${serverUrl}")
            wireMockServer.stop()
        }
        def options = WireMockConfiguration.wireMockConfig()
                .withRootDirectory("src/test/resources/empty")
                .dynamicPort()

        wireMockServer = new WireMockServer(options)
        wireMockServer.start()
        testClient = new WireMock(wireMockServer.port())
        WireMock.configureFor(wireMockServer.port())
        wm = wireMockServer
    }

    def setup() {
        keepFiles = true
        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.coreLockingSupport=true"

        projectName = getProjectDir().getName().replaceAll(/_\d+/, '')
        settingsFile << """\
            rootProject.name = '${projectName}'
        """.stripIndent()

        serverUrl = wireMockServer.url('/').toString()
        println("wiremock serverUrl: ${serverUrl} for test ${moduleName}")

        repo = new File(projectDir, 'repo')
        repo.mkdirs()

        uniqueId = UUID.randomUUID().toString().substring(0, 5)

        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id 'java'
            }
            repositories {
                maven { 
                    url "$serverUrl"
                    setAllowInsecureProtocol(true)
                    metadataSources {
                        mavenPom()
                        artifact()
                        ignoreGradleMetadataRedirection()
                    }
                }
            }
            """.stripIndent()

        debug = true // if you want to debug with IntegrationTestKit, this is needed
    }

    def cleanupSpec() {
    }

    def cleanup() {
        WireMock.resetToDefault()
    }

    void setupBaseDependencyAndMockedResponses(String uniqueId, String groupIdentifier) {
        DependencyGraph graph = new DependencyGraphBuilder()
                .addModule(new ModuleBuilder("test.$groupIdentifier:z-$uniqueId:1.0.0").addDependency("test.nebula:a-$uniqueId:1.0.0").build())
                .build()
        mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()


        serveMockedArtifactMavenMetadataResponse("test.$groupIdentifier", "z-$uniqueId")

        serveMockedArtifactMetadataResponse("test.$groupIdentifier", "z-$uniqueId", '1.0.0')
        serveMockedJar_Head_Response("test.$groupIdentifier", "z-$uniqueId", '1.0.0')

        serveMockedArtifactMetadataResponse('test.nebula', "a-$uniqueId", '1.0.0')
        serveMockedJar_Head_Response('test.nebula', "a-$uniqueId", '1.0.0')
    }

    void updateChangingDependencyAndMockedResponses(String uniqueId) {
        DependencyGraph updatedGraph = new DependencyGraphBuilder()
                .addModule(new ModuleBuilder("test.changing:z-$uniqueId:1.0.0").addDependency("test.nebula:a-$uniqueId:1.1.1").build())
                .build()
        mavenrepo = new GradleDependencyGenerator(updatedGraph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()

        serveMockedArtifactMetadata_Head_Response('test.changing', "z-$uniqueId", '1.0.0')
        serveMockedArtifactMetadataSha1Response('test.changing', "z-$uniqueId", '1.0.0')
        serveMockedArtifactMetadataResponse('test.changing', "z-$uniqueId", '1.0.0')
        serveMockedJar_Head_Response('test.changing', "z-$uniqueId", '1.0.0')

        serveMockedArtifactMetadataResponse('test.nebula', "a-$uniqueId", '1.1.1')
        serveMockedJar_Head_Response('test.nebula', "a-$uniqueId", '1.1.1')
    }

    void setupMockedResponsesForRefreshingDependencies(String uniqueId) {
        serveMockedArtifactMetadata_Head_Response('test.nebula', "a-$uniqueId", '1.0.0')
        serveMockedArtifactMetadataSha1Response('test.nebula', "a-$uniqueId", '1.0.0')
    }

    void updateDynamicDependencyAndMockedResponses(String uniqueId) {
        DependencyGraph updatedGraph = new DependencyGraphBuilder()
                .addModule(new ModuleBuilder("test.dynamic:z-$uniqueId:2.0.0").addDependency("test.nebula:a-$uniqueId:1.1.1").build())
                .build()
        mavenrepo = new GradleDependencyGenerator(updatedGraph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()

        serveMockedArtifactMavenMetadata_Head_Response("test.dynamic", "z-$uniqueId")
        serveMockedArtifactMavenMetadataResponse("test.dynamic", "z-$uniqueId")

        serveMockedArtifactMetadataResponse('test.dynamic', "z-$uniqueId", '2.0.0')
        serveMockedJar_Head_Response('test.dynamic', "z-$uniqueId", '2.0.0')

        serveMockedArtifactMetadataResponse('test.nebula', "a-$uniqueId", '1.1.1')
        serveMockedJar_Head_Response('test.nebula', "a-$uniqueId", '1.1.1')
    }

    void serveMockedArtifactMetadataResponse(String group, String artifactName, String version) {
        String body = dependencyMetadata(group, artifactName, version)
        assert body != null && body != ''

        WireMock.stubFor(WireMock.get('/' + filePathFor(group, artifactName, version, 'pom'))
                .willReturn(
                        WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/xml")
                                .withBody(body)))
    }

    void serveMockedArtifactMavenMetadataResponse(String group, String artifactName) {
        String body = mavenMetadataFileContents(group, artifactName)
        assert body != null && body != ''

        WireMock.stubFor(WireMock.get("/${parseGroup(group)}/${artifactName}/maven-metadata.xml")
                .willReturn(
                        WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/xml")
                                .withBody(body)))
    }

    void serveMockedArtifactMavenMetadata_Head_Response(String group, String artifactName) {
        String body = mavenMetadataFileContents(group, artifactName)
        assert body != null && body != ''

        WireMock.stubFor(WireMock.head(WireMock.urlEqualTo("/${parseGroup(group)}/${artifactName}/maven-metadata.xml"))
                .willReturn(
                        WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/xml")
                                .withBody(body)))
    }

    void serveMockedArtifactMetadata_Head_Response(String group, String artifactName, String version) {
        String body = dependencyMetadata(group, artifactName, version)
        assert body != null && body != ''

        WireMock.stubFor(WireMock.head(WireMock.urlEqualTo('/' + filePathFor(group, artifactName, version, 'pom')))
                .willReturn(
                        WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/xml")
                                .withBody(body)))
    }

    void serveMockedArtifactMetadataSha1Response(String group, String artifactName, String version) {
        def sampleSha1 = '32ec6c325bf85c9a270f89a9afc8a63918a1889b'
        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo('/' + filePathFor(group, artifactName, version, 'pom.sha1')))
                .willReturn(
                        WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/html")
                                .withBody(sampleSha1)))
    }

    void serveMockedJar_Head_Response(String group, String artifactName, String version) {
        String pathToFile = dependencyArtifactPath(group, artifactName, version)
        assert pathToFile != null && pathToFile != ''

        // requests the headers that are returned if the specified resource would be requested with an HTTP GET method
        WireMock.stubFor(WireMock.head(WireMock.urlEqualTo('/' + filePathFor(group, artifactName, version, 'jar')))
                .willReturn(
                        WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/java-archive")
                                .withBody('<fakeJarContent></fakeJarContent>')))
    }

    private String dependencyMetadata(String group, String artifactName, String version) {
        File metadataFile = dependencyFile(group, artifactName, version, 'pom')

        return metadataFile.text
    }

    private String dependencyArtifactPath(String group, String artifactName, String version) {
        File file = dependencyFile(group, artifactName, version, 'jar')

        return file.path
    }

    private File dependencyFile(String group, String artifactName, String version, String extension) {
        File file = new File("${projectDir}/testrepogen/mavenrepo/${filePathFor(group, artifactName, version, extension)}")
        assert file.exists()

        file
    }

    private String mavenMetadataFileContents(String group, String artifactName) {
        File file = new File("${projectDir}/testrepogen/mavenrepo/${parseGroup(group)}/${artifactName}/maven-metadata.xml")
        assert file.exists()

        file.text
    }

    String filePathFor(String group, String artifactName, String version, String extension) {
        return "${parseGroup(group)}/${artifactName}/$version/$artifactName-${version}.${extension}"
    }

    static String parseGroup(String group) {
        group.replace('.', '/')
    }
}
