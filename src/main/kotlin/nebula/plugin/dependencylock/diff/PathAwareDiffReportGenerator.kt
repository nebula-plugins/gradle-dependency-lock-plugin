package nebula.plugin.dependencylock.diff

import nebula.dependencies.comparison.DependencyDiff
import org.gradle.api.Project
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

//todo add assertions to tests
//todo refactor
class PathAwareDiffReportGenerator : DiffReportGenerator {

    companion object {
        val VERSION_SCHEME = DefaultVersionSelectorScheme(DefaultVersionComparator(), VersionParser())
    }
    override fun generateDiffReport(project: Project, diffsByConfiguration: Map<String, List<DependencyDiff>> ): List<Map<String, Any>> {
        val pathsPerConfiguration: List<ConfigurationPaths> = diffsByConfiguration.map { (configurationName: String, differences: List<DependencyDiff>) ->
            val differencesByDependency: Map<String, DependencyDiff> = differences.associateBy { it.dependency }

            //build paths for all dependencies
            val pathQueue: Queue<Path> = LinkedList()
            pathQueue.addAll(project.configurations.getByName(configurationName).incoming.resolutionResult.root.dependencies.filterIsInstance<ResolvedDependencyResult>().map {
                Path(it, null)
            })
            val visited: MutableSet<ResolvedComponentResult> = HashSet()
            val terminatedPaths: MutableSet<Path> = HashSet()
            while(! pathQueue.isEmpty()) {
                val forExploration = pathQueue.poll()
                visited.add(forExploration.dependency.selected)
                val nextLevel: Set<Path> = forExploration.dependency.selected.dependencies.filterIsInstance<ResolvedDependencyResult>().map {
                    Path(it, forExploration)
                }.toSet()

                if (nextLevel.size > 0)
                    nextLevel.forEach {
                        if (visited.contains(it.dependency.selected)) {
                            terminatedPaths.add(it)
                        } else {
                            pathQueue.add(it)
                        }
                    }
                else
                    terminatedPaths.add(forExploration)
            }

            //filter paths with a change
            val pathsWithChanges: Set<Deque<Path>> = terminatedPaths.map {
                it.toList()
            }.filter { fullPath ->
                val firstChangeOnPath = fullPath.reversed().find { it.changedInUpdate(differencesByDependency) }
                if (firstChangeOnPath == null) {
                    false
                } else {
                    ! firstChangeOnPath.dependency.selected.selectionReason.isConflictResolution || firstChangeOnPath.isWinnerOfConflictResolution()
                }
            }.toSet()

            val pathsFromDirectDependencies: Set<List<Path>> = pathsWithChanges.map { fullPath ->
                val danglingLeaves = fullPath.takeWhile {!it.changedInUpdate(differencesByDependency) || it.dependency.selected.selectionReason.isConflictResolution && !it.isWinnerOfConflictResolution() }
                val significantPart = fullPath.drop(danglingLeaves.size)
                significantPart.toList().reversed()
            }.toSet()

            val removed: List<String> = differences.filter { it.isRemoved() }.map { it.dependency }

            ConfigurationPaths(configurationName, Differences(pathsFromDirectDependencies, removed))
        }

        val groupedDiffs: Map<Differences, List<String>> = pathsPerConfiguration.groupBy {it.paths}.mapValues { it.value.map { it.configurationName } }

        return groupedDiffs.map { (differences: Differences, configurations: List<String>) ->
            //todo waste to compute it again
            val differencesByDependency: Map<String, DependencyDiff> =  diffsByConfiguration.get(configurations.first())?.associateBy { it.dependency } ?: throw RuntimeException("Not expected state")
            mapOf<String, Any>(
                    "configurations" to configurations,
                    "differentPaths" to createDiffTree(differencesByDependency, differences.paths),
                    "removed" to differences.removed
            )
        }
    }

    fun createDiffTree(differencesByDependency: Map<String, DependencyDiff>, paths: Set<List<Path>>): List<Map<String, Any>> {
        val grouped: Map<Path, Set<List<Path>>> = paths.groupBy {
            it.first()
        }.mapValues { it.value.map{it.drop(1)}.filter { it.isNotEmpty() }.toSet() }.toSortedMap()

        return grouped.map { (node: Path, restOfPaths: Set<List<Path>>) ->
            val result = mutableMapOf(
                    "dependency" to node.dependency.selected.moduleVersion?.module.toString(),
                    "children" to createDiffTree(differencesByDependency, restOfPaths),
                    if (node.isSubmodule())
                        "isSubmodule" to true
                    else
                        "version" to node.dependency.selected.moduleVersion?.version.toString()
            )
            if (node.changedInUpdate(differencesByDependency)) {
                val diff = differencesByDependency[node.dependency.selected.moduleVersion?.module.toString()] ?: throw RuntimeException("Not expected state")
                val change = mutableMapOf(
                        "description" to node.changeDescription(),
                        "type" to if (diff.isNew) "NEW" else "UPDATED"
                )
                if (diff.isUpdated) {
                    change["previousVersion"] = diff.diff.values.first().oldVersion
                }
                result["change"] = change
            }
            result
        }


    }



    class ConfigurationPaths(val configurationName: String, val paths: Differences)

    data class Differences(val paths: Set<List<Path>>, val removed: List<String>)

    class Path(val dependency: ResolvedDependencyResult, val parent: Path?): Comparable<Path> {

        fun toList(): Deque<Path> {
            val tail = if (parent == null) {
                LinkedList<Path>()
            } else {
                parent.toList()
            }
            tail.addFirst(this)
            return tail
        }

        fun changedInUpdate(differencesByDependency: Map<String, DependencyDiff>): Boolean {
            return differencesByDependency.containsKey(dependency.selected.moduleVersion?.module.toString())
        }

        fun changeDescription(): String {
            return if (dependency.selected.selectionReason.isSelectedByRule) {
                val gradleDescription = dependency.selected.selectionReason.descriptions.find { it.cause == ComponentSelectionCause.SELECTED_BY_RULE}
                gradleDescription!!.description
            } else if (dependency.selected.selectionReason.isExpected) {
                if (isSubmodule()) {
                    "new local submodule"
                } else {
                    val gradleDescription = dependency.selected.selectionReason.descriptions.find { it.cause == ComponentSelectionCause.REQUESTED }
                    gradleDescription!!.description
                }
            } else if (dependency.selected.selectionReason.isForced) {
                val gradleDescription = dependency.selected.selectionReason.descriptions.find { it.cause == ComponentSelectionCause.FORCED}
                gradleDescription!!.description
            } else if (isWinnerOfConflictResolution()) {
                val gradleDescription = dependency.selected.selectionReason.descriptions.find { it.cause == ComponentSelectionCause.REQUESTED}
                gradleDescription!!.description
            } else {
                ""
            }
        }

        fun isSubmodule(): Boolean {
            return dependency.selected.id is ProjectComponentIdentifier
        }

        fun isWinnerOfConflictResolution(): Boolean {
            return if (dependency.selected.selectionReason.isConflictResolution() &&
                    dependency.requested is ModuleComponentSelector &&
                    dependency.selected.id is ModuleComponentIdentifier) {
                val selector = VERSION_SCHEME.parseSelector((dependency.requested as ModuleComponentSelector).version)
                selector.accept((dependency.selected.id as ModuleComponentIdentifier).version)
            } else
                false
        }

        override fun compareTo(other: Path): Int {
            return dependency.selected.id.toString().compareTo(other.dependency.selected.id.toString())
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Path

            if (dependency.selected.id != other.dependency.selected.id) return false
            if (parent != other.parent) return false

            return true
        }

        override fun hashCode(): Int {
            var result = dependency.selected.id.hashCode()
            result = 31 * result + (parent?.hashCode() ?: 0)
            return result
        }
    }
}