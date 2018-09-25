package nebula.plugin.dependencylock.wayback

import groovy.transform.Memoized
import groovyx.net.http.HttpResponseException
import groovyx.net.http.RESTClient
import nebula.plugin.dependencylock.model.GradleDependency
import nebula.plugin.metrics.MetricsPluginExtension
import net.logstash.logback.encoder.org.apache.commons.lang.StringUtils
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.joda.time.DateTime

abstract class AbstractNebulaMetricsWaybackProvider extends WaybackProvider {
    RESTClient client

    AbstractNebulaMetricsWaybackProvider(Project project) {
        super(project)
        def metrics = project.extensions.findByType(MetricsPluginExtension)

        if(!metrics) {
            throw new GradleException('You must apply the nebula.metrics plugin in order to use this wayback provider')
        }

        client = new RESTClient("http://$metrics.hostname:$metrics.httpPort")
    }

    /**
     * @param terms
     * @return Map of configuration names to their set of resolved dependencies
     */
    @Memoized
    protected Map<String, Set<GradleDependency>> waybackAllConfigurations(Map<String, String> terms, Closure maxHit) {
        String query = """
            |{
            |  "query": {
            |    "filtered": {
            |      "query": {
            |        "match_all": {}
            |      },
            |      "filter": {
            |        "and": [
        """.stripMargin() +
        terms.collect { k, v -> """ 
            |          {
            |            "term": {
            |              "$k": "$v"
            |            }
            |          }
        """.stripMargin()}.join(',') + """
            |        ]
            |      }
            |    }
            |  }
            |}
        """.stripMargin()

        def hit = null

        for(int i = 0; i <= 6; i++) {
            try {
                def resp = client.post(path: "/${getIndexName(project, DateTime.now().minusMonths(i))}/build/_search",
                        body: query,
                        requestContentType: 'application/json')

                if(resp.status == 200) {
                    hit = resp.data.hits?.hits?.collect { it._source }?.max(maxHit)
                    if (hit) break
                }
            } catch (HttpResponseException ignored) {
                // Jest throws an exception attempting to parse JSON bodies when a web server returns an HTML body with a 404,
                // so just do nothing here... (bug in Abstract.createNewElasticSearchResult)
            }
        }

        /*
            "resolved-dependencies": {
              "myapp-dependencies": {},
              "myapp-app-dependencies": {
                "Resolved-Dependencies-ResolutionRules": "com.netflix.nebula:gradle-resolution-rules:0.41.0,netflix.nebula.resolutionrules:resolution-rules:0.33.0",
                ...
         */
        Map<String, String> projectDependencies = hit?.'resolved-dependencies'?.find { it.key.startsWith(project.name) }?.value
        return ((projectDependencies?.collectEntries { k, v ->
            [(uncapitalize(StringUtils.substringAfter(k, 'Resolved-Dependencies-'))) :
                v.split(',').collect { GradleDependency.fromConstant(it) }.toSet()]
        } ?: [:]) as Map<String, Set<GradleDependency>>).withDefault {[] as Set}
    }

    protected static String getIndexName(Project project, DateTime dt) {
        return project.extensions.findByType(MetricsPluginExtension).getIndexName(dt)
    }

    protected static String uncapitalize(String str) {
        int strLen
        return str != null && (strLen = str.length()) != 0 ? (new StringBuilder(strLen)).append(Character.toLowerCase(str.charAt(0))).append(str.substring(1)).toString() : str
    }
}
