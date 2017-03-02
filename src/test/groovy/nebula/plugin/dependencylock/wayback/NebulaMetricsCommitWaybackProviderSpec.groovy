package nebula.plugin.dependencylock.wayback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import nebula.plugin.metrics.MetricsPluginExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.joda.time.DateTime
import spock.lang.Specification

class NebulaMetricsCommitWaybackProviderSpec extends Specification {
    def rootProjectName = 'myapp'
    def projectName = 'myapp-app'

    Project p
    MetricsPluginExtension metricsExt
    NebulaMetricsCommitWaybackProvider provider
    HttpServer esServer

    def setup() {
        p = ProjectBuilder.builder()
                .withParent(ProjectBuilder.builder().withName(rootProjectName).build())
                .withName(projectName).build()

        metricsExt = p.extensions.create('metrics', MetricsPluginExtension).with {
            hostname = 'localhost'
            httpPort = 7104
            rollingIndex = true
            delegate
        }

        provider = new NebulaMetricsCommitWaybackProvider(p)

        esServer = mockServer()
    }

    def cleanup() {
        esServer.stop(0)
    }

    def 'extract resolved dependencies from a Nebula Metrics response'() {
        when:
        handleAndReturnHits(esServer, metricsExt.getIndexName(), 'compile', 'com.google.guava:guava:19.0')
        esServer.start()
        then:
        provider.wayback('mycommit', p.configurations.create('compile')).size() == 1
    }

    def 'return no results when the metrics server is unavailable'() {
        when:
        esServer.start() // everything will return 404

        then:
        provider.wayback('mycommit', p.configurations.create('compile')).isEmpty()
    }

    def "when the most recent build is in a prior month's metrics index, find it still"() {
        when:
        handleAndReturnHits(esServer, metricsExt.getIndexName(DateTime.now().minusMonths(1)), 'compile', 'com.google.guava:guava:19.0')
        esServer.start()

        then:
        provider.wayback('mycommit', p.configurations.create('compile')).size() == 1
    }

    private static HttpServer mockServer() {
        return HttpServer.create(new InetSocketAddress(7104), 0)
    }

    private void handleAndReturnHits(HttpServer server, String index, String configuration, String... dependencies) {
        server.createContext("/$index/build/_search", new HttpHandler() {
            @Override
            void handle(HttpExchange t) throws IOException {
                println(t.requestURI)
                def response = """
                    |{
                    |  "hits": {
                    |    "hits": [
                    |      {
                    |        "_index": "$index",
                    |        "_type": "build",
                    |        "_source": {
                    |          "startTime": "2016-08-30T19:21:34.447Z",
                    |          "resolved-dependencies": {
                    |            "$rootProjectName-dependencies": {},
                    |            "$projectName-dependencies": {
                    |              "Resolved-Dependencies-${configuration.capitalize()}": "${dependencies.join(',')}"
                    |            }
                    |          }
                    |        }
                    |      }
                    |    ]
                    |  }
                    |}
                """.stripMargin()

                t.responseHeaders.add('Content-Type', 'application/json')
                t.sendResponseHeaders(200, response.length())
                OutputStream os = t.getResponseBody()
                os.write(response.getBytes())
                os.close()
            }
        })
    }
}
