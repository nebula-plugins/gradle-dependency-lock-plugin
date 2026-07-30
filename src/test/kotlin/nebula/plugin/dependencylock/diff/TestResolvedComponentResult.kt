package nebula.plugin.dependencylock.diff


import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.result.*
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.ComponentSelectionReasons

internal class TestResolvedComponentResult(
    val componentIdentifier: ComponentIdentifier,
    val versionIdentifier: ModuleVersionIdentifier? = null,
    private val variants: List<ResolvedVariantResult> = emptyList(),
    private val dependencies: Set<ResolvedDependencyResult> = emptySet(),
    private val dependants: Set<ResolvedDependencyResult> = emptySet()
) : ResolvedComponentResult {
    override fun getDependencies(): Set<DependencyResult> {
        return dependencies
    }

    override fun getDependents(): Set<ResolvedDependencyResult> {
        return dependants
    }

    override fun getSelectionReason(): ComponentSelectionReason {
        return ComponentSelectionReasons.requested()
    }

    override fun getModuleVersion(): ModuleVersionIdentifier? {
        return versionIdentifier
    }

    override fun getVariants(): List<ResolvedVariantResult> {
        return variants
    }

    override fun getDependenciesForVariant(variant: ResolvedVariantResult): List<DependencyResult> {
        TODO("Not yet implemented")
    }

    override fun getId(): ComponentIdentifier {
        return componentIdentifier
    }

    override fun toString(): String {
        return id.toString()
    }
}
