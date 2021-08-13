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
//todo presunout do nebula?
//todo machine readable outout and print output
//todo refactor
class PathAwareDiffReportGenerator : DiffReportGenerator {

    companion object {
        val VERSIONED_COMPARATOR = DefaultVersionComparator()
        val VERSION_SCHEME = DefaultVersionSelectorScheme(VERSIONED_COMPARATOR, VersionParser())
    }
    override fun generateDiffReport(project: Project, diffsByConfiguration: Map<String, List<DependencyDiff>> ): MutableMap<Any, Any> {
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
                val firstChangeOnPath = fullPath.reversed().find { changedInUpdate(differencesByDependency, it) }
                if (firstChangeOnPath == null) {
                    false
                } else {
                    ! firstChangeOnPath.dependency.selected.selectionReason.isConflictResolution || isWinnerOfConflictResolution(firstChangeOnPath)
                }
            }.toSet()

            val pathsFromDirectDependencies: Set<List<Path>> = pathsWithChanges.map { fullPath ->
                val danglingLeaves = fullPath.takeWhile {!changedInUpdate(differencesByDependency, it) || it.dependency.selected.selectionReason.isConflictResolution && !isWinnerOfConflictResolution(it) }
                val significantPart = fullPath.drop(danglingLeaves.size)
                significantPart.toList().reversed()
            }.toSet()

            val removed: List<String> = differences.filter { it.isRemoved() }.map { it.dependency }

            ConfigurationPaths(configurationName, Differences(pathsFromDirectDependencies, removed))
        }

        val groupedDiffs: Map<Differences, List<String>> = pathsPerConfiguration.groupBy {it.paths}.mapValues { it.value.map { it.configurationName } }

        groupedDiffs.forEach {
            //todo waste to compute it again
            val differencesByDependency: Map<String, DependencyDiff> =  diffsByConfiguration.get(it.value.first())?.associateBy { it.dependency } ?: throw RuntimeException("Not expected state")
            //construct tree output
            println("Configurations: ${it.value.joinToString(", ")}")
            println("Updated and New dependencies:")
            printDiffTree(differencesByDependency, it.key.paths, "")
            println()
            //todo removed dependencies are printed only as flat list without context from where they were removed
            //it would be possible to use nebula lock to reconstruct the original paths, it could be potentially hard
            //to implement with core locking so we need to consider if we want to do that.
            if (! it.key.removed.isEmpty()) {
                println("Removed dependencies:")
                it.key.removed.forEach {
                    println("  $it")
                }
                println()
            }
        }
        return mutableMapOf()
    }

    fun printDiffTree(differencesByDependency: Map<String, DependencyDiff>, paths: Set<List<Path>>, levelPrefix: String) {
        val grouped: Map<Path, Set<List<Path>>> = paths.groupBy {
            it.first()
        }.mapValues { it.value.map{it.drop(1)}.filter { it.isNotEmpty() }.toSet() }.toSortedMap()
        val currentLevel = grouped.keys
        currentLevel.forEachIndexed { index, element ->

            var intention: String
            var nextLevelPrefix: String
            if (currentLevel.size - 1 == index) {
                intention = "\\---"
                nextLevelPrefix = levelPrefix + "     "
            } else {
                intention = "+---"
                nextLevelPrefix = levelPrefix + "|    "
            }
            val restOfPaths = grouped.get(element) ?: throw RuntimeException("Not expected state")
            var description = ""
            if (changedInUpdate(differencesByDependency, element)) {
                if (element.dependency.selected.selectionReason.isSelectedByRule) {
                    val gradleDescription = element.dependency.selected.selectionReason.descriptions.find { it.cause == ComponentSelectionCause.SELECTED_BY_RULE}
                    description = gradleDescription?.description ?: throw RuntimeException("Not expected state")
                } else if (element.dependency.selected.selectionReason.isExpected()) {
                    if (isSubmodule(element.dependency)) {
                        description = "new local submodule"
                    } else {
                        val gradleDescription = element.dependency.selected.selectionReason.descriptions.find { it.cause == ComponentSelectionCause.REQUESTED }
                        description = gradleDescription?.description ?: throw RuntimeException("Not expected state")
                    }
                } else if (element.dependency.selected.selectionReason.isForced()) {
                    val gradleDescription = element.dependency.selected.selectionReason.descriptions.find { it.cause == ComponentSelectionCause.FORCED}
                    description = gradleDescription?.description ?: throw RuntimeException("Not expected state")
                } else if (isWinnerOfConflictResolution(element)) {
                    val gradleDescription = element.dependency.selected.selectionReason.descriptions.find { it.cause == ComponentSelectionCause.REQUESTED}
                    description = gradleDescription?.description ?: throw RuntimeException("Not expected state")
                }

                val diff = differencesByDependency.get(element.dependency.selected.moduleVersion?.module.toString()) ?: throw RuntimeException("Not expected state")
                var dependencyChange: String
                        if (diff.isUpdated()) {
                            dependencyChange = diff.updatedDiffString().trim()
                        } else if (isSubmodule(element.dependency)) {
                            dependencyChange = element.dependency.selected.moduleVersion?.module.toString()
                        } else if (diff.isNew()) {
                            dependencyChange = diff.newDiffString().trim()
                        } else {
                            dependencyChange = element.dependency.selected.moduleVersion.toString()
                        }

                if (! description.isEmpty()) {
                    description = " - ${description}"
                }
                println("${levelPrefix}${intention}${dependencyChange}${description}")
            } else {
                val dependency = if (isSubmodule(element.dependency))
                element.dependency.selected.moduleVersion?.module.toString() else
                element.dependency.selected.moduleVersion.toString()
                println("${levelPrefix}${intention}${dependency}")
            }

            printDiffTree(differencesByDependency, restOfPaths, nextLevelPrefix)
        }
    }

    private fun changedInUpdate(differencesByDependency: Map<String, DependencyDiff>, element: Path): Boolean {
        return differencesByDependency.containsKey(element.dependency.selected.moduleVersion?.module.toString())
    }

    private fun isSubmodule(dependencyResult: ResolvedDependencyResult): Boolean {
        return dependencyResult.selected.id is ProjectComponentIdentifier
    }

    private fun isWinnerOfConflictResolution(element: Path): Boolean {
        return if (element.dependency.selected.selectionReason.isConflictResolution() &&
                element.dependency.requested is ModuleComponentSelector &&
                element.dependency.selected.id is ModuleComponentIdentifier) {
            val selector = VERSION_SCHEME.parseSelector((element.dependency.requested as ModuleComponentSelector).version)
            selector.accept((element.dependency.selected.id as ModuleComponentIdentifier).version)
        } else
            false
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