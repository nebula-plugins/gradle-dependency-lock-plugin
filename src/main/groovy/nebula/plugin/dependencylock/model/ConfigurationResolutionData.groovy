package nebula.plugin.dependencylock.model

import groovy.transform.Canonical
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.provider.Provider

@Canonical
class ConfigurationResolutionData {
    String configurationName
    Collection<DependencyResult> allDependencies
    Provider<ResolvedComponentResult> resolvedComponentResult
}
