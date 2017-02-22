/*
 * Copyright 2014-2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License atpre5*4nu
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nebula.plugin.dependencylock

import groovy.json.JsonSlurper
import nebula.plugin.dependencylock.dependencyfixture.Fixture
import nebula.plugin.dependencylock.util.LockGenerator
import nebula.test.IntegrationSpec
import spock.lang.Ignore
import spock.lang.Issue

class DependencyLockLauncherSpec extends IntegrationSpec {
    def setup() {
        fork = false
    }

    static final String SPECIFIC_BUILD_GRADLE = """\
        apply plugin: 'java'
        apply plugin: 'nebula.dependency-lock'
        repositories { maven { url '${Fixture.repo}' } }
        dependencies {
            compile 'test.example:foo:1.0.1'
        }
    """.stripIndent()

    static final String BUILD_GRADLE = """\
        apply plugin: 'java'
        apply plugin: 'nebula.dependency-lock'
        repositories { maven { url '${Fixture.repo}' } }
        dependencies {
            compile 'test.example:foo:1.+'
        }
    """.stripIndent()

    static final String NEW_BUILD_GRADLE = """\
        apply plugin: 'java'
        apply plugin: 'nebula.dependency-lock'
        repositories { maven { url '${Fixture.repo}' } }
        dependencies {
            compile 'test.example:foo:2.+'
        }
    """.stripIndent()

    static final String OLD_FOO_LOCK = '''\
        {
            "compile": {
                "test.example:foo": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                }
            }
        }
    '''.stripIndent()

    static final String DEPRECATED_LOCK_FORMAT = '''\
        {
           "test.example:foo": { "locked": "1.0.0", "requested": "1.+" }
        }
    '''.stripIndent()

    static final String PRE_DIFF_FOO_LOCK = LockGenerator.duplicateIntoConfigs('''\
                "test.example:foo": {
                    "locked": "1.0.0",
                    "requested": "1.+"
                }
                '''.stripIndent())

    static final String FOO_LOCK = LockGenerator.duplicateIntoConfigs('''\
                "test.example:foo": {
                    "locked": "1.0.1",
                    "requested": "1.+"
                }
                '''.stripIndent())

    static final String NEW_FOO_LOCK = LockGenerator.duplicateIntoConfigs('''\
                "test.example:foo": {
                    "locked": "2.0.1",
                    "requested": "1.+",
                    "viaOverride": "2.0.1"
                }
                '''.stripIndent())

    static FOO_LOCK_OVERRIDE = '''\
        {
          "test.example:foo": "2.0.1"
        }
        '''.stripIndent()

    def setupSpec() {
        Fixture.createFixtureIfNotCreated()
    }

    def 'plugin allows normal gradle operation'() {
        buildFile << SPECIFIC_BUILD_GRADLE

        when:
        runTasksSuccessfully('build')

        then:
        noExceptionThrown()
    }

    def 'lock file is applied'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_FOO_LOCK

        buildFile << SPECIFIC_BUILD_GRADLE

        when:
        def result = runTasksSuccessfully('dependencies')

        then:
        result.standardOutput.contains 'test.example:foo:1.0.1 -> 1.0.0'
    }

    @Issue('#79')
    def 'lock file is not applied while generating lock with abbreviated task name'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_FOO_LOCK

        buildFile << SPECIFIC_BUILD_GRADLE

        when:
        def result = runTasksSuccessfully('gL', 'dependencies')

        then:

        !result.standardOutput.contains('test.example:foo:1.0.1 -> 1.0.0')
    }

    @Issue('#79')
    def 'lock file is not applied while generating lock with qualified task name'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_FOO_LOCK

        buildFile << SPECIFIC_BUILD_GRADLE

        when:
        def result = runTasksSuccessfully(':generateLock', 'dependencies')

        then:
        !result.standardOutput.contains('test.example:foo:1.0.1 -> 1.0.0')
    }

    @Issue('#79')
    def 'lock file is applied on subprojects not being locked while generating lock with qualified task name'() {
        setupCommonMultiproject()
        new File(projectDir, 'sub1/dependencies.lock').text = PRE_DIFF_FOO_LOCK
        new File(projectDir, 'sub2/dependencies.lock').text = PRE_DIFF_FOO_LOCK

        when:
        def result = runTasksSuccessfully('sub1:generateLock', ':sub2:dependencies')

        then:
        result.standardOutput.contains('test.example:foo:1.+ -> 1.0.0')
    }

    @Issue('#79')
    def 'lock file ignored in multiproject on specific project with no leading :'() {
        setupCommonMultiproject()
        new File(projectDir, 'sub1/dependencies.lock').text = PRE_DIFF_FOO_LOCK
        new File(projectDir, 'sub2/dependencies.lock').text = PRE_DIFF_FOO_LOCK

        when:
        def result = runTasksSuccessfully('sub1:generateLock', 'sub1:dependencies')

        then:
        !result.standardOutput.contains('test.example:foo:2.0.0 -> 1.0.0')
    }

    @Issue('#79')
    def 'lock file ignored in nested multiproject when generating locks'() {
        def middleDir = addSubproject('middle')
        def sub0Dir = new File(middleDir, 'sub0')
        sub0Dir.mkdirs()
        new File(sub0Dir, 'build.gradle').text = """\
            apply plugin: 'java'
            dependencies {
                compile 'test.example:foo:2.0.0'
            }
        """.stripIndent()
        new File(sub0Dir, 'dependencies.lock').text = PRE_DIFF_FOO_LOCK
        def sub1Dir = new File(middleDir, 'sub1')
        sub1Dir.mkdirs()
        new File(sub1Dir, 'build.gradle').text = """\
            apply plugin: 'java'
            dependencies {
                compile 'test.example:foo:1.+'
            }
        """.stripIndent()

        buildFile << """\
            allprojects {
                ${applyPlugin(DependencyLockPlugin)}
                group = 'test'
            }
            subprojects {
                repositories { maven { url '${Fixture.repo}' } }
            }
        """.stripIndent()

        settingsFile << '''\
            include ':middle:sub0'
            include ':middle:sub1'
        '''.stripIndent()

        when:
        def result = runTasksSuccessfully(':middle:sub0:generateLock', ':middle:sub0:dependencies')

        then:
        !result.standardOutput.contains('test.example:foo:2.0.0 -> 1.0.0')
    }

    def 'override lock file is applied'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_FOO_LOCK

        def testOverride = new File(projectDir, 'test.override')
        testOverride << FOO_LOCK

        def gradleProperties = new File(projectDir, 'gradle.properties')
        gradleProperties << 'dependencyLock.lockFile = \'test.override\''

        buildFile << BUILD_GRADLE

        when:
        def result = runTasksSuccessfully('dependencies')

        then:
        result.standardOutput.contains 'test.example:foo:1.+ -> 1.0.1'
    }

    def 'deprecated lock file is applied correctly and emits warning'() {
        def lockOverride = new File(projectDir, 'test.lock')
        lockOverride << DEPRECATED_LOCK_FORMAT

        buildFile << BUILD_GRADLE

        when:
        def s = runTasksSuccessfully('-PdependencyLock.overrideFile=test.lock', 'generateLock', 'saveLock')

        then:
        def outputDepLock = new JsonSlurper().parse(new File(projectDir, 'dependencies.lock'))

        // ensure all test.example:foo deps have been overridden to 1.0.0
        outputDepLock.each { conf, depMap ->
            depMap['test.example:foo'].viaOverride == '1.0.0'
        }

        s.standardOutput.contains('is using a deprecated format')
    }

    def 'create lock'() {
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('generateLock')

        then:
        new File(projectDir, 'build/dependencies.lock').text == FOO_LOCK
    }

    def 'create lock with skipped dependencies'() {
        buildFile << """\
            apply plugin: 'java'
            apply plugin: 'nebula.dependency-lock'
            repositories { maven { url '${Fixture.repo}' } }
            dependencyLock {
                skippedDependencies = [ 'test.example:foo' ]
            }
            dependencies {
                compile 'test.example:foo:2.+'
                compile 'test.example:baz:1.+'
            }
        """.stripIndent()

        def lockWithSkips = LockGenerator.duplicateIntoConfigs('''\
                "test.example:baz": {
                    "locked": "1.1.0",
                    "requested": "1.+"
                }
            '''.stripIndent())

        when:
        runTasksSuccessfully('generateLock')

        then:
        new File(projectDir, 'build/dependencies.lock').text == lockWithSkips
    }

    def 'update lock'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_FOO_LOCK
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('generateLock', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == FOO_LOCK
    }

    def 'run with generated lock'() {
        def generatedLock = new File(projectDir, 'build/dependencies.lock')
        generatedLock.parentFile.mkdirs()
        generatedLock << OLD_FOO_LOCK
        buildFile << BUILD_GRADLE

        when:
        def result0 = runTasksSuccessfully('dependencies')

        then:
        result0.standardOutput.contains 'test.example:foo:1.+ -> 1.0.1'

        when:
        def result1 = runTasksSuccessfully('-PdependencyLock.useGeneratedLock=true', 'dependencies')

        then:
        result1.standardOutput.contains "Using build/dependencies.lock to lock dependencies"
        result1.standardOutput.contains 'test.example:foo:1.+ -> 1.0.0'
    }


    def 'run with generated global lock in single project'() {
        def generatedLock = new File(projectDir, 'build/global.lock')
        generatedLock.parentFile.mkdirs()
        generatedLock << OLD_FOO_LOCK
        buildFile << BUILD_GRADLE

        when:
        def result0 = runTasksSuccessfully('dependencies')

        then:
        result0.standardOutput.contains 'test.example:foo:1.+ -> 1.0.1'

        when:
        def result1 = runTasksSuccessfully('-PdependencyLock.useGeneratedGlobalLock=true', 'dependencies')

        then:
        result1.standardOutput.contains "Using build/global.lock to lock dependencies"
        result1.standardOutput.contains 'test.example:foo:1.+ -> 1.0.0'
    }


    def 'run with generated global lock in multiproject'() {
        def generatedLock = new File(projectDir, 'build/global.lock')
        generatedLock.parentFile.mkdirs()
        generatedLock << OLD_FOO_LOCK

        addSubproject('sub1', """\
            dependencies {
                compile 'test.example:foo:2.+'
            }
        """.stripIndent())
        addSubproject('sub2', """\
            dependencies {
                compile 'test.example:foo:1.+'
            }
        """.stripIndent())

        buildFile << """\
            allprojects {
                ${applyPlugin(DependencyLockPlugin)}
                group = 'test'
            }
            subprojects {
                repositories { maven { url '${Fixture.repo}' } }
                apply plugin: 'java'
            }
        """.stripIndent()

        when:
        def result0 = runTasksSuccessfully(':sub1:dependencies')

        then:
        result0.standardOutput.contains 'test.example:foo:2.+ -> 2.0.1'

        when:
        def result1 = runTasksSuccessfully(':sub2:dependencies')

        then:
        result1.standardOutput.contains 'test.example:foo:1.+ -> 1.0.1'

        when:
        def result2 = runTasksSuccessfully('-PdependencyLock.useGeneratedGlobalLock=true', ':sub1:dependencies')

        then:
        result2.standardOutput.contains "Using ../build/global.lock to lock dependencies"
        result2.standardOutput.contains 'test.example:foo:2.+ -> 1.0.0'

        when:
        def result3 = runTasksSuccessfully('-PdependencyLock.useGeneratedGlobalLock=true', ':sub2:dependencies')

        then:
        result3.standardOutput.contains "Using ../build/global.lock to lock dependencies"
        result3.standardOutput.contains 'test.example:foo:1.+ -> 1.0.0'
    }

    def 'generateLock with deprecated format existing causes no issues'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << DEPRECATED_LOCK_FORMAT
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('generateLock')

        then:
        new File(projectDir, 'build/dependencies.lock').text == FOO_LOCK

        when:
        def result0 = runTasksSuccessfully('dependencies')

        then:
        result0.standardOutput.contains 'test.example:foo:1.+ -> 1.0.0'

        when:
        def result1 = runTasksSuccessfully('-PdependencyLock.useGeneratedLock=true', 'dependencies')

        then:
        result1.standardOutput.contains "Using build/dependencies.lock to lock dependencies"
        result1.standardOutput.contains 'test.example:foo:1.+ -> 1.0.1'
    }

    def 'trigger failure with bad lock file'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << '''\
            {
              key: {}
            }
        '''.stripIndent()
        buildFile << BUILD_GRADLE

        when:
        def result = runTasksWithFailure('dependencies')

        then:
        result.failure.cause.cause.message.contains('unreadable or invalid json')
    }

    def 'existing lock ignored while updating lock'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << NEW_FOO_LOCK
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('generateLock', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == FOO_LOCK
    }

    def 'command line override respected while updating lock'() {
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('-PdependencyLock.override=test.example:foo:2.0.1', 'generateLock', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == NEW_FOO_LOCK
    }

    def 'command line override file respected while updating lock'() {
        def testLock = new File(projectDir, 'test.lock')
        testLock << FOO_LOCK_OVERRIDE
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('-PdependencyLock.overrideFile=test.lock', 'generateLock', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == NEW_FOO_LOCK
    }

    def 'command line overrideFile fails if file is non existent'() {
        buildFile << BUILD_GRADLE

        when:
        def result = runTasksWithFailure('-PdependencyLock.overrideFile=test.lock', 'generateLock')

        then:
        result.failure != null
    }

    def 'multiple runs each generate a lock'() {
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('generateLock', 'saveLock')

        then:
        def savedLock = new File(projectDir, 'dependencies.lock')
        savedLock.text == FOO_LOCK

        buildFile << NEW_BUILD_GRADLE

        when:
        runTasksSuccessfully('generateLock')

        then:
        new File(projectDir, 'build/dependencies.lock').text != savedLock.text
    }

    def 'multiple runs of save lock works'() {
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('generateLock', 'saveLock')

        then:
        def savedLock = new File(projectDir, 'dependencies.lock')
        def firstRun = savedLock.text
        firstRun == FOO_LOCK

        buildFile << NEW_BUILD_GRADLE

        when:
        runTasksSuccessfully('generateLock', 'saveLock')

        then:
        savedLock.text != firstRun
    }

    def 'multiproject properly ignores unused overrides'() {
        def sub1 = new File(projectDir, 'sub1')
        sub1.mkdirs()
        def sub2 = new File(projectDir, 'sub2')
        sub2.mkdirs()

        buildFile << """\
            subprojects {
                apply plugin: 'java'
                apply plugin: 'nebula.dependency-lock'
                repositories { maven { url '${Fixture.repo}' } }
            }
        """.stripIndent()

        settingsFile << '''
            include 'sub1'
            include 'sub2'
        '''.stripIndent()

        new File(sub1, 'build.gradle') << '''\
            dependencies {
                compile 'test.example:foo:2.+'
            }
        '''.stripIndent()

        new File(sub2, 'build.gradle') << '''\
            dependencies {
                compile 'test.example:baz:1.+'
            }
        '''.stripIndent()

        def override = new File(projectDir, 'override.lock')
        override.text = '''\
            {
              "test.example:foo": "2.0.0",
              "test.example:baz": "1.0.0"
            }
        '''.stripIndent()

        when:
        runTasksSuccessfully('-PdependencyLock.overrideFile=override.lock', 'generateLock', 'saveLock')

        then:
        String lockText1 = LockGenerator.duplicateIntoConfigs('''\
                "test.example:foo": {
                    "locked": "2.0.0",
                    "requested": "2.+",
                    "viaOverride": "2.0.0"
                }
            '''.stripIndent())
        new File(sub1, 'dependencies.lock').text == lockText1
        String lockText2 = LockGenerator.duplicateIntoConfigs('''\
                "test.example:baz": {
                    "locked": "1.0.0",
                    "requested": "1.+",
                    "viaOverride": "1.0.0"
                }
            '''.stripIndent())
        new File(sub2, 'dependencies.lock').text == lockText2
    }

    def 'in multiproject allow applying to root project'() {
        addSubproject('sub1')
        addSubproject('sub2')

        buildFile << """\
            allprojects { ${applyPlugin(DependencyLockPlugin)} }
            subprojects { apply plugin: 'java' }
        """.stripIndent()

        when:
        runTasksSuccessfully('generateLock')

        then:
        noExceptionThrown()
    }

    def 'create global lock in singleproject'() {
        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('generateGlobalLock')

        then:
        new File(projectDir, 'build/global.lock').text == FOO_LOCK
    }

    def 'create global lock in multiproject'() {
        addSubproject('sub1', """\
            dependencies {
                compile 'test.example:bar:1.1.0'
                compile 'test.example:foo:2.0.0'
            }
        """.stripIndent())
        addSubproject('sub2', """\
            dependencies {
                compile 'test.example:transitive:1.+'
            }
        """.stripIndent())

        buildFile << """\
            allprojects {
                ${applyPlugin(DependencyLockPlugin)}
                group = 'test'
            }
            subprojects {
                apply plugin: 'java'
                repositories { maven { url '${Fixture.repo}' } }
            }
            dependencyLock {
                includeTransitives = true
            }
            configurations.all {
                resolutionStrategy {
                    force 'test.example:foo:2.0.1'
                }
            }
        """.stripIndent()

        when:
        runTasksSuccessfully('generateGlobalLock')

        then:
        String globalLockText = '''\
            {
                "_global_": {
                    "test.example:bar": {
                        "locked": "1.1.0",
                        "transitive": [
                            "test.example:transitive",
                            "test:sub1"
                        ]
                    },
                    "test.example:baz": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foobaz"
                        ]
                    },
                    "test.example:foo": {
                        "locked": "2.0.1",
                        "transitive": [
                            "test.example:bar",
                            "test.example:foobaz",
                            "test:sub1"
                        ]
                    },
                    "test.example:foobaz": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:transitive"
                        ]
                    },
                    "test.example:transitive": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test:sub2"
                        ]
                    },
                    "test:sub1": {
                        "project": true
                    },
                    "test:sub2": {
                        "project": true
                    }
                }
            }'''.stripIndent()
        new File(projectDir, 'build/global.lock').text == globalLockText
    }

    def 'create global lock in multiproject with force in subproject'() {
        addSubproject('sub1', """\
            dependencies {
                compile 'test.example:bar:1.1.0'
                compile 'test.example:foo:2.0.0'
            }
        """.stripIndent())
        addSubproject('sub2', """\
            dependencies {
                compile 'test.example:transitive:1.+'
            }
            configurations.all {
                resolutionStrategy {
                    force 'test.example:foo:2.0.1'
                }
            }
        """.stripIndent())

        buildFile << """\
            allprojects {
                ${applyPlugin(DependencyLockPlugin)}
                group = 'test'
            }
            subprojects {
                apply plugin: 'java'
                repositories { maven { url '${Fixture.repo}' } }
            }
            dependencyLock {
                includeTransitives = true
            }
        """.stripIndent()

        when:
        runTasksSuccessfully('generateGlobalLock')

        then:
        String globalLockText = '''\
            {
                "_global_": {
                    "test.example:bar": {
                        "locked": "1.1.0",
                        "transitive": [
                            "test.example:transitive",
                            "test:sub1"
                        ]
                    },
                    "test.example:baz": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:foobaz"
                        ]
                    },
                    "test.example:foo": {
                        "locked": "2.0.1",
                        "transitive": [
                            "test.example:bar",
                            "test.example:foobaz",
                            "test:sub1"
                        ]
                    },
                    "test.example:foobaz": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:transitive"
                        ]
                    },
                    "test.example:transitive": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test:sub2"
                        ]
                    },
                    "test:sub1": {
                        "project": true
                    },
                    "test:sub2": {
                        "project": true
                    }
                }
            }'''.stripIndent()
        new File(projectDir, 'build/global.lock').text == globalLockText
    }

    def 'uses chosen subproject configurations when creating global lock in multiproject'() {
        addSubproject('sub1', """\
            configurations {
                special
            }
            dependencies {
                compile 'test.example:bar:1.1.0'
                special 'test.example:foo:2.0.0'
            }
            dependencyLock.configurationNames = ['testRuntime', 'special']
        """.stripIndent())

        buildFile << """\
            allprojects {
                ${applyPlugin(DependencyLockPlugin)}
                group = 'test'
            }
            subprojects {
                apply plugin: 'java'
                repositories { maven { url '${Fixture.repo}' } }
            }
            dependencyLock {
                includeTransitives = true
            }
        """.stripIndent()

        when:
        runTasksSuccessfully('generateGlobalLock')

        then:
        String globalLockText = '''\
            {
                "_global_": {
                    "test.example:bar": {
                        "locked": "1.1.0",
                        "transitive": [
                            "test:sub1"
                        ]
                    },
                    "test.example:foo": {
                        "locked": "2.0.0",
                        "transitive": [
                            "test.example:bar",
                            "test:sub1"
                        ]
                    },
                    "test:sub1": {
                        "project": true
                    }
                }
            }'''.stripIndent()
        new File(projectDir, 'build/global.lock').text == globalLockText
    }

    def 'save global lock in multiproject'() {
        setupCommonMultiproject()

        when:
        runTasksSuccessfully('generateGlobalLock', 'saveGlobalLock')

        then:
        // Should this be an empty result? Check OneNote
        String globalLockText = '''\
            {
                "_global_": {
                    "test.example:foo": {
                        "locked": "2.0.0",
                        "transitive": [
                            "test:sub1",
                            "test:sub2"
                        ]
                    },
                    "test:sub1": {
                        "project": true
                    },
                    "test:sub2": {
                        "project": true
                    }
                }
            }'''.stripIndent()

        new File(projectDir, 'global.lock').text == globalLockText
    }

    def 'locks are correct when applying to all projects'() {
        setupCommonMultiproject()

        when:
        runTasksSuccessfully('generateLock', 'saveLock')

        then:
        String lockText = '''\
            {

            }'''.stripIndent()
        new File(projectDir, 'dependencies.lock').text.replaceAll(' ', '') == lockText
        String lockText1 = LockGenerator.duplicateIntoConfigs('''\
                "test.example:foo": {
                    "locked": "2.0.0",
                    "requested": "2.0.0"
                }
            '''.stripIndent())
        new File(projectDir, 'sub1/dependencies.lock').text == lockText1
        String lockText2 = LockGenerator.duplicateIntoConfigs('''\
                "test.example:foo": {
                    "locked": "1.0.1",
                    "requested": "1.+"
                }
            '''.stripIndent())
        new File(projectDir, 'sub2/dependencies.lock').text == lockText2
    }

    def 'generateGlobalLock ignores existing global lock file'() {
        setupCommonMultiproject()
        new File(projectDir, 'global.lock').text = '''\
            {
                "_global_": {
                    "test.example:foo": {
                        "locked": "1.0.1",
                        "transitive": [
                            "test:sub1",
                            "test:sub2"
                        ]
                    },
                    "test:sub1": {
                        "project": true
                    },
                    "test:sub2": {
                        "project": true
                    }
                }
            }'''.stripIndent()
        String globalLockText = '''\
            {
                "_global_": {
                    "test.example:foo": {
                        "locked": "2.0.0",
                        "transitive": [
                            "test:sub1",
                            "test:sub2"
                        ]
                    },
                    "test:sub1": {
                        "project": true
                    },
                    "test:sub2": {
                        "project": true
                    }
                }
            }'''.stripIndent()

        when:
        runTasksSuccessfully('generateGlobalLock')

        then:
        new File(projectDir, 'build/global.lock').text == globalLockText
    }

    def 'throw exception when saving global lock, if individual locks are present'() {
        setupCommonMultiproject()
        runTasksSuccessfully('generateLock', 'saveLock')
        runTasksSuccessfully('generateGlobalLock')

        when:
        def result = runTasksWithFailure('saveGlobalLock')

        then:
        result.failure != null
    }

    def 'throw exception when saving lock, if global locks are present'() {
        setupCommonMultiproject()
        runTasksSuccessfully('generateGlobalLock', 'saveGlobalLock')
        runTasksSuccessfully('generateLock')

        when:
        def result = runTasksWithFailure('saveLock')

        then:
        result.failure != null
    }

    def 'delete global lock'() {
        setupCommonMultiproject()
        runTasksSuccessfully('generateGlobalLock', 'saveGlobalLock')

        when:
        runTasksSuccessfully('deleteGlobalLock')

        then:
        !(new File(projectDir, 'global.lock').exists())
    }

    def 'delete locks'() {
        setupCommonMultiproject()
        runTasksSuccessfully('generateLock', 'saveLock')

        when:
        runTasksSuccessfully('deleteLock')

        then:
        !(new File(projectDir, 'dependencies.lock').exists())
        !(new File(projectDir, 'sub1/dependencies.lock').exists())
        !(new File(projectDir, 'sub2/dependencies.lock').exists())
    }

    def 'only the update dependency and its transitives are updated'() {
        buildFile << """\
            apply plugin: 'java'
            apply plugin: 'nebula.dependency-lock'
            repositories { maven { url '${Fixture.repo}' } }
            dependencyLock {
                includeTransitives = true
            }
            dependencies {
                compile 'test.example:bar:1.+'
                compile 'test.example:qux:latest.release'
            }
        """.stripIndent()

        def lockFile = new File(projectDir, 'dependencies.lock')
        def lockText = LockGenerator.duplicateIntoConfigs(
                '''\
                    "test.example:bar": {
                        "locked": "1.0.0",
                        "requested": "1.+"
                    },
                    "test.example:qux": {
                        "locked": "1.0.0",
                        "requested": "latest.release"
                    },
                    "test.example:foo": {
                        "locked": "1.0.0",
                        "transitive": [
                            "test.example:bar",
                            "test.example:qux"
                        ]
                    }'''.stripIndent()
        )
        lockFile.text = lockText

        def updatedLock = LockGenerator.duplicateIntoConfigs(
                '''\
                    "test.example:bar": {
                        "locked": "1.1.0",
                        "requested": "1.+"
                    },
                    "test.example:foo": {
                        "locked": "1.0.1",
                        "transitive": [
                            "test.example:bar",
                            "test.example:qux"
                        ]
                    },
                    "test.example:qux": {
                        "locked": "1.0.0",
                        "requested": "latest.release"
                    }'''.stripIndent()
        )

        when:
        def results = runTasksSuccessfully('updateLock', '-PdependencyLock.updateDependencies=test.example:bar')

        then:
        println results.standardOutput
        new File(projectDir, 'build/dependencies.lock').text == updatedLock
    }

    def 'generateLock interacts well with resolution rules'() {
        buildFile << """\
            plugins {
                id "nebula.resolution-rules" version "2.0.0"
            }

            apply plugin: 'nebula.dependency-lock'
            apply plugin: 'java'

            repositories {
                mavenCentral()
            }

            dependencies {
                resolutionRules 'com.netflix.nebula:gradle-resolution-rules:latest.release'

                compile 'commons-io:commons-io:2.4'
            }
        """.stripIndent()

        when:
        runTasksSuccessfully('generateLock')

        then:
        noExceptionThrown()
    }

    @Issue('#86')
    def 'locks win over Spring dependency management'() {
        buildFile << """\
            buildscript {
                repositories {
                    mavenCentral()
                }
                dependencies {
                    classpath 'org.springframework.boot:spring-boot-gradle-plugin:1.4.0.RELEASE'
                }
            }

            apply plugin: 'java'
            apply plugin: 'nebula.dependency-lock'
            apply plugin: 'spring-boot'

            repositories {
                mavenCentral()
            }

            dependencies {
                compile     ('com.hazelcast:hazelcast:3.6-RC1')
                compile     ('com.hazelcast:hazelcast-spring:3.6-RC1')
                compile     ('org.mariadb.jdbc:mariadb-java-client:1.1.7')
                compile     ('org.flywaydb:flyway-core')

                testCompile ('org.springframework.boot:spring-boot-starter-test')
            }
        """.stripIndent()

        when:
        runTasksSuccessfully('generateLock', 'saveLock')

        then:
        noExceptionThrown()
        def buildFileText = buildFile.text
        buildFile.delete()
        buildFile << buildFileText.replace('com.hazelcast:hazelcast:3.6-RC1', 'com.hazelcast:hazelcast:3.6-EA2')

        when:
        def result = runTasksSuccessfully('dependencies')

        then:
        result.standardOutput.contains('\\--- com.hazelcast:hazelcast:3.6-RC1\n')
    }

    @Ignore('Android plugin incompatible with Gradle 3.0')
    @Issue('#95')
    def 'locking applied to Android variant configurations'() {
        buildFile << """\
            buildscript {
                repositories {
                    mavenCentral()
                }
                dependencies {
                    classpath 'com.android.tools.build:gradle:2.1.2'
                }
            }

            apply plugin: 'nebula.dependency-lock'
            apply plugin: 'com.android.application'

            repositories {
                jcenter()
            }

            android {
                compileSdkVersion 20
                buildToolsVersion '20.0.0'

                defaultConfig {
                    applicationId "com.netflix.dependencylocktest"
                    minSdkVersion 15
                    targetSdkVersion 23
                    versionCode 1
                    versionName '1.0'
                }
                buildTypes {
                    release {
                        minifyEnabled false
                        proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
                    }
                }
            }

            gradle.addBuildListener(new BuildAdapter() {
                void buildFinished(BuildResult result) {
                   def confsWithDependencies = project.configurations.findAll { !it.dependencies.empty }.collect { '"' + it.name + '"' }
                   println "configurations=" + confsWithDependencies
                }
            })

            dependencies {
                compile 'commons-io:commons-io:2.4'
            }
        """.stripIndent()

        when:
        def generateResult = runTasksSuccessfully('generateLock', 'saveLock')

        then: 'all configurations are in the lock file'
        def configList = generateResult.standardOutput.readLines().find{ it.startsWith("configurations=")}.split("configurations=")[1]
        def configurations = Eval.me(configList)
        def lockFile = new File(projectDir, 'dependencies.lock')
        def json = new JsonSlurper().parseText(lockFile.text)
        configurations.each {
            assert json.keySet().contains(it)
        }

        when: 'all configurations are locked to the specified version'
        def originalLockFile = new File('dependencies.lock.orig')
        lockFile.renameTo(originalLockFile)
        lockFile.withWriter { w ->
            originalLockFile.eachLine { line ->
                w << line.replaceAll( '2\\.4', '2.3' )
            }
        }
        def dependenciesResult = runTasksSuccessfully('dependencies')

        then:
        dependenciesResult.standardOutput.contains('\\--- commons-io:commons-io:2.4 -> 2.3\n')
        !dependenciesResult.standardOutput.contains('\\--- commons-io:commons-io:2.4\n')
    }

    def 'deprecated lock format message is not output for an empty file'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << """{}"""

        buildFile << BUILD_GRADLE

        when:
        def result = runTasksSuccessfully('dependencies')

        then:
        !result.standardOutput.contains("is using a deprecated lock format")
    }

    @Ignore
    def 'diff the generated lock with the existing lock '() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << PRE_DIFF_FOO_LOCK

        buildFile << BUILD_GRADLE

        when:
        runTasksSuccessfully('generateLock', 'diffLock')

        then:
        def diff = new File(projectDir, 'build/reports/dependencylock/lockdiff.txt')
        diff.text == '''\
          compile
            test.example:foo:1.0.0 -> 1.0.1
          runtime
            test.example:foo:1.0.0 -> 1.0.1
          testCompile
            test.example:foo:1.0.0 -> 1.0.1
          testRuntime
            test.example:foo:1.0.0 -> 1.0.1
        '''.stripIndent()
    }

    /*test.example:new:1.0.0 (added)
            test.example:removed:1.0.0 (removed)*/

    private void setupCommonMultiproject() {
        addSubproject('sub1', """\
            dependencies {
                compile 'test.example:foo:2.0.0'
            }
        """.stripIndent())
        addSubproject('sub2', """\
            dependencies {
                compile 'test.example:foo:1.+'
            }
        """.stripIndent())

        buildFile << """\
            allprojects {
                ${applyPlugin(DependencyLockPlugin)}
                group = 'test'
            }
            subprojects {
                apply plugin: 'java'
                repositories { maven { url '${Fixture.repo}' } }
            }
            dependencyLock {
                includeTransitives = true
            }
        """.stripIndent()
    }
}
