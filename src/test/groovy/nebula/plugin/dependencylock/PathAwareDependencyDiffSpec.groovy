package nebula.plugin.dependencylock

import nebula.plugin.dependencylock.util.LockGenerator
import nebula.test.IntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraph
import nebula.test.dependencies.GradleDependencyGenerator
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
                'test.example:consumer-of-replaced:1.0.0 -> test.example:replaced:1.0.0',
                'test.example:consumer-of-replacer:1.0.0 -> test.example:replacer:1.0.0',
                'test.example:replaced:1.0.0',
                'test.example:replacer:1.0.0',
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
        println(new File(projectDir, 'build/dependency-lock/lockdiff.json').text)
        true
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
        println(new File(projectDir, 'build/dependency-lock/lockdiff.json').text)
        true
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
                id "nebula.resolution-rules" version "7.8.9"
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
        println(new File(projectDir, 'build/dependency-lock/lockdiff.json').text)
        true
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
                id "nebula.resolution-rules" version "7.8.9"
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
        println(new File(projectDir, 'build/dependency-lock/lockdiff.json').text)
        true
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
            }
        """)

        def dependenciesLockApp = new File(projectDir, 'app/dependencies.lock')
        dependenciesLockApp << LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
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
        println(new File(projectDir, 'app/build/dependency-lock/lockdiff.json').text)
        true
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
        println(new File(projectDir, 'app/build/dependency-lock/lockdiff.json').text)
        true
    }
}
