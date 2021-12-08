package nebula.plugin.dependencylock

import groovy.json.JsonSlurper
import nebula.plugin.dependencylock.util.LockGenerator
import nebula.test.IntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraph
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder
import nebula.test.dependencies.maven.Pom
import nebula.test.dependencies.maven.ArtifactType
import nebula.test.dependencies.repositories.MavenRepo

class PathAwareDependencyDiffSpec extends IntegrationTestKitSpec {

    private File repoDir

    def setup() {
        debug = true

        def myGraph = [
                'test.example:foo:1.0.0 -> test.example:baz:1.0.0|test.example:direct-dependency-updated-transitively:1.0.0|test.example:direct-dependency-updating-transitive:2.0.0|test.example:removed-dependency:1.0.0',
                'test.example:foo:1.0.1 -> test.example:baz:1.0.0|test.example:direct-dependency-updated-transitively:1.0.0|test.example:direct-dependency-updating-transitive:2.0.0|test.example:removed-dependency:1.0.0',
                'test.example:foo:2.0.1 -> test.example:baz:1.0.0|test.example:direct-dependency-updated-transitively:1.1.0|test.example:direct-dependency-updating-transitive:2.0.0',
                'test.example:removed-dependency:1.0.0',
                'test.example:bar:1.0.0 -> test.example:foo:1.0.0',
                'test.example:baz:1.0.0',
                'test.example:qux:1.0.0 -> test.example:foo:1.0.1',
                'test.example:qux:2.0.0 -> test.example:foo:2.0.1|test.example:new-dependency:1.0.0',
                'test.example:new-dependency:1.0.0',
                'test.example:updated-by-rule-dependency:1.0.0',
                'test.example:updated-by-rule-dependency:2.0.0',
                'test.example:updated-by-rule-dependency-consumer:1.0.0 -> test.example:updated-by-rule-dependency:1.0.0',
                'test.example:direct-dependency-updated-transitively:1.0.0',
                'test.example:direct-dependency-updated-transitively:1.1.0',
                'test.example:direct-dependency-updating-transitive:2.0.0',
                'test.example:direct-dependency-updating-transitive:2.2.0',
                'test.example.alignment:core-library:1.0.0',
                'test.example.alignment:core-library:2.0.0',
                'test.example.alignment:core2-library:1.0.0',
                'test.example.alignment:core2-library:2.0.0',
                'test.example.alignment:consumer1-library:1.0.0 -> test.example.alignment:core-library:1.0.0',
                'test.example.alignment:consumer1-library:2.0.0 -> test.example.alignment:core-library:2.0.0',
                'test.example.alignment:consumer2-library:1.0.0 -> test.example.alignment:core2-library:1.0.0',
                'test.example.alignment:consumer2-library:2.0.0 -> test.example.alignment:core2-library:2.0.0',
                'test.example:consumer-of-aligned-dependency1:1.0.0 -> test.example.alignment:consumer1-library:1.0.0',
                'test.example:consumer-of-aligned-dependency1:2.0.0 -> test.example.alignment:consumer1-library:1.0.0',
                'test.example:consumer-of-aligned-dependency2:1.0.0 -> test.example.alignment:consumer2-library:1.0.0',
                'test.example:consumer-of-aligned-dependency2:2.0.0 -> test.example.alignment:consumer2-library:2.0.0',
                'test.example:consumer-of-replaced:1.0.0 -> test.example:replaced:1.0.0',
                'test.example:consumer-of-replacer:1.0.0 -> test.example:replacer:1.0.0',
                'test.example:replaced:1.0.0',
                'test.example:replacer:1.0.0',
                'test.example:constrained-dependency:1.0.0',
                'test.example:constrained-dependency:2.0.0',
                'test.example:constrained-consumer:1.0.0 -> test.example:constrained-dependency:2.0.0',
                'test.example:transitive-dependency:1.0.0',
                'test.example:transitive-dependency:2.0.0',
                'test.example:transitive-consumer1:1.0.0 -> test.example:transitive-dependency:1.0.0',
                'test.example:transitive-consumer1:2.0.0 -> test.example:transitive-dependency:1.0.0',
                'test.example:transitive-consumer2:1.0.0 -> test.example:transitive-dependency:1.0.0',
                'test.example:transitive-consumer2:2.0.0 -> test.example:transitive-dependency:2.0.0',
                'test.example:transitive-consumer3:1.0.0 -> test.example:transitive-consumer2:1.0.0',
                'test.example:transitive-consumer3:2.0.0 -> test.example:transitive-consumer2:2.0.0',
                'test.example:transitive-consumer4:1.0.0 -> test.example:transitive-dependency:1.0.0',
                'test.example:transitive-consumer4:2.0.0 -> test.example:transitive-dependency:2.0.0',
                'some.group:dependency:1.0.0',
                'some.group:dependency:2.0.0',
                'test.example:dependency1:1.0.0',
                'test.example:dependency1:2.0.0',
                'test.example:dependency2:1.0.0',
                'test.example:dependency2:2.0.0',
                'test.example:cycle1:1.0.0',
                'test.example:cycle1:2.0.0 -> test.example:cycle2:2.0.0',
                'test.example:cycle2:1.0.0',
                'test.example:cycle2:2.0.0 -> test.example:cycle1:2.0.0',
        ]

        def generator = new GradleDependencyGenerator(new DependencyGraph(myGraph))
        generator.generateTestMavenRepo()
        repoDir = generator.getMavenRepoDir()
    }

    /* Scenarios:
       - `qux` direct dependencies that gets updated and brings updated `foo` that keeps the same version of `baz`
         - `qux` is shown, `foo` under qux is show, unchanged `baz` dependency of `foo` is not shown
         - `bar` has updated `foo` by conflict resolution but it is not shown
       - `qux` bring a new transitive dependency `new-dependency`
         - `new-dependency` is shown under `qux`
       - `updated-by-rule-dependency` is updated by a rule without any direct dependency change.
         - a path that leads to `updated-by-rule-dependency` is shown
       - 'removed-dependency' is not present after dependency update
     */
    def 'diff lock with paths'() {
        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.pathAwareDependencyDiff=true"
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                   "test.example:bar": {
                        "locked": "1.0.0"
                    },
                    "test.example:qux": {
                        "locked": "1.0.0"
                    },
                    "test.example:foo": {
                        "locked": "1.0.1",
                        "transitive": [
                            "test.example:bar",
                            "test.example:qux"
                        ]
                    },
                    "test.example:baz": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foo"
                        ]
                    },
                    "test.example:direct-dependency-updated-transitively": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foo"
                        ]
                    },
                    "test.example:removed-dependency": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foo"
                        ]
                    },
                    "test.example:direct-dependency-updating-transitive": {
                        "locked": "2.0.0",
                        "transitive": [
                            "test.example:foo"
                        ]
                    },
                    "test.example:updated-by-rule-dependency-consumer": {
                        "locked": "1.0.0"
                    },
                    "test.example:updated-by-rule-dependency": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:updated-by-rule-dependency-consumer"
                        ]
                    }
                '''.stripIndent())
            buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
            }
        
            apply plugin: 'java'
            repositories { maven { url '${repoDir.absolutePath}' } }

            dependencyLock {
                includeTransitives = true
            }
            
            configurations.all {
                resolutionStrategy {
                    dependencySubstitution {
                        substitute(module('test.example:updated-by-rule-dependency:1.0.0'))
                            .because("substitue test.example:updated-by-rule-dependency:1.0.0 with 2.0.0 because JIRA-1039")
                            .using(module('test.example:updated-by-rule-dependency:2.0.0'))
                            
                    }
                }
            }

            dependencies {
                implementation 'test.example:qux:latest.release'
                implementation 'test.example:bar:1.0.0'
                implementation 'test.example:updated-by-rule-dependency-consumer:1.0.0'
                implementation 'test.example:direct-dependency-updated-transitively:1.0.0'
                implementation 'test.example:direct-dependency-updating-transitive:latest.release'
            }
        """.stripIndent()

        when:
        def result = runTasks('generateLock', 'diffLock')

        then:
        def lockdiff = new JsonSlurper().parse(new File(projectDir, 'build/dependency-lock/lockdiff.json'))
        def allConfigurations = lockdiff[0]
        allConfigurations["configurations"].containsAll(["compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath"])
        allConfigurations["removed"].contains("test.example:removed-dependency")
        def directDependencies = allConfigurations["differentPaths"]
        def directTransitive = directDependencies.find { it.dependency == "test.example:direct-dependency-updating-transitive"}
        directTransitive.version == "2.2.0"
        directTransitive.selectionReasons["REQUESTED"] == "requested"
        directTransitive.selectionReasons["CONFLICT_RESOLUTION"] == "between versions 2.2.0 and 2.0.0"
        directTransitive.change.description == "requested; the parent brought the winner of conflict resolution"
        directTransitive.change.type == "UPDATED"
        directTransitive.change.previousVersion == "2.0.0"
        def ruleUpdateConsumer = directDependencies.find { it.dependency == "test.example:updated-by-rule-dependency-consumer"}
        ruleUpdateConsumer.version == "1.0.0"
        ruleUpdateConsumer.change == null
        ruleUpdateConsumer.children[0].dependency == "test.example:updated-by-rule-dependency"
        ruleUpdateConsumer.children[0].version == "2.0.0"
        ruleUpdateConsumer.children[0].change.description == "requested; substitue test.example:updated-by-rule-dependency:1.0.0 with 2.0.0 because JIRA-1039"
        ruleUpdateConsumer.children[0].change.type == "UPDATED"
        ruleUpdateConsumer.children[0].change.previousVersion == "1.0.0"
        def qux = directDependencies.find { it.dependency == "test.example:qux"}
        qux.version == "2.0.0"
        qux.change.description == "requested"
        qux.change.type == "UPDATED"
        qux.change.previousVersion == "1.0.0"
        def foo = qux.children.find { it.dependency == "test.example:foo" }
        foo.version == "2.0.1"
        foo.change.description == "requested; the parent brought the winner of conflict resolution"
        foo.change.type == "UPDATED"
        foo.change.previousVersion == "1.0.1"
        foo.children[0].dependency == "test.example:direct-dependency-updated-transitively"
        foo.children[0].version == "1.1.0"
        foo.children[0].change.description == "requested; the parent brought the winner of conflict resolution"
        foo.children[0].change.type == "UPDATED"
        foo.children[0].change.previousVersion == "1.0.0"
        def newDependency = qux.children.find { it.dependency == "test.example:new-dependency" }
        newDependency.version == "1.0.0"
        newDependency.change.description == "requested"
        newDependency.change.type == "NEW"
    }

    def 'diff lock with paths with recommendation'() {
        def repo = new MavenRepo()
        repo.root = new File(projectDir, 'build/bomrepo')
        def pom = new Pom('test.nebula.bom', 'testbom', '1.0.0', ArtifactType.POM)
        pom.addManagementDependency('test.example', 'qux', '2.0.0')
        repo.poms.add(pom)
        repo.generate()
        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.pathAwareDependencyDiff=true"
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                    "test.example:qux": {
                        "locked": "1.0.0"
                    },
                    "test.example:foo": {
                        "locked": "1.0.1",
                        "transitive": [
                            "test.example:qux"
                        ]
                    },
                    "test.example:baz": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foo"
                        ]
                    },
                    "test.example:direct-dependency-updated-transitively": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foo"
                        ]
                    },
                    "test.example:direct-dependency-updating-transitive": {
                        "locked": "2.0.0",
                        "transitive": [
                            "test.example:foo"
                        ]
                    }
                '''.stripIndent())
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id 'nebula.dependency-recommender' version '10.0.0'
            }
        
            apply plugin: 'java'
            repositories { 
                maven { url '${repo.root.absoluteFile.toURI()}' }
                maven { url '${repoDir.absolutePath}' }
            }

            dependencyLock {
                includeTransitives = true
            }
            
            dependencyRecommendations {
                mavenBom module: 'test.nebula.bom:testbom:latest.release'
            }

            dependencies {
                implementation 'test.example:qux'
            }
        """.stripIndent()

        when:
        def result = runTasks('generateLock', 'diffLock')

        then:
        def lockdiff = new JsonSlurper().parse(new File(projectDir, 'build/dependency-lock/lockdiff.json'))
        def allConfigurations = lockdiff.find { it.configurations.contains("compileClasspath")}
        def directDependencies = allConfigurations["differentPaths"]
        def qux = directDependencies.find { it.dependency == "test.example:qux"}
        qux.version == "2.0.0"
        qux.change.description == "requested; Recommending version 2.0.0 for dependency test.example:qux via conflict resolution recommendation\n\twith reasons: nebula.dependency-recommender uses mavenBom: test.nebula.bom:testbom:pom:1.0.0"
        qux.change.type == "UPDATED"
        qux.change.previousVersion == "1.0.0"
    }

    def 'diff lock with paths with forced alignment'() {
        File rulesJsonFile = new File(projectDir, "${moduleName}.json")
        rulesJsonFile << '''\
            {
                "deny": [], "reject": [], "substitute": [], "replace": [],
                "align": [
                    {
                        "name": "testNebula",
                        "group": "test.example.alignment",
                        "reason": "Align test.example.alignment dependencies",
                        "author": "Example Person <person@example.org>",
                        "date": "2016-03-17T20:21:20.368Z"
                    }
                ]
            }
        '''.stripIndent()

        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.pathAwareDependencyDiff=true"
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                    "test.example.alignment:consumer2-library": {
                        "locked": "2.0.0"
                    },
                    "test.example.alignment:consumer1-library": {
                        "locked": "2.0.0"
                    },
                    "test.example.alignment:core-library": {
                        "locked": "2.0.0",
                        "transitive": [
                            "test.example.alignment:consumer1-library"
                        ]
                    },
                    "test.example.alignment:core2-library": {
                        "locked": "2.0.0",
                        "transitive": [
                            "test.example.alignment:consumer2-library"
                        ]
                    }
                '''.stripIndent())
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id "nebula.resolution-rules" version "9.0.0"
            }
        
            apply plugin: 'java'
            repositories { 
                maven { url '${repoDir.absolutePath}' }
            }

            dependencyLock {
                includeTransitives = true
            }
            
            configurations.all {
                resolutionStrategy {
                    force 'test.example.alignment:core-library:1.0.0'
                }
            }

            dependencies {
                resolutionRules files('$rulesJsonFile')
                implementation 'test.example.alignment:consumer1-library:2.0.0'
                implementation 'test.example.alignment:consumer2-library:2.0.0'
            }
        """.stripIndent()

        when:
        def result = runTasks('generateLock', 'diffLock')

        then:
        def lockdiff = new JsonSlurper().parse(new File(projectDir, 'build/dependency-lock/lockdiff.json'))
        def allConfigurations = lockdiff.find { it.configurations.contains("compileClasspath")}
        def directDependencies = allConfigurations["differentPaths"]
        def consumer1 = directDependencies.find { it.dependency == "test.example.alignment:consumer1-library"}
        consumer1.version == "1.0.0"
        consumer1.change.description == "requested; forced; belongs to platform aligned-platform:diff-lock-with-paths-with-forced-alignment-0-for-test.example.alignment:1.0.0"
        consumer1.change.type == "UPDATED"
        consumer1.change.previousVersion == "2.0.0"
        consumer1.children[0].dependency == "test.example.alignment:core-library"
        consumer1.children[0].version == "1.0.0"
        consumer1.children[0].change.description == "requested; forced; belongs to platform aligned-platform:diff-lock-with-paths-with-forced-alignment-0-for-test.example.alignment:1.0.0"
        consumer1.children[0].change.type == "UPDATED"
        consumer1.children[0].change.previousVersion == "2.0.0"
        def consumer2 = directDependencies.find { it.dependency == "test.example.alignment:consumer2-library"}
        consumer2.version == "1.0.0"
        consumer2.change.description == "requested; forced; belongs to platform aligned-platform:diff-lock-with-paths-with-forced-alignment-0-for-test.example.alignment:1.0.0"
        consumer2.change.type == "UPDATED"
        consumer2.change.previousVersion == "2.0.0"
        consumer2.children[0].dependency == "test.example.alignment:core2-library"
        consumer2.children[0].version == "1.0.0"
        consumer2.children[0].change.description == "requested; forced; belongs to platform aligned-platform:diff-lock-with-paths-with-forced-alignment-0-for-test.example.alignment:1.0.0"
        consumer2.children[0].change.type == "UPDATED"
        consumer2.children[0].change.previousVersion == "2.0.0"
    }

    def 'diff lock with paths with alignment without clear conflict resolution winner'() {
        File rulesJsonFile = new File(projectDir, "${moduleName}.json")
        rulesJsonFile << '''\
            {
                "deny": [], "reject": [], "substitute": [], "replace": [],
                "align": [
                    {
                        "name": "testNebula",
                        "group": "test.example.alignment",
                        "reason": "Align test.example.alignment dependencies",
                        "author": "Example Person <person@example.org>",
                        "date": "2016-03-17T20:21:20.368Z"
                    }
                ]
            }
        '''.stripIndent()

        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.pathAwareDependencyDiff=true"
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                    "test.example:consumer-of-aligned-dependency1": {
                        "locked": "1.0.0"
                    },
                    "test.example:consumer-of-aligned-dependency2": {
                        "locked": "1.0.0"
                    },
                    "test.example.alignment:consumer1-library": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:consumer-of-aligned-dependency1"
                        ]
                    },
                    "test.example.alignment:consumer2-library": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:consumer-of-aligned-dependency2"
                        ]
                    },
                    "test.example.alignment:core-library": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example.alignment:consumer1-library"
                        ]
                    },
                    "test.example.alignment:core2-library": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example.alignment:consumer2-library"
                        ]
                    }
                '''.stripIndent())
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id "nebula.resolution-rules" version "9.0.0"
            }
        
            apply plugin: 'java'
            repositories { 
                maven { url '${repoDir.absolutePath}' }
            }

            dependencyLock {
                includeTransitives = true
            }

            dependencies {
                resolutionRules files('$rulesJsonFile')
                implementation 'test.example:consumer-of-aligned-dependency1:2.0.0'
                implementation 'test.example:consumer-of-aligned-dependency2:2.0.0'
            }
        """.stripIndent()

        when:
        def result = runTasks('generateLock', 'diffLock')

        then:
        def lockdiff = new JsonSlurper().parse(new File(projectDir, 'build/dependency-lock/lockdiff.json'))
        def allConfigurations = lockdiff.find { it.configurations.contains("compileClasspath")}
        def directDependencies = allConfigurations["differentPaths"]
        def alignedConsumer1 = directDependencies.find { it.dependency == "test.example:consumer-of-aligned-dependency1"}
        alignedConsumer1.version == "2.0.0"
        alignedConsumer1.change.description == "requested"
        alignedConsumer1.change.type == "UPDATED"
        alignedConsumer1.change.previousVersion == "1.0.0"
        def consumer1 = alignedConsumer1.children.find { it.dependency == "test.example.alignment:consumer1-library"}
        consumer1.requestedVersion == "1.0.0"
        consumer1.version == "2.0.0"
        consumer1.change.description == "requested; the parent brought this participant in conflict resolution, but the winner is from a different path; belongs to platform aligned-platform:diff-lock-with-paths-with-alignment-without-clear-conflict-resolution-winner-0-for-test.example.alignment:2.0.0"
        consumer1.change.type == "UPDATED"
        consumer1.change.previousVersion == "1.0.0"
        consumer1.children[0].dependency == "test.example.alignment:core-library"
        consumer1.children[0].version == "2.0.0"
        consumer1.children[0].change.description == "requested; belongs to platform aligned-platform:diff-lock-with-paths-with-alignment-without-clear-conflict-resolution-winner-0-for-test.example.alignment:2.0.0"
        consumer1.children[0].change.type == "UPDATED"
        consumer1.children[0].change.previousVersion == "1.0.0"
        def alignedConsumer2 = directDependencies.find { it.dependency == "test.example:consumer-of-aligned-dependency2"}
        alignedConsumer2.version == "2.0.0"
        alignedConsumer2.change.description == "requested"
        alignedConsumer2.change.type == "UPDATED"
        alignedConsumer2.change.previousVersion == "1.0.0"
        def consumer2 = alignedConsumer2.children.find { it.dependency == "test.example.alignment:consumer2-library"}
        consumer2.version == "2.0.0"
        consumer2.change.description == "requested; belongs to platform aligned-platform:diff-lock-with-paths-with-alignment-without-clear-conflict-resolution-winner-0-for-test.example.alignment:2.0.0"
        consumer2.change.type == "UPDATED"
        consumer2.change.previousVersion == "1.0.0"
        consumer2.children[0].dependency == "test.example.alignment:core2-library"
        consumer2.children[0].version == "2.0.0"
        consumer2.children[0].change.description == "requested; belongs to platform aligned-platform:diff-lock-with-paths-with-alignment-without-clear-conflict-resolution-winner-0-for-test.example.alignment:2.0.0"
        consumer2.children[0].change.type == "UPDATED"
        consumer2.children[0].change.previousVersion == "1.0.0"
    }

    def 'diff lock with paths with alignment and constraint to have multiple descriptions of the same cause'() {
        File rulesJsonFile = new File(projectDir, "${moduleName}.json")
        rulesJsonFile << '''\
            {
                "deny": [], "reject": [], "substitute": [], "replace": [],
                "align": [
                    {
                        "name": "testNebula",
                        "group": "test.example.alignment",
                        "reason": "Align test.example.alignment dependencies",
                        "author": "Example Person <person@example.org>",
                        "date": "2016-03-17T20:21:20.368Z"
                    }
                ]
            }
        '''.stripIndent()

        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.pathAwareDependencyDiff=true"
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                    "test.example:consumer-of-aligned-dependency1": {
                        "locked": "1.0.0"
                    },
                    "test.example:consumer-of-aligned-dependency2": {
                        "locked": "1.0.0"
                    },
                    "test.example.alignment:consumer1-library": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:consumer-of-aligned-dependency1"
                        ]
                    },
                    "test.example.alignment:consumer2-library": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:consumer-of-aligned-dependency2"
                        ]
                    },
                    "test.example.alignment:core-library": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example.alignment:consumer1-library"
                        ]
                    },
                    "test.example.alignment:core2-library": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example.alignment:consumer2-library"
                        ]
                    }
                '''.stripIndent())
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id "nebula.resolution-rules" version "9.0.0"
            }
        
            apply plugin: 'java'
            repositories { 
                maven { url '${repoDir.absolutePath}' }
            }

            dependencyLock {
                includeTransitives = true
            }

            dependencies {
                constraints {
                    implementation 'test.example.alignment:consumer1-library:2.0.0'
                }
                resolutionRules files('$rulesJsonFile')
                implementation 'test.example:consumer-of-aligned-dependency1:2.0.0'
                implementation 'test.example:consumer-of-aligned-dependency2:2.0.0'
            }
        """.stripIndent()

        when:
        def result = runTasks('generateLock', 'diffLock')

        then:
        def lockdiff = new JsonSlurper().parse(new File(projectDir, 'build/dependency-lock/lockdiff.json'))
        def allConfigurations = lockdiff.find { it.configurations.contains("compileClasspath")}
        def directDependencies = allConfigurations["differentPaths"]
        def alignedConsumer1 = directDependencies.find { it.dependency == "test.example:consumer-of-aligned-dependency1"}
        alignedConsumer1.version == "2.0.0"
        alignedConsumer1.change.description == "requested"
        alignedConsumer1.change.type == "UPDATED"
        alignedConsumer1.change.previousVersion == "1.0.0"
        def consumer1 = alignedConsumer1.children.find { it.dependency == "test.example.alignment:consumer1-library"}
        consumer1.requestedVersion == "1.0.0"
        consumer1.version == "2.0.0"
        consumer1.selectionReasons["CONSTRAINT"] == "belongs to platform aligned-platform:diff-lock-with-paths-with-alignment-and-constraint-to-have-multiple-descriptions-of-the-same-cause-0-for-test.example.alignment:2.0.0; constraint"
        consumer1.change.type == "UPDATED"
        consumer1.change.previousVersion == "1.0.0"
        def alignedConsumer2 = directDependencies.find { it.dependency == "test.example:consumer-of-aligned-dependency2"}
        alignedConsumer2.version == "2.0.0"
        alignedConsumer2.change.description == "requested"
        alignedConsumer2.change.type == "UPDATED"
        alignedConsumer2.change.previousVersion == "1.0.0"
        def consumer2 = alignedConsumer2.children.find { it.dependency == "test.example.alignment:consumer2-library"}
        consumer2.version == "2.0.0"
        consumer2.change.description == "requested; belongs to platform aligned-platform:diff-lock-with-paths-with-alignment-and-constraint-to-have-multiple-descriptions-of-the-same-cause-0-for-test.example.alignment:2.0.0"
        consumer2.change.type == "UPDATED"
        consumer2.change.previousVersion == "1.0.0"
        consumer2.children[0].dependency == "test.example.alignment:core2-library"
        consumer2.children[0].version == "2.0.0"
        consumer2.children[0].change.description == "requested; belongs to platform aligned-platform:diff-lock-with-paths-with-alignment-and-constraint-to-have-multiple-descriptions-of-the-same-cause-0-for-test.example.alignment:2.0.0"
        consumer2.children[0].change.type == "UPDATED"
        consumer2.children[0].change.previousVersion == "1.0.0"
    }

    def 'diff lock with paths with constrained dependency'() {
        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.pathAwareDependencyDiff=true"
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                    "test.example:constrained-consumer": {
                        "locked": "1.0.0"
                    },
                    "test.example:constrained-dependency": {
                        "locked": "2.0.0",
                        "transitive": [
                            "test.example:constrained-consumer"\
                        ]
                    }
                '''.stripIndent())
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id "nebula.resolution-rules" version "9.0.0"
            }
        
            apply plugin: 'java'
            repositories { 
                maven { url '${repoDir.absolutePath}' }
            }

            dependencyLock {
                includeTransitives = true
            }

            dependencies {
                constraints {
                    implementation('test.example:constrained-dependency') {
                        version {
                            strictly '1.0.0'
                        }
                    }
                }
                implementation 'test.example:constrained-consumer:1.0.0'
            }
        """.stripIndent()

        when:
        def result = runTasks('generateLock', 'diffLock')

        then:
        def lockdiff = new JsonSlurper().parse(new File(projectDir, 'build/dependency-lock/lockdiff.json'))
        def allConfigurations = lockdiff.find { it.configurations.contains("compileClasspath")}
        def directDependencies = allConfigurations["differentPaths"]
        def consumer = directDependencies.find { it.dependency == "test.example:constrained-consumer"}
        consumer.version == "1.0.0"
        consumer.change == null
        consumer.children[0].dependency == "test.example:constrained-dependency"
        consumer.children[0].version == "1.0.0"
        consumer.children[0].change.type == "UPDATED"
        consumer.children[0].change.previousVersion == "2.0.0"
        consumer.children[0].change.description == "constraint; by ancestor"
    }

    def 'diff lock with paths with repeated dependencies'() {
        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.pathAwareDependencyDiff=true"
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                    "test.example:transitive-consumer1": {
                        "locked": "1.0.0"
                    },
                    "test.example:transitive-consumer3": {
                        "locked": "1.0.0"
                    },
                    "test.example:transitive-consumer4": {
                        "locked": "1.0.0"
                    },
                    "test.example:transitive-consumer2": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:transitive-consumer3"
                        ]
                    },
                    "test.example:transitive-dependency": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:transitive-consumer1",
                            "test.example:transitive-consumer2",
                            "test.example:transitive-consumer4"
                        ]
                    }
                '''.stripIndent())
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id "nebula.resolution-rules" version "9.0.0"
            }
        
            apply plugin: 'java'
            repositories { 
                maven { url '${repoDir.absolutePath}' }
            }

            dependencyLock {
                includeTransitives = true
            }

            dependencies {
                implementation 'test.example:transitive-consumer1:2.0.0'
                implementation 'test.example:transitive-consumer3:2.0.0'
                implementation 'test.example:transitive-consumer4:2.0.0'
            }
        """.stripIndent()

        when:
        def result = runTasks('generateLock', 'diffLock')

        then:
        def lockdiff = new JsonSlurper().parse(new File(projectDir, 'build/dependency-lock/lockdiff.json'))
        def allConfigurations = lockdiff.find { it.configurations.contains("compileClasspath")}
        def directDependencies = allConfigurations["differentPaths"]
        def consumer = directDependencies.find { it.dependency == "test.example:transitive-consumer4"}
        consumer.version == "2.0.0"
        consumer.children[0].dependency == "test.example:transitive-dependency"
        consumer.children[0].version == "2.0.0"
        consumer.children[0].change.type == "UPDATED"
        consumer.children[0].change.previousVersion == "1.0.0"
        consumer.children[0].repeated == true
    }

    def 'diff lock with paths with replaced rules'() {
        File rulesJsonFile = new File(projectDir, "${moduleName}.json")
        rulesJsonFile << '''\
            {
                "deny": [], "reject": [], "substitute": [], "align": [],
                "replace" : [
                    {
                        "module" : "test.example:replaced",
                        "with" : "test.example:replacer",
                        "reason" : "replaced is duplicate of replacer",
                        "author" : "Example Person <person@example.org>",
                        "date" : "2015-10-07T20:21:20.368Z"
                    }
                ]
            }
        '''.stripIndent()

        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.pathAwareDependencyDiff=true"
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                    "test.example:consumer-of-replaced": {
                        "locked": "1.0.0"
                    },
                    "test.example:consumer-of-replacer": {
                        "locked": "1.0.0"
                    },
                    "test.example:replaced": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:consumer-of-replaced"
                        ]
                    },
                    "test.example:replacer": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:consumer-of-replacer"
                        ]
                    }
                '''.stripIndent())
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id "nebula.resolution-rules" version "9.0.0"
            }
        
            apply plugin: 'java'
            repositories { 
                maven { url '${repoDir.absolutePath}' }
            }

            dependencyLock {
                includeTransitives = true
            }

            dependencies {
                resolutionRules files('$rulesJsonFile')
                implementation 'test.example:consumer-of-replaced:1.0.0'
                implementation 'test.example:consumer-of-replacer:1.0.0'
            }
        """.stripIndent()

        when:
        def result = runTasks('generateLock', 'diffLock')

        then:
        def lockdiff = new JsonSlurper().parse(new File(projectDir, 'build/dependency-lock/lockdiff.json'))
        def allConfigurations = lockdiff[0]
        allConfigurations["removed"].contains("test.example:replaced")
    }

    def 'diff lock with paths including submodule'() {
        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.pathAwareDependencyDiff=true"
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
            }
        
            allprojects {
                apply plugin: 'java-library'
                apply plugin: 'nebula.dependency-lock'
                repositories { maven { url '${repoDir.absolutePath}' } }

                group = 'test'
                
                dependencyLock {
                    includeTransitives = true
                }
            }
        """.stripIndent()


        addSubproject("common", """
            dependencies {
                api 'test.example:foo:latest.release'
            }
        """)

        def dependenciesLockCommon = new File(projectDir, 'common/dependencies.lock')
        dependenciesLockCommon << LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                    "test.example:foo": {
                        "locked": "1.0.1"
                    },
                    "test.example:baz": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foo"
                        ]
                    },
                    "test.example:direct-dependency-updated-transitively": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foo"
                        ]
                    },
                    "test.example:removed-dependency": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foo"
                        ]
                    },
                    "test.example:direct-dependency-updating-transitive": {
                        "locked": "2.0.0",
                        "transitive": [
                            "test.example:foo"
                        ]
                    }
                '''.stripIndent())

        addSubproject("app", """
            dependencies {
                implementation project(':common')
                implementation "some.group:dependency:2.0.0"
            }
        """)

        def dependenciesLockApp = new File(projectDir, 'app/dependencies.lock')
        dependenciesLockApp << LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                    "some.group:dependency": {
                        "locked": "1.0.0"
                    },
                    "test:common": {
                        "project": true
                    },
                    "test.example:foo": {
                        "locked": "1.0.1",
                        "transitive": [
                            "test:common"
                        ]
                    },
                    "test.example:baz": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foo"
                        ]
                    },
                    "test.example:direct-dependency-updated-transitively": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foo"
                        ]
                    },
                    "test.example:removed-dependency": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foo"
                        ]
                    },
                    "test.example:direct-dependency-updating-transitive": {
                        "locked": "2.0.0",
                        "transitive": [
                            "test.example:foo"
                        ]
                    }
                '''.stripIndent())


        when:
        def result = runTasks('generateLock', 'diffLock')

        then:
        def lockdiff = new JsonSlurper().parse(new File(projectDir, 'app/build/dependency-lock/lockdiff.json'))
        def allConfigurations = lockdiff[0]
        def directDependencies = allConfigurations["differentPaths"]
        //verify the right order involving submodule
        def some = directDependencies[0]
        some.dependency == "some.group:dependency"
        def common = directDependencies[1]
        common.dependency == "test:common"
        common.submodule == true
        def foo = common.children.find { it.dependency == "test.example:foo" }
        foo.version == "2.0.1"
        foo.change.description == "requested"
        foo.change.type == "UPDATED"
        foo.change.previousVersion == "1.0.1"
        foo.children[0].dependency == "test.example:direct-dependency-updated-transitively"
        foo.children[0].version == "1.1.0"
        foo.children[0].change.description == "requested"
        foo.children[0].change.type == "UPDATED"
        foo.children[0].change.previousVersion == "1.0.0"
    }

    def 'diff lock with new submodule dependency'() {
        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.pathAwareDependencyDiff=true"
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
            }
        
            allprojects {
                apply plugin: 'java-library'
                apply plugin: 'nebula.dependency-lock'
                repositories { maven { url '${repoDir.absolutePath}' } }

                group = 'test'
                
                dependencyLock {
                    includeTransitives = true
                }
            }
        """.stripIndent()


        addSubproject("common", """
        """)

        addSubproject("app", """
            dependencies {
                implementation project(':common')
            }
        """)

        when:
        def result = runTasks('generateLock', 'diffLock')

        then:
        def lockdiff = new JsonSlurper().parse(new File(projectDir, 'app/build/dependency-lock/lockdiff.json'))
        def allConfigurations = lockdiff[0]
        def directDependencies = allConfigurations["differentPaths"]
        def common = directDependencies.find { it.dependency == "test:common"}
        common.submodule == true
        common.change.description == "new local submodule"
        common.change.type == "NEW"
    }

    def 'properly aggregate configurations with the same dependencies into report'() {
        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.pathAwareDependencyDiff=true"
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
            }
        
            apply plugin: 'java'
            repositories { 
                maven { url '${repoDir.absolutePath}' }
            }

            dependencyLock {
                includeTransitives = true
            }

            dependencies {
                compileOnly 'test.example:dependency1:2.0.0'
                runtimeOnly 'test.example:dependency2:2.0.0'
            }
        """.stripIndent()

        when:
        def result = runTasks('generateLock', 'diffLock')

        then:
        def lockdiff = new JsonSlurper().parse(new File(projectDir, 'build/dependency-lock/lockdiff.json'))
        def directDependenciesCompileClasspath = lockdiff.find { it.configurations.contains("compileClasspath")} ["differentPaths"]
        def direct1 = directDependenciesCompileClasspath.find { it.dependency == "test.example:dependency1"}
        direct1.version == "2.0.0"
        direct1.change.type == "NEW"

        def directDependenciesRuntimeClasspath = lockdiff.find { it.configurations.contains("runtimeClasspath")} ["differentPaths"]
        def direct2 = directDependenciesRuntimeClasspath.find { it.dependency == "test.example:dependency2"}
        direct2.version == "2.0.0"
        direct2.change.type == "NEW"
    }

    def 'diff lock with cyclic dependencies'() {
        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.pathAwareDependencyDiff=true"
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                    "test.example:cycle1": {
                        "locked": "1.0.0"
                    },
                    "test.example:cycle2": {
                        "locked": "1.0.0"
                    }
                '''.stripIndent())
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
                id "nebula.resolution-rules" version "9.0.0"
            }
        
            apply plugin: 'java'
            repositories { 
                maven { url '${repoDir.absolutePath}' }
            }

            dependencyLock {
                includeTransitives = true
            }

            dependencies {
                implementation 'test.example:cycle1:2.0.0'
                implementation 'test.example:cycle2:2.0.0'
            }
        """.stripIndent()

        when:
        def result = runTasks('generateLock', 'diffLock')

        then:
        def lockdiff = new JsonSlurper().parse(new File(projectDir, 'build/dependency-lock/lockdiff.json'))
        def allConfigurations = lockdiff[0]
        ! allConfigurations["differentPaths"].isEmpty()
    }

    def 'diff lock with snapshot dependencies'() {
        def myGraph= new DependencyGraphBuilder()
                .addModule("test.example:dependency-of-snapshot:2.0.0")
                .addModule(new ModuleBuilder("test.example:snapshot-dependency:1.0.0")
                        .setStatus("integration")
                        .addDependency("test.example:dependency-of-snapshot:2.0.0")
                        .build())
                .addModule(new ModuleBuilder("test.example:dependency-of-snapshot:2.0.0").setStatus("release").build())
                .addModule(new ModuleBuilder("test.example:direct-dependency:1.0.0")
                        .setStatus("release")
                        .addDependency("test.example:snapshot-dependency:1.0.0")
                        .build())
                .build()

        def generator = new GradleDependencyGenerator(myGraph)
        generator.generateTestIvyRepo()

        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.pathAwareDependencyDiff=true"
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                    "test.example:dependency-of-snapshot": {
                        "locked": "1.0.0"
                    },
                    "test.example:snapshot-dependency": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:dependency-of-snapshot"
                        ]
                    },
                    "test.example:direct-dependency": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:snapshot-dependency"
                        ]
                    }
                '''.stripIndent())
        buildFile << """\
            plugins {
                id 'nebula.dependency-lock'
            }
        
            apply plugin: 'java'
            repositories { 
                ${generator.getIvyRepositoryBlock()}
            }

            dependencyLock {
                includeTransitives = true
            }

            dependencies {
                implementation 'test.example:direct-dependency:1.0.0'
            }
        """.stripIndent()

        when:
        def result = runTasks('generateLock', 'diffLock')

        then:
        def lockdiff = new JsonSlurper().parse(new File(projectDir, 'build/dependency-lock/lockdiff.json'))
        def allConfigurations = lockdiff[0]
        def directDependencies = allConfigurations["differentPaths"]
        def direct = directDependencies[0]
        direct.dependency == "test.example:direct-dependency"
        direct.status == "release"
        direct.version == "1.0.0"
        direct.children[0].dependency == "test.example:snapshot-dependency"
        direct.children[0].status == "integration"
        direct.children[0].version == "1.0.0"
        def updatedChild = direct.children[0].children[0]
        updatedChild.dependency == "test.example:dependency-of-snapshot"
        updatedChild.status == "release"
        updatedChild.version == "2.0.0"
        updatedChild.change.description == "requested"
        updatedChild.change.type == "UPDATED"
        updatedChild.change.previousVersion == "1.0.0"
    }
}
