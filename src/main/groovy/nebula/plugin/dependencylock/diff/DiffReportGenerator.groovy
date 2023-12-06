package nebula.plugin.dependencylock.diff

import org.gradle.api.Project
import nebula.dependencies.comparison.DependencyDiff

interface DiffReportGenerator {
    List<Map<String, Object>> generateDiffReport(Project project, Map<String, List<DependencyDiff>> diffsByConfiguration)
}
