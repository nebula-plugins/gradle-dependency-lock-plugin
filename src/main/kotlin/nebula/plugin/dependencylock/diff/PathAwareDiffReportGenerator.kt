package nebula.plugin.dependencylock.diff

import nebula.dependencies.comparison.DependencyDiff
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ComponentSelectionCause
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionSelectorScheme
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser
import java.util.*
import kotlin.collections.HashSet

typealias Path = List<PathAwareDiffReportGenerator.DependencyPathElement>

class PathAwareDiffReportGenerator : DiffReportGenerator {

    companion object {
        val VERSION_SCHEME = DefaultVersionSelectorScheme(DefaultVersionComparator(), VersionParser())
    }

    // method constructs a map/list structure ready to be serialized with dependency paths with changes. Each group of paths
    // is marked with configuration names where those paths belong.
    override fun generateDiffReport(project: Project, diffsByConfiguration: Map<String, List<DependencyDiff>> ): List<Map<String, Any>> {
        val pathsPerConfiguration: List<ConfigurationPaths> = diffsByConfiguration.map { (configurationName: String, differences: List<DependencyDiff>) ->
            val allPaths: Set<Path> = constructShortestPathsToAllDependencies(differences, project, configurationName)
            val pathsWithChanges: Set<Path> = filterPathsWithSignificantChanges(allPaths)
            val pathsFromDirectDependencies: Set<Path> = removeNonSignificantPathParts(pathsWithChanges)

            val removed: List<String> = differences.filter { it.isRemoved }.map { it.dependency }

            ConfigurationPaths(configurationName, Differences(pathsFromDirectDependencies, removed))
        }

        val groupedDiffs: Map<Differences, List<String>> = groupConfigurationsWithSameChanges(pathsPerConfiguration)

        return groupedDiffs.map { (differences: Differences, configurations: List<String>) ->
            mapOf<String, Any>(
                    "configurations" to configurations,
                    "differentPaths" to createDiffTree(differences.paths),
                    "removed" to differences.removed
            )
        }
    }

    //this method constructs shotests paths to all unique dependencies from module root within a configuration
    private fun constructShortestPathsToAllDependencies(differences: List<DependencyDiff>, project: Project, configurationName: String): Set<Path> {
        val differencesByDependency: Map<String, DependencyDiff> = differences.associateBy { it.dependency }

        //build paths for all dependencies
        val pathQueue: Queue<DependencyPathElement> = LinkedList()
        pathQueue.add(DependencyPathElement(project.configurations.getByName(configurationName).incoming.resolutionResult.root, null, null, null))
        val visited: MutableSet<ResolvedComponentResult> = HashSet()
        val terminatedPaths: MutableSet<DependencyPathElement> = HashSet()
        while (!pathQueue.isEmpty()) {
            val forExploration = pathQueue.poll()
            visited.add(forExploration.selected)
            val nextLevel: Set<DependencyPathElement> = forExploration.selected.dependencies.filterIsInstance<ResolvedDependencyResult>().map {
                DependencyPathElement(it.selected, it.requested, differencesByDependency[it.selected.moduleName()], forExploration)
            }.toSet()

            if (nextLevel.isNotEmpty())
                nextLevel.forEach {
                    if (visited.contains(it.selected)) {
                        terminatedPaths.add(it)
                    } else {
                        pathQueue.add(it)
                    }
                }
            else
                terminatedPaths.add(forExploration)
        }
        //convert path to a list structure and drop root from it
        return terminatedPaths.mapTo(mutableSetOf()) { it.toList().drop(1) }
    }

    // we need to find only paths that have significant changes in them. A significant change is any new version requested by parent
    // or a rule or force. If change is caused by conflict resolution but the path is not responsible for bringing the winning version,
    // it is not considered significant change
    private fun filterPathsWithSignificantChanges(allPaths: Set<Path>): Set<Path> {
        return allPaths.filterTo(mutableSetOf()) { fullPath ->
            val firstChangeOnPath = fullPath.find { it.isChangedInUpdate() }
            if (firstChangeOnPath == null) {
                false
            } else {
                !firstChangeOnPath.selected.selectionReason.isConflictResolution || firstChangeOnPath.isWinnerOfConflictResolution()
            }
        }
    }

    // dependency paths can have a significant change in a middle but transitive dependencies of the updated dependency
    // might be the same, we will drop any parts of a path after changed dependency that is unchanged or just changed
    // by conflict resolution
    private fun removeNonSignificantPathParts(pathsWithChanges: Set<Path>) =
            pathsWithChanges.map { fullPath ->
                fullPath.dropLastWhile { !it.isChangedInUpdate() || it.selected.selectionReason.isConflictResolution && !it.isWinnerOfConflictResolution() }
            }.toSet()

    // some configurations can have the exact same changes we want to avoid duplicating the same information so
    // configurations with the exact same changes are grouped together.
    private fun groupConfigurationsWithSameChanges(pathsPerConfiguration: List<ConfigurationPaths>) =
            pathsPerConfiguration.groupBy { it.paths }.mapValues { it.value.map { it.configurationName } }

    private fun createDiffTree(paths: Set<Path>): List<Map<String, Any>> {
        val grouped: Map<DependencyPathElement, Set<Path>> = currentLevelPathElementsWithChildren(paths)

        return grouped.map { (dependencyPathElement: DependencyPathElement, restOfPaths: Set<Path>) ->
            val result = mutableMapOf(
                    "dependency" to dependencyPathElement.selected.moduleName(),
                    "children" to createDiffTree(restOfPaths),
                    if (dependencyPathElement.isSubmodule())
                        "isSubmodule" to true
                    else
                        "version" to dependencyPathElement.selected.moduleVersion()
            )
            if (dependencyPathElement.isChangedInUpdate()) {
                val diff = dependencyPathElement.dependencyDiff!!
                val change = mutableMapOf(
                        "description" to dependencyPathElement.changeDescription(),
                        "type" to if (diff.isNew) "NEW" else "UPDATED"
                )
                if (diff.isUpdated) {
                    change["previousVersion"] = dependencyPathElement.getPreviousVersion()
                }
                result["change"] = change
            }
            result
        }
    }

    private fun currentLevelPathElementsWithChildren(paths: Set<Path>) =
            paths.groupBy {
                it.first()
            }.mapValues {
                //remove current element to get possible children
                //remove empty lists where no children left
                it.value.map { it.drop(1) }
                        .filter { it.isNotEmpty() }
                        .toSet()
            }.toSortedMap()

    class ConfigurationPaths(val configurationName: String, val paths: Differences)

    data class Differences(val paths: Set<Path>, val removed: List<String>)

    class DependencyPathElement(val selected: ResolvedComponentResult, val requested: ComponentSelector?, val dependencyDiff: DependencyDiff?, val parent: DependencyPathElement?): Comparable<DependencyPathElement> {

        fun toList(): MutableList<DependencyPathElement> {
            val tail = parent?.toList() ?: LinkedList()
            tail.add(this)
            return tail
        }

        //return true if the dependency has been somehow updated/added in the graph
        fun isChangedInUpdate(): Boolean {
            return dependencyDiff != null
        }

        fun getPreviousVersion(): String {
            return dependencyDiff?.diff?.values?.first()?.oldVersion
                    ?: throw IllegalStateException("Dependency wasn't updated so it doesn't have previous version")
        }


        fun changeDescription(): String {
            return if (selected.selectionReason.isSelectedByRule) {
                findDescriptionForCause(ComponentSelectionCause.SELECTED_BY_RULE)
            } else if (selected.selectionReason.isExpected) {
                if (isSubmodule()) {
                    "new local submodule"
                } else {
                    findDescriptionForCause(ComponentSelectionCause.REQUESTED)
                }
            } else if (selected.selectionReason.isForced) {
                findDescriptionForCause(ComponentSelectionCause.FORCED)
            } else if (isWinnerOfConflictResolution()) {
                findDescriptionForCause(ComponentSelectionCause.REQUESTED)
            } else {
                ""
            }
        }

        private fun findDescriptionForCause(cause: ComponentSelectionCause): String {
            val gradleDescription = selected.selectionReason.descriptions.find { it.cause == cause}
            return gradleDescription!!.description
        }

        fun isSubmodule(): Boolean {
            return selected.id is ProjectComponentIdentifier
        }

        fun isWinnerOfConflictResolution(): Boolean {
            return if (selected.selectionReason.isConflictResolution &&
                    requested is ModuleComponentSelector &&
                    selected.id is ModuleComponentIdentifier) {
                val selector = VERSION_SCHEME.parseSelector(requested.version)
                selector.accept((selected.id as ModuleComponentIdentifier).version)
            } else
                false
        }

        override fun compareTo(other: DependencyPathElement): Int {
            return selected.id.toString().compareTo(other.selected.id.toString())
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as DependencyPathElement

            if (selected.id != other.selected.id) return false
            if (parent != other.parent) return false

            return true
        }

        override fun hashCode(): Int {
            var result = selected.id.hashCode()
            result = 31 * result + (parent?.hashCode() ?: 0)
            return result
        }
    }

    private fun ResolvedComponentResult.moduleName(): String {
        return this.moduleVersion?.module.toString()
    }

    private fun ResolvedComponentResult.moduleVersion(): String {
        return this.moduleVersion?.version.toString()
    }
}