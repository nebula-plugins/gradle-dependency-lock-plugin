package nebula.plugin.dependencylock.diff

import nebula.dependencies.comparison.ConfigurationsSet
import nebula.dependencies.comparison.Dependencies
import nebula.dependencies.comparison.DependenciesComparison
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.artifacts.DefaultProjectComponentIdentifier
import org.gradle.api.internal.artifacts.result.DefaultResolvedDependencyResult
import org.gradle.api.internal.artifacts.result.DefaultResolvedVariantResult
import org.gradle.api.internal.project.ProjectIdentity
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.provider.DefaultProvider
import org.gradle.internal.Describables
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector
import org.gradle.internal.component.external.model.ImmutableCapabilities
import org.gradle.internal.impldep.com.google.common.collect.ImmutableList
import org.gradle.util.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

internal class PathAwareDiffReportGeneratorTest {
    @Test
    fun `reports a changed configuration without a resolution result`() {
        val oldLocks = ConfigurationsSet(mapOf("runtimeClasspath" to Dependencies(mapOf("group:artifact" to "1.0"))))
        val newLocks = ConfigurationsSet(mapOf("runtimeClasspath" to Dependencies(mapOf("group:artifact" to "2.0"))))
        val diffs = DependenciesComparison.performDiffByConfiguration(oldLocks, newLocks)

        val report = PathAwareDiffReportGenerator().generateDiffReport(emptyMap(), diffs)

        assertEquals(1, report.size)
        assertEquals(listOf("runtimeClasspath"), report.single()["configurations"])
        assertEquals(emptyList<Any>(), report.single()["differentPaths"])
        assertEquals(listOf(mapOf(
            "coordinate" to "group:artifact",
            "oldVersion" to "1.0",
            "newVersion" to "2.0",
            "status" to "UPDATED"
        )), report.single()["changes"])
    }

    @Test
    fun `reports a coordinate moved between configurations`() {
        val oldLocks = ConfigurationsSet(mapOf("compileClasspath" to Dependencies(mapOf("group:artifact" to "1.0"))))
        val newLocks = ConfigurationsSet(mapOf("runtimeClasspath" to Dependencies(mapOf("group:artifact" to "1.0"))))
        val diffs = DependenciesComparison.performDiffByConfiguration(oldLocks, newLocks)

        val report = PathAwareDiffReportGenerator().generateDiffReport(emptyMap(), diffs)
        val byConfiguration = report.associateBy { (it["configurations"] as List<*>).single() }

        assertEquals(setOf("compileClasspath", "runtimeClasspath"), byConfiguration.keys)
        assertEquals(listOf(mapOf(
            "coordinate" to "group:artifact",
            "oldVersion" to "1.0",
            "newVersion" to null,
            "status" to "REMOVED"
        )), byConfiguration["compileClasspath"]?.get("changes"))
        assertEquals(listOf(mapOf(
            "coordinate" to "group:artifact",
            "oldVersion" to null,
            "newVersion" to "1.0",
            "status" to "ADDED"
        )), byConfiguration["runtimeClasspath"]?.get("changes"))
    }

    @Test
    fun `test project to external`() {
        val oldLocks = ConfigurationsSet(
            mapOf(
                "compileClasspath" to Dependencies(mapOf("group:artifact" to null))
            )
        )
        val newLocks = ConfigurationsSet(
            mapOf(
                "compileClasspath" to Dependencies(mapOf("group:artifact" to "1.0"))
            )
        )
        val diffByConfiguration = DependenciesComparison.performDiffByConfiguration(oldLocks, newLocks)
        val moduleIdentifier = DefaultModuleIdentifier.newId("group", "name")
        val id = DefaultModuleComponentIdentifier(moduleIdentifier, "2.0")
        val rootComponent = rootProject(
            "test", setOf(
                externalDependency("group", "artifact", "1.0", TestResolvedComponentResult(id))
            )
        )
        val instance = PathAwareDiffReportGenerator()
        val result = instance.generateDiffReport(
            mapOf("compileClasspath" to DefaultProvider({ rootComponent })),
            diffByConfiguration
        )
    }

    fun rootProject(name: String, dependencies: Set<ResolvedDependencyResult>): ResolvedComponentResult {
        return TestResolvedComponentResult(
            DefaultProjectComponentIdentifier(
                ProjectIdentity.forRootProject(Path.path(":$name"), name)
            ),
            dependencies = dependencies
        )
    }

    fun externalDependency(
        group: String,
        name: String,
        version: String,
        from: ResolvedComponentResult,
        dependants: Set<ResolvedDependencyResult> = emptySet()
    ): ResolvedDependencyResult {
        val moduleIdentifier = DefaultModuleIdentifier.newId(group, name)
        val versionId = DefaultModuleVersionIdentifier.newId(moduleIdentifier, version)
        val componentId = DefaultModuleComponentIdentifier.newId(moduleIdentifier, version)
        val variant = DefaultResolvedVariantResult(
            componentId,
            Describables.of("runtimeElements"),
            TestAttributeContainer(mutableMapOf(ProjectInternal.STATUS_ATTRIBUTE to "release")),
            ImmutableList.of(),
            null
        )
        val component = TestResolvedComponentResult(
            componentId, versionId,
            variants = listOf(variant),
            dependants = dependants
        )
        return DefaultResolvedDependencyResult(
            DefaultModuleComponentSelector.newSelector(moduleIdentifier, version),
            false,
            from,
            component,
            variant
        )
    }
}
