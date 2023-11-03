package nebula.plugin.dependencylock.diff

import org.gradle.api.Project
import nebula.dependencies.comparison.DependencyDiff
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer

interface DiffReportGenerator {
    List<Map<String, Object>> generateDiffReport(Collection<Configuration> configurations, Map<String, List<DependencyDiff>> diffsByConfiguration)
}
