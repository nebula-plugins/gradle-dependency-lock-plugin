package nebula.plugin.dependencylock.diff

import nebula.dependencies.comparison.DependencyDiff
import org.gradle.api.artifacts.component.*
import org.gradle.api.artifacts.result.ComponentSelectionCause
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionSelectorScheme
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.provider.Provider
import java.lang.RuntimeException
import java.util.*

class PathAwareDiffReportGenerator : DiffReportGenerator {

    companion object {
        val VERSION_SCHEME = DefaultVersionSelectorScheme(DefaultVersionComparator(), VersionParser())
    }

    // method constructs a map/list structure ready to be serialized with dependency paths with changes. Each group of paths
    // is marked with configuration names where those paths belong.
    override fun generateDiffReport(
        resolutionResults: Map<String, Provider<ResolvedComponentResult>>,
        diffsByConfiguration: Map<String, List<DependencyDiff>>
    ): List<Map<String, Any>> {
        val pathsPerConfiguration: List<ConfigurationPaths> = diffsByConfiguration
            .filterKeys { name ->
                // Only process configurations that have resolution results available
                resolutionResults.containsKey(name)
            }
            .map { (configurationName: String, differences: List<DependencyDiff>) ->
            val completeDependencyTree: AnnotatedDependencyTree = constructPathsToAllDependencies(
                differences, 
                resolutionResults[configurationName]!!,
                configurationName
            )
            val removedInsignificantChanges: AnnotatedDependencyTree = filterPathsWithSignificantChanges(completeDependencyTree)
            val removeAlreadyVisited: AnnotatedDependencyTree = filterPathsWithDuplicatedElements(removedInsignificantChanges)
            val removedInsignificantChangesAfterRemovingAlreadyVisited: AnnotatedDependencyTree = filterPathsWithSignificantChanges(removeAlreadyVisited)

            val removed: List<String> = differences.filter { it.isRemoved }.map { it.dependency }

            ConfigurationPaths(configurationName, Differences(removedInsignificantChangesAfterRemovingAlreadyVisited, removed))
        }

        val groupedDiffs: Map<Differences, List<String>> = groupConfigurationsWithSameChanges(pathsPerConfiguration)

        return groupedDiffs.map { (differences: Differences, configurations: List<String>) ->
            mapOf<String, Any>(
                    "configurations" to configurations,
                    "differentPaths" to createDiffTree(differences.newAndUpdated.root),
                    "removed" to differences.removed
            )
        }
    }

    //this method constructs paths to all unique dependencies from module root within a configuration
    private fun constructPathsToAllDependencies(
        differences: List<DependencyDiff>,
        rootComponentProvider: Provider<ResolvedComponentResult>,
        configurationName: String
    ): AnnotatedDependencyTree {
        val differencesByDependency: Map<String, DependencyDiff> = differences.associateBy { it.dependency }

        //build paths for all dependencies
        val pathStack: Deque<DependencyPathElement> = LinkedList()
        // Get the root component from the provider
        val root = DependencyPathElement(rootComponentProvider.get(), null, null)
        pathStack.add(root)
        val visited = mutableSetOf<ResolvedDependencyResult>()
        while (!pathStack.isEmpty()) {
            val forExploration = pathStack.pop()
            forExploration.selected.dependencies.filterIsInstance<ResolvedDependencyResult>()
                    .sortedBy { it.selected.moduleVersion.toString() }
                    .reversed()
                    .forEach {
                //attach new element to the tree
                val newElement = DependencyPathElement(it.selected, it.requested, differencesByDependency[it.selected.moduleName()])
                if (! visited.contains(it) && ! terminateExploration(newElement)) {
                    forExploration.addChild(newElement)
                    pathStack.push(newElement)
                }
                visited.add(it)
            }
        }
        return AnnotatedDependencyTree(root)
    }

    private fun terminateExploration(element: DependencyPathElement): Boolean {
        //we assume that if this node lost conflict resolution there will be another place where this subtree
        //was a winner so we don't need to cover it on all places where it was used instead of losers
        //the exception are aligned dependencies that could have "no winner" since they are using virtual platform to upgrade to desired version
        val selectionReason = element.selected.selectionReason
        return selectionReason.isConflictResolution && !selectionReason.isConstrained && !element.isWinnerOfConflictResolution()
    }

    // we need to find only paths that have significant changes in them. A significant change is any new version requested by parent
    // or a rule or force. If change is caused by conflict resolution but the path is not responsible for bringing the winning version,
    // it is not considered significant change
    // dependency paths can have a significant change in a middle but transitive dependencies of the updated dependency
    // might be the same, we will drop any parts of a path after changed dependency that is unchanged or just changed
    // by conflict resolution
    private fun filterPathsWithSignificantChanges(completeDependencyTree: AnnotatedDependencyTree): AnnotatedDependencyTree {
        removeInsignificantDependencyPathElements(completeDependencyTree.root)
        return completeDependencyTree
    }

    private fun removeInsignificantDependencyPathElements(element: DependencyPathElement): Boolean {
        element.children.removeIf {
            removeInsignificantDependencyPathElements(it)
        }
        return element.children.isEmpty() && !element.isChangedInUpdate()
    }

    private fun filterPathsWithDuplicatedElements(completeDependencyTree: AnnotatedDependencyTree): AnnotatedDependencyTree {
        removeAlreadyVisited(completeDependencyTree.root, mutableSetOf())
        return completeDependencyTree
    }

    private fun removeAlreadyVisited(element: DependencyPathElement, visited: MutableSet<ComponentIdentifier>) {
        visited.add(element.selected.id)
        element.children.forEach {
            if (visited.contains(it.selected.id)) {
                it.alreadyVisited = true
                it.children.clear()
            } else {
                removeAlreadyVisited(it, visited)
            }
        }
    }

    // some configurations can have the exact same changes we want to avoid duplicating the same information so
    // configurations with the exact same changes are grouped together.
    private fun groupConfigurationsWithSameChanges(pathsPerConfiguration: List<ConfigurationPaths>) =
            pathsPerConfiguration.groupBy { it.paths }.mapValues { it.value.map { it.configurationName } }

    private fun createDiffTree(parentElement: DependencyPathElement): List<Map<String, Any>> {
        return parentElement.children.map { dependencyPathElement: DependencyPathElement ->
            val result: MutableMap<String, Any> = mutableMapOf()
            result["dependency"] = dependencyPathElement.selected.moduleName()
            if (dependencyPathElement.isSubmodule())
                result["submodule"] = true
            else {
                result["status"] = dependencyPathElement.extractStatus()
                result["version"] = dependencyPathElement.selected.moduleVersion()
                result["requestedVersion"] = dependencyPathElement.requestedVersion() ?: "Unknown"
                result["selectionReasonDescriptions"] = dependencyPathElement.collectSelectionReasons()
            }

            if (!dependencyPathElement.alreadyVisited) {
                result["children"] = createDiffTree(dependencyPathElement)
            } else {
                result["repeated"] = true
            }
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
        }.filter { it.isNotEmpty() }
    }

    class ConfigurationPaths(val configurationName: String, val paths: Differences)

    data class Differences(val newAndUpdated: AnnotatedDependencyTree, val removed: List<String>)

    data class AnnotatedDependencyTree(val root: DependencyPathElement)

    class DependencyPathElement(val selected: ResolvedComponentResult, val requested: ComponentSelector?, val dependencyDiff: DependencyDiff?) {

        var alreadyVisited: Boolean = false
        val children: LinkedList<DependencyPathElement> = LinkedList()

        //return true if the dependency has been somehow updated/added in the graph
        fun isChangedInUpdate(): Boolean {
            return dependencyDiff != null
        }

        fun getPreviousVersion(): String {
            return dependencyDiff?.diff?.values?.first()?.oldVersion
                    ?: throw IllegalStateException("Dependency wasn't updated so it doesn't have previous version")
        }


        fun changeDescription(): String {
            val causesWithDescription = selected.selectionReason.descriptions.associate { it.cause to it.description }.toSortedMap()
            if (causesWithDescription.contains(ComponentSelectionCause.REQUESTED) && isSubmodule()) {
                causesWithDescription[ComponentSelectionCause.REQUESTED] = "new local submodule"
            }
            if (causesWithDescription.contains(ComponentSelectionCause.CONFLICT_RESOLUTION)) {
                val message = if (isWinnerOfConflictResolution())
                    "the parent brought the winner of conflict resolution"
                else
                    "the parent brought this participant in conflict resolution, but the winner is from a different path"
                causesWithDescription[ComponentSelectionCause.CONFLICT_RESOLUTION] = message

            }
            return causesWithDescription.values.joinToString("; ")
        }

        fun isSubmodule(): Boolean {
            return selected.id is ProjectComponentIdentifier
        }

        fun isWinnerOfConflictResolution(): Boolean {
            val requestedVersion = requestedVersion()
            return if (selected.selectionReason.isConflictResolution &&
                    requestedVersion != null &&
                    selected.id is ModuleComponentIdentifier) {
                val selector = VERSION_SCHEME.parseSelector(requestedVersion)
                selector.accept((selected.id as ModuleComponentIdentifier).version)
            } else
                false
        }

        fun requestedVersion(): String? {
            return if (requested is ModuleComponentSelector) {
                requested.version
            } else {
                null
            }
        }

        fun extractStatus(): String {
            return this.selected.variants.first().attributes.getAttribute(ProjectInternal.STATUS_ATTRIBUTE) ?: throw RuntimeException("Unknown status")
        }

        fun collectSelectionReasons(): Map<String, List<String>> {
            return selected.selectionReason.descriptions.groupBy { it.cause.toString() }
                    .mapValues {
                        it.value.filter { it.description.isNotEmpty() }.map { it.toString() }
                    }.toSortedMap()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as DependencyPathElement

            if (selected.id != other.selected.id) return false
            if (children != other.children) return false

            return true
        }

        override fun hashCode(): Int {
            var result = selected.id.hashCode()
            result = 31 * result + children.hashCode()
            return result
        }

        fun addChild(child: DependencyPathElement) {
            children.addFirst(child)
        }

        override fun toString(): String {
            return "DependencyPathElement(selected=${selected.id.displayName})"
        }
    }

    private fun ResolvedComponentResult.moduleName(): String {
        return this.moduleVersion?.module.toString()
    }

    private fun ResolvedComponentResult.moduleVersion(): String {
        return this.moduleVersion?.version.toString()
    }
}