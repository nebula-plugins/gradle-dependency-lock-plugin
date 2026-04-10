/**
 *
 *  Copyright 2026 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package nebula.plugin.dependencyverifier

import nebula.plugin.BaseIntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder
import spock.lang.Subject
import spock.lang.Unroll

/**
 * Performance baseline tests for the dependency resolution verifier.
 *
 * The verifier traverses the resolved dependency graph (currently twice: once for unresolved
 * detection, once for resolved coordinates). Projects with many thousands of dependencies
 * can be sensitive to this. These tests record a baseline and assert that verification
 * completes within acceptable limits so that future changes (e.g. single traversal) only
 * improve or maintain performance.
 *
 * Baseline thresholds are intentionally generous to avoid flakiness in CI; they can be
 * tightened after the "make it smaller" refactor (single traversal) is done.
 *
 * Recorded baseline (run with current implementation and update this comment when changing thresholds):
 * - Small (50-node chain):  &lt; 90s  (resolution + verification)
 * - Medium (200-node chain): &lt; 180s
 * - Large (500-node chain):  &lt; 420s  (or run only with -Dperf.includeLarge=true)
 *
 */
@Subject(DependencyResolutionVerifierKt)
class DependencyResolutionVerifierPerformanceTest extends BaseIntegrationTestKitSpec {

    def mavenrepo

    /**
     * Build a dependency graph that forms a single chain: root -> lib1 -> lib2 -> ... -> libN.
     * Resolution then traverses N nodes; the verifier traverses the resolved graph (currently 2x).
     */
    private static DependencyGraphBuilder buildChain(int length) {
        def builder = new DependencyGraphBuilder()
        if (length < 1) return builder
        builder.addModule("perf.verifier:lib-${length}:1.0.0")
        (length - 1).downto(1) { i ->
            builder.addModule(
                    new ModuleBuilder("perf.verifier:lib-${i}:1.0.0")
                            .addDependency("perf.verifier:lib-${i + 1}:1.0.0")
                            .build()
            )
        }
        builder
    }

    private void setupProjectWithChain(int chainLength) {
        def graph = buildChain(chainLength).build()
        mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen")
        mavenrepo.generateTestMavenRepo()

        buildFile << """\
            plugins {
                id 'com.netflix.nebula.dependency-lock'
                id 'java-library'
            }
            repositories {
                ${mavenrepo.mavenRepositoryBlock}
            }
            dependencies {
                implementation 'perf.verifier:lib-1:1.0.0'
            }
        """.stripIndent()

        writeHelloWorld()
    }

    @Unroll
    def 'verification completes within baseline (#description)'() {
        given:
        setupProjectWithChain(chainLength)
        def startNs = System.nanoTime()

        when:
        def results = runTasks(*tasks)
        def elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        then:
        !results.output.contains('FAILURE')
        elapsedMs < maxElapsedMs

        where:
        chainLength | tasks                                                   | maxElapsedMs | description
        10          | ['dependencies', '--configuration', 'compileClasspath'] | 90_000       | '10-node chain'
        50          | ['dependencies', '--configuration', 'compileClasspath'] | 90_000       | '50-node chain'
        100         | ['dependencies', '--configuration', 'compileClasspath'] | 120_000      | '100-node chain'
        200         | ['dependencies', '--configuration', 'compileClasspath'] | 180_000      | '200-node chain'
        50          | ['build']                                               | 120_000      | '50-node chain (build task)'
    }
}
