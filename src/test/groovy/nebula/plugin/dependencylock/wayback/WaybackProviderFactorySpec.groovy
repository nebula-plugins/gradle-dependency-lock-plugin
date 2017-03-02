package nebula.plugin.dependencylock.wayback

import nebula.plugin.metrics.MetricsPluginExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

class WaybackProviderFactorySpec extends Specification {
    Project project = ProjectBuilder.builder().build()
    WaybackProviderFactory factory = new WaybackProviderFactory(project, getClass().classLoader)

    def setup() {
        project.extensions.create('metrics', MetricsPluginExtension)
    }

    def 'construct a wayback provider by provider ID'() {
        expect:
        factory.build('nebula.metrics-commit')
    }

    def 'return null when the provider ID has no matching resource on the classpath'() {
        when:
        factory.build('nebula.does-not-exist')

        then:
        thrown(GradleException)
    }
}
