package nebula.plugin.dependencylock.diff

import nebula.dependencies.comparison.DependencyDiff
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.provider.Provider

interface DiffReportGenerator {
    /**
     * Generate a diff report from dependency differences.
     * 
     * @param resolutionResults Map of configuration name to its resolved component result (for path-aware diff)
     * @param diffsByConfiguration Map of configuration name to list of dependency diffs
     * @return List of diff report entries
     */
    List<Map<String, Object>> generateDiffReport(
        Map<String, Provider<ResolvedComponentResult>> resolutionResults,
        Map<String, List<DependencyDiff>> diffsByConfiguration
    )
}
