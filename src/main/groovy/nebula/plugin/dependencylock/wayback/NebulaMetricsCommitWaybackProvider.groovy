package nebula.plugin.dependencylock.wayback

import nebula.plugin.dependencylock.model.GradleDependency
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.joda.time.DateTime

class NebulaMetricsCommitWaybackProvider extends AbstractNebulaMetricsWaybackProvider {
    NebulaMetricsCommitWaybackProvider(Project project) {
        super(project)
    }

    @Override
    Set<GradleDependency> wayback(String selector, Configuration configuration) {
        Map<String, Set<GradleDependency>> depsByConf = waybackAllConfigurations(
                ['project.name': project.rootProject.name, 'info.scm.generic.change': selector],
                { DateTime.parse(it.startTime as String) }
        )

        return depsByConf[configuration.name]
    }
}