/*
 * Copyright 2014-2019 Netflix, Inc.
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
import nebula.plugin.BaseIntegrationTestKitSpec
import nebula.plugin.dependencylock.dependencyfixture.Fixture
import nebula.plugin.dependencylock.util.LockGenerator
import nebula.plugin.dependencylock.utils.GradleVersionUtils
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder
import org.gradle.util.GradleVersion
import org.junit.Rule
import org.junit.contrib.java.lang.system.ProvideSystemProperty
import spock.lang.Ignore
import spock.lang.IgnoreIf
import spock.lang.Issue
import spock.lang.Unroll

import java.nio.file.Files
import java.nio.file.Paths

class DependencyLockLauncherSpec extends BaseIntegrationTestKitSpec {

    @Rule
    public final ProvideSystemProperty provideSystemProperty = new ProvideSystemProperty("ignoreDeprecations", "false")

    def setup() {
        definePluginOutsideOfPluginBlock = true
    }
    
    static final String SPECIFIC_BUILD_GRADLE = """\
        apply plugin: 'java'
        apply plugin: 'com.netflix.nebula.dependency-lock'
        repositories { maven { url = '${Fixture.repo}' } }
        dependencies {
            implementation 'test.example:foo:1.0.1'
            implementation 'test.example:baz:1.0.0'
        }
    """.stripIndent()

    static final String BUILD_GRADLE = """\
        apply plugin: 'java'
        apply plugin: 'com.netflix.nebula.dependency-lock'
        repositories { maven { url = '${Fixture.repo}' } }
        dependencies {
            implementation 'test.example:foo:1.+'
        }
    """.stripIndent()

    static final String NEW_BUILD_GRADLE = """\
        apply plugin: 'java'
        apply plugin: 'com.netflix.nebula.dependency-lock'
        repositories { maven { url = '${Fixture.repo}' } }
        dependencies {
            implementation 'test.example:foo:2.+'
        }
    """.stripIndent()

    static final String OLD_FOO_LOCK = '''\
        {
            "compileClasspath": {
                "test.example:foo": {
                    "locked": "1.0.0"
                }
            }
        }
    '''.stripIndent()

    static final String DEPRECATED_LOCK_FORMAT = '''\
        {
           "test.example:foo": { "locked": "1.0.0" }
        }
    '''.stripIndent()

    static final String PRE_DIFF_FOO_LOCK = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                "test.example:foo": {
                    "locked": "1.0.0"
                }
                '''.stripIndent())

    static final String FOO_LOCK = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                "test.example:foo": {
                    "locked": "1.0.1"
                }
                '''.stripIndent())

    static final String NEW_FOO_LOCK = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                "test.example:foo": {
                    "locked": "2.0.1",
                    "viaOverride": "2.0.1"
                }
                '''.stripIndent())

    static final String REMOVE_UPDATE_LOCK = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                "test.example:baz": {
                    "locked": "1.1.0"
                },
                "test.example:foo": {
                    "locked": "1.0.1"
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
        runTasks('build')

        then:
        noExceptionThrown()
    }

    def 'lock file is applied'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_FOO_LOCK

        buildFile << SPECIFIC_BUILD_GRADLE

        when:
        def result = runTasks('dependencies')

        then:
        result.output.contains 'test.example:foo:1.0.1 -> 1.0.0'
    }

    def 'lock file contributes to dependencyInsight'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_FOO_LOCK

        buildFile << SPECIFIC_BUILD_GRADLE

        when:
        def fooResult = runTasks('dependencyInsight', '--configuration', 'compileClasspath', '--dependency', 'foo')

        then:
        fooResult.output.contains 'locked to 1.0.0'
        fooResult.output.contains('com.netflix.nebula.dependency-lock locked with: dependencies.lock')

        when:
        def bazResult = runTasks('dependencyInsight', '--configuration', 'compileClasspath', '--dependency', 'baz')

        then:
        !bazResult.output.contains('locked')
        !bazResult.output.contains('com.netflix.nebula.dependency-lock locked with: dependencies.lock')
        bazResult.output.contains('baz:1.0.0')
    }

    def 'override lock file contributes to dependencyInsight'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_FOO_LOCK

        def dependenciesLockOverride = new File(projectDir, 'override.lock')
        dependenciesLockOverride << '''
            { "test.example:foo": "2.0.0" }
            '''.stripIndent()

        buildFile << SPECIFIC_BUILD_GRADLE

        when:
        def fooResult = runTasks('dependencyInsight', '--configuration', 'compileClasspath', '--dependency', 'foo', '-PdependencyLock.overrideFile=override.lock')

        then:
        fooResult.output.contains 'locked to 2.0.0'
        fooResult.output.contains('com.netflix.nebula.dependency-lock locked with: dependencies.lock')
        fooResult.output.contains('com.netflix.nebula.dependency-lock using override file: override.lock')

        when:
        def bazResult = runTasks('dependencyInsight', '--configuration', 'compileClasspath', '--dependency', 'baz', '-PdependencyLock.overrideFile=override.lock')

        then:
        !bazResult.output.contains('locked')
        !bazResult.output.contains('com.netflix.nebula.dependency-lock locked with: dependencies.lock')
        !bazResult.output.contains('com.netflix.nebula.dependency-lock using override file: override.lock')
        bazResult.output.contains('baz:1.0.0')
    }

    def 'override lock property contributes to dependencyInsight'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_FOO_LOCK

        buildFile << SPECIFIC_BUILD_GRADLE

        when:
        def fooResult = runTasks('dependencyInsight', '--configuration', 'compileClasspath', '--dependency', 'foo', '-PdependencyLock.override=test.example:foo:2.0.1')

        then:
        fooResult.output.contains 'locked to 2.0.1'
        fooResult.output.contains('com.netflix.nebula.dependency-lock locked with: dependencies.lock')
        fooResult.output.contains('com.netflix.nebula.dependency-lock using override: test.example:foo:2.0.1')

        when:
        def bazResult = runTasks('dependencyInsight', '--configuration', 'compileClasspath', '--dependency', 'baz', '-PdependencyLock.override=test.example:foo:2.0.1')

        then:
        !bazResult.output.contains('locked')
        !bazResult.output.contains('com.netflix.nebula.dependency-lock locked with: dependencies.lock')
        !bazResult.output.contains('com.netflix.nebula.dependency-lock using override: test.example:foo:2.0.1')
        bazResult.output.contains('baz:1.0.0')
    }

    @Issue('#79')
    def 'lock file is not applied while generating lock with abbreviated task name'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_FOO_LOCK

        buildFile << SPECIFIC_BUILD_GRADLE

        when:
        def result = runTasks('gL', 'dependencies')

        then:

        !result.output.contains('test.example:foo:1.0.1 -> 1.0.0')
    }

    @Issue('#79')
    def 'lock file is not applied while generating lock with qualified task name'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_FOO_LOCK

        buildFile << SPECIFIC_BUILD_GRADLE

        when:
        def result = runTasks(':generateLock', 'dependencies')

        then:
        !result.output.contains('test.example:foo:1.0.1 -> 1.0.0')
    }

    @Issue('#79')
    def 'lock file is applied on subprojects not being locked while generating lock with qualified task name'() {
        setupCommonMultiproject()
        new File(projectDir, 'sub1/dependencies.lock').text = PRE_DIFF_FOO_LOCK
        new File(projectDir, 'sub2/dependencies.lock').text = PRE_DIFF_FOO_LOCK

        when:
        def result = runTasks('sub1:generateLock', ':sub2:dependencies')

        then:
        result.output.contains('test.example:foo:1.+ -> 1.0.0')
    }

    @Issue('#79')
    def 'lock file ignored in multiproject on specific project with no leading :'() {
        setupCommonMultiproject()
        new File(projectDir, 'sub1/dependencies.lock').text = PRE_DIFF_FOO_LOCK
        new File(projectDir, 'sub2/dependencies.lock').text = PRE_DIFF_FOO_LOCK

        when:
        def result = runTasks('sub1:generateLock', 'sub1:dependencies')

        then:
        !result.output.contains('test.example:foo:2.0.0 -> 1.0.0')
    }

    @Issue('#79')
    def 'lock file ignored in nested multiproject when generating locks'() {
        def middleDir = addSubproject('middle')
        def sub0Dir = new File(middleDir, 'sub0')
        sub0Dir.mkdirs()
        new File(sub0Dir, 'build.gradle').text = """\
            apply plugin: 'java'
            dependencies {
                implementation 'test.example:foo:2.0.0'
            }
        """.stripIndent()
        new File(sub0Dir, 'dependencies.lock').text = PRE_DIFF_FOO_LOCK
        def sub1Dir = new File(middleDir, 'sub1')
        sub1Dir.mkdirs()
        new File(sub1Dir, 'build.gradle').text = """\
            apply plugin: 'java'
            dependencies {
                implementation 'test.example:foo:1.+'
            }
        """.stripIndent()

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-lock'
                group = 'test'
            }
            subprojects {
                repositories { maven { url = '${Fixture.repo}' } }
            }
        """.stripIndent()

        settingsFile << '''\
            include ':middle:sub0'
            include ':middle:sub1'
        '''.stripIndent()

        when:
        def result = runTasks(':middle:sub0:generateLock', ':middle:sub0:dependencies')

        then:
        !result.output.contains('test.example:foo:2.0.0 -> 1.0.0')
    }

    def 'lock file name can be customized'() {
        buildFile << BUILD_GRADLE
        buildFile << """
        dependencyLock {
            lockFile = "custom.lock"
        }
        """

        when:
        def result = runTasks('generateLock', 'saveLock')

        then:
        def dependenciesLock = new File(projectDir, 'custom.lock')
        dependenciesLock.exists()
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
        def result = runTasks('dependencies')

        then:
        result.output.contains 'test.example:foo:1.+ -> 1.0.1'
    }

    def 'deprecated lock file is applied correctly and emits warning'() {
        def lockOverride = new File(projectDir, 'test.lock')
        lockOverride << DEPRECATED_LOCK_FORMAT

        buildFile << BUILD_GRADLE

        when:
        def s = runTasks('-PdependencyLock.overrideFile=test.lock', 'generateLock', 'saveLock')

        then:
        def outputDepLock = new JsonSlurper().parse(new File(projectDir, 'dependencies.lock'))

        // ensure all test.example:foo deps have been overridden to 1.0.0
        outputDepLock.each { conf, depMap ->
            depMap['test.example:foo'].viaOverride == '1.0.0'
        }

        s.output.contains('is using a deprecated format')
    }

    def 'create lock'() {
        buildFile << BUILD_GRADLE

        when:
        runTasks('generateLock')

        then:
        new File(projectDir, 'build/dependencies.lock').text == FOO_LOCK
    }

    def 'create lock with skipped dependencies'() {
        buildFile << """\
            apply plugin: 'java'
            apply plugin: 'com.netflix.nebula.dependency-lock'
            repositories { maven { url = '${Fixture.repo}' } }
            dependencyLock {
                skippedDependencies = [ 'test.example:foo' ]
            }
            dependencies {
                implementation 'test.example:foo:2.+'
                implementation 'test.example:baz:1.+'
            }
        """.stripIndent()

        def lockWithSkips = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                "test.example:baz": {
                    "locked": "1.1.0"
                }
            '''.stripIndent())

        when:
        runTasks('generateLock')

        then:
        new File(projectDir, 'build/dependencies.lock').text.replaceAll("\\s", "") == lockWithSkips.replaceAll("\\s", "")
    }

    def 'update lock'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_FOO_LOCK
        buildFile << BUILD_GRADLE

        when:
        runTasks('generateLock', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == FOO_LOCK
    }

    def 'diff lock'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << PRE_DIFF_FOO_LOCK
        buildFile << BUILD_GRADLE

        when:
        runTasks('generateLock', 'diffLock')

        then:
        final String MY_DIFF = '''\
            updated:
              test.example:foo: 1.0.0 -> 1.0.1
            '''.stripIndent()
        new File(projectDir, 'build/dependency-lock/lockdiff.txt').text == MY_DIFF
    }

    def 'generateLock fails if dependency locks are ignored'() {
        buildFile << BUILD_GRADLE

        when:
        def result = runTasksAndFail('generateLock', '-PdependencyLock.ignore=true')

        then:
        result.output.contains('Dependency locks cannot be generated. The plugin is disabled for this project (dependencyLock.ignore is set to true)')
    }

    def 'generateGlobalLock fails if dependency locks are ignored'() {
        buildFile << BUILD_GRADLE

        when:
        def result = runTasksAndFail('generateGlobalLock', '-PdependencyLock.ignore=true')

        then:
        result.output.contains('Dependency locks cannot be generated. The plugin is disabled for this project (dependencyLock.ignore is set to true)')
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
        def result = runTasksAndFail('dependencies')

        then:
        result.output.contains('unreadable or invalid json')
    }

    def 'existing lock ignored while updating lock'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << NEW_FOO_LOCK
        buildFile << BUILD_GRADLE

        when:
        runTasks('generateLock', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == FOO_LOCK
    }

    def 'command line override respected while generating lock'() {
        buildFile << BUILD_GRADLE

        when:
        runTasks('-PdependencyLock.override=test.example:foo:2.0.1', 'generateLock', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == NEW_FOO_LOCK
    }

    def 'command line override respected while updating lock'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << FOO_LOCK
        buildFile << BUILD_GRADLE

        when:
        runTasks('-PdependencyLock.updateDependencies=test.example:foo', '-PdependencyLock.override=test.example:foo:2.0.1', 'updateLock', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == NEW_FOO_LOCK
    }

    def 'update lock removed dependency'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << REMOVE_UPDATE_LOCK
        buildFile.text = BUILD_GRADLE
        when:
        runTasks('-PdependencyLock.updateDependencies=test.example:baz', 'updateLock', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == FOO_LOCK
    }

    def 'update lock requires dependencies to update by default'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << REMOVE_UPDATE_LOCK
        buildFile.text = BUILD_GRADLE

        when:
        def result = runTasksAndFail('updateLock', 'saveLock')

        then:
        result.output.contains("Usage of `updateLock` task requires specific modules to update. Please specify dependencies to update")
    }

    def 'update lock uses property when intending to run with no dependencies to update passed in'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << REMOVE_UPDATE_LOCK
        buildFile.text = BUILD_GRADLE
        when:
        runTasks('-PdependencyLock.updateDependenciesFailOnNonSpecifiedDependenciesToUpdate=false', 'updateLock', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == FOO_LOCK
    }

    def 'command line override file respected while generating lock'() {
        def testLock = new File(projectDir, 'test.lock')
        testLock << FOO_LOCK_OVERRIDE
        buildFile << BUILD_GRADLE

        when:
        runTasks('-PdependencyLock.overrideFile=test.lock', 'generateLock', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == NEW_FOO_LOCK
    }

    def 'command line overrideFile fails if file is non existent'() {
        buildFile << BUILD_GRADLE

        expect:
        runTasksAndFail('-PdependencyLock.overrideFile=test.lock', 'generateLock')
    }

    def 'multiple runs each generate a lock'() {
        buildFile << BUILD_GRADLE

        when:
        runTasks('generateLock', 'saveLock')

        then:
        def savedLock = new File(projectDir, 'dependencies.lock')
        savedLock.text == FOO_LOCK

        buildFile << NEW_BUILD_GRADLE

        when:
        runTasks('generateLock')

        then:
        new File(projectDir, 'build/dependencies.lock').text != savedLock.text
    }

    def 'multiple runs of save lock works'() {
        buildFile << BUILD_GRADLE

        when:
        runTasks('generateLock', 'saveLock')

        then:
        def savedLock = new File(projectDir, 'dependencies.lock')
        def firstRun = savedLock.text
        firstRun == FOO_LOCK

        buildFile << NEW_BUILD_GRADLE

        when:
        runTasks('generateLock', 'saveLock')

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
                apply plugin: 'com.netflix.nebula.dependency-lock'
                repositories { maven { url = '${Fixture.repo}' } }
            }
        """.stripIndent()

        settingsFile << '''
            include 'sub1'
            include 'sub2'
        '''.stripIndent()

        new File(sub1, 'build.gradle') << '''\
            dependencies {
                implementation 'test.example:foo:2.+'
            }
        '''.stripIndent()

        new File(sub2, 'build.gradle') << '''\
            dependencies {
                implementation 'test.example:baz:1.+'
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
        runTasks('-PdependencyLock.overrideFile=override.lock', 'generateLock', 'saveLock')

        then:
        String lockText1 = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                "test.example:foo": {
                    "locked": "2.0.0",
                    "viaOverride": "2.0.0"
                }
            '''.stripIndent())
        new File(sub1, 'dependencies.lock').text.replaceAll("\\s", "") == lockText1.replaceAll("\\s", "")
        String lockText2 = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                "test.example:baz": {
                    "locked": "1.0.0",
                    "viaOverride": "1.0.0"
                }
            '''.stripIndent())
        new File(sub2, 'dependencies.lock').text.replaceAll("\\s", "") == lockText2.replaceAll("\\s", "")
    }

    def 'in multiproject allow applying to root project'() {
        addSubproject('sub1')
        addSubproject('sub2')

        buildFile << """\
            allprojects {                 apply plugin: 'com.netflix.nebula.dependency-lock' }
            subprojects { apply plugin: 'java' }
        """.stripIndent()

        when:
        runTasks('generateLock')

        then:
        noExceptionThrown()
    }

    def 'create global lock in singleproject'() {
        buildFile << BUILD_GRADLE

        when:
        runTasks('generateGlobalLock')

        then:
        new File(projectDir, 'build/global.lock').text == FOO_LOCK
    }


    def 'warn the user when configurations for creating global lock in multiproject are no longer resolvable, thus no longer lockable'() {
        disableConfigurationCache()
        addSubproject('sub1', """\
            configurations {
                special
            }
            dependencies {
                implementation 'test.example:bar:1.1.0'
                special 'test.example:foo:2.0.0'
            }
            dependencyLock.configurationNames = ['special', 'compileClasspath']
        """.stripIndent())

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-lock'
                group = 'test'
            }
            subprojects {
                apply plugin: 'java'
                repositories { maven { url = '${Fixture.repo}' } }
            }
            dependencyLock {
                includeTransitives = true
            }
        """.stripIndent()

        when:
        def result =runTasks('generateGlobalLock', '--warning-mode', 'all')

        then:
        assert result.output.contains('Global lock warning: project \'sub1\' requested locking a configuration which cannot be consumed: \'compileClasspath\'')
        assert result.output.contains('Requested configurations for global locks must be resolvable, consumable, and without resolution alternatives.')
        assert result.output.contains('You can remove the configuration \'dependencyLock.configurationNames\' to stop this customization.')
        assert result.output.contains('If you wish to lock only specific configurations, please update \'dependencyLock.configurationNames\' with other configurations.')

        def globalLockFile = new File(projectDir, 'build/global.lock')
        assert globalLockFile.exists()
        assert globalLockFile.text.contains('test.example:foo')
        assert globalLockFile.text.contains('test:sub1')
    }

    def 'save global lock in multiproject'() {
        disableConfigurationCache()
        setupCommonMultiproject()

        when:
        runTasks('generateGlobalLock', 'saveGlobalLock')

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
        runTasks('generateLock', 'saveLock')

        then:
        String lockText = '''\
            {

            }'''.stripIndent()
        new File(projectDir, 'dependencies.lock').text.replaceAll(' ', '') == lockText
        String lockText1 = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                "test.example:foo": {
                    "locked": "2.0.0"
                }
            '''.stripIndent())
        new File(projectDir, 'sub1/dependencies.lock').text.replaceAll("\\s", "") == lockText1.replaceAll("\\s", "")
        String lockText2 = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                "test.example:foo": {
                    "locked": "1.0.1"
                }
            '''.stripIndent())
        new File(projectDir, 'sub2/dependencies.lock').text.replaceAll("\\s", "") == lockText2.replaceAll("\\s", "")
    }

    def 'diffLock in multiproject'() {
        setupCommonMultiproject()
        new File(projectDir, 'dependencies.lock').text = '{}'
        new File(projectDir, 'sub1/dependencies.lock').text = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                "test.example:foo": {
                    "locked": "2.0.0"
                }
            '''.stripIndent())

        new File(projectDir, 'sub2/dependencies.lock').text = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
                "test.example:foo": {
                    "locked": "1.0.0"
                }
            '''.stripIndent())

        when:
        runTasks('generateLock', 'diffLock')

        then:
        String lockText1 = ''
        new File(projectDir, 'sub1/build/dependency-lock/lockdiff.txt').text == lockText1

        String lockText2 = '''\
            updated:
              test.example:foo: 1.0.0 -> 1.0.1
            '''.stripIndent()
        new File(projectDir, 'sub2/build/dependency-lock/lockdiff.txt').text == lockText2
    }

    def 'diffLock in multiproject with no locks'() {
        disableConfigurationCache()
        setupCommonMultiproject()

        when:
        runTasks('diffLock')

        then:
        String lockText1 = '''\
            --no updated locks to diff--
            '''.stripIndent()
        new File(projectDir, 'sub1/build/dependency-lock/lockdiff.txt').text == lockText1

        String lockText2 = '''\
            --no updated locks to diff--
            '''.stripIndent()
        new File(projectDir, 'sub2/build/dependency-lock/lockdiff.txt').text == lockText2
    }

    def 'generateGlobalLock ignores existing global lock file'() {
        disableConfigurationCache()
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
        runTasks('generateGlobalLock')

        then:
        new File(projectDir, 'build/global.lock').text == globalLockText
    }

    def 'throw exception when saving global lock, if individual locks are present'() {
        disableConfigurationCache()
        setupCommonMultiproject()
        runTasks('generateLock', 'saveLock')
        runTasks('generateGlobalLock')

        expect:
        runTasksAndFail('saveGlobalLock')
    }

    def 'throw exception when saving lock, if global locks are present'() {
        disableConfigurationCache()
        setupCommonMultiproject()
        runTasks('generateGlobalLock', 'saveGlobalLock')
        runTasks('generateLock')

        expect:
        runTasksAndFail('saveLock')
    }

    def 'delete global lock'() {
        disableConfigurationCache()
        setupCommonMultiproject()
        runTasks('generateGlobalLock', 'saveGlobalLock')

        when:
        runTasks('deleteGlobalLock')

        then:
        !(new File(projectDir, 'global.lock').exists())
    }

    def 'delete locks'() {
        setupCommonMultiproject()
        runTasks('generateLock', 'saveLock')

        when:
        runTasks('deleteLock')

        then:
        !(new File(projectDir, 'dependencies.lock').exists())
        !(new File(projectDir, 'sub1/dependencies.lock').exists())
        !(new File(projectDir, 'sub2/dependencies.lock').exists())
    }

    def 'only the update dependency and its transitives are updated'() {
        buildFile << """\
            apply plugin: 'java'
            apply plugin: 'com.netflix.nebula.dependency-lock'
            repositories { maven { url = '${Fixture.repo}' } }
            dependencyLock {
                includeTransitives = true
            }
            dependencies {
                implementation 'test.example:bar:1.+'
                implementation 'test.example:qux:latest.release'
            }
        """.stripIndent()

        def lockFile = new File(projectDir, 'dependencies.lock')
        def lockText = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly(
                '''\
                    "test.example:bar": {
                        "locked": "1.0.0"
                    },
                    "test.example:qux": {
                        "locked": "1.0.0"
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

        def updatedLock = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly(
                '''\
                    "test.example:bar": {
                        "locked": "1.1.0"
                    },
                    "test.example:foo": {
                        "locked": "1.0.1",
                        "transitive": [
                            "test.example:bar",
                            "test.example:qux"
                        ]
                    },
                    "test.example:qux": {
                        "locked": "1.0.0"
                    }'''.stripIndent()
        )

        when:
        runTasks('updateLock', '-PdependencyLock.updateDependencies=test.example:bar')

        then:
        new File(projectDir, 'build/dependencies.lock').text == updatedLock
    }

    def 'only the update dependency, its transitives and dependencies without requested versions are updated'() {
        buildFile << """\
            apply plugin: 'java'
            apply plugin: 'com.netflix.nebula.dependency-lock'
            repositories { maven { url = '${Fixture.repo}' } }
            dependencyLock {
                includeTransitives = true
            }
            configurations.all {
                resolutionStrategy.eachDependency { details ->
                    if (details.requested.name == 'bar') {
                        details.useVersion '1.1.0'
                    }
                }
            }
            dependencies {
                implementation 'test.example:bar'
                implementation 'test.example:qux:latest.release'
            }
        """.stripIndent()

        def lockFile = new File(projectDir, 'dependencies.lock')
        def lockText = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly(
                '''\
                    "test.example:bar": {
                        "locked": "1.0.0"
                    },
                    "test.example:qux": {
                        "locked": "1.0.0"
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

        def updatedLock = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly(
                '''\
                    "test.example:bar": {
                        "locked": "1.1.0"
                    },
                    "test.example:foo": {
                        "locked": "2.0.1",
                        "transitive": [
                            "test.example:bar",
                            "test.example:qux"
                        ]
                    },
                    "test.example:qux": {
                        "locked": "2.0.0"
                    }'''.stripIndent()
        )

        when:
        runTasks('updateLock', '-PdependencyLock.updateDependencies=test.example:qux')

        then:
        new File(projectDir, 'build/dependencies.lock').text == updatedLock
    }

    def 'project first level transitives are kept locked during update, using "implementation" configuration'() {
        setupCommonMultiproject()
        addSubproject('sub3', """\
            dependencies {
                implementation project(':sub4')
            }
        """.stripIndent())

        addSubproject('sub4', """\
            dependencies {
                implementation project(':sub2')
            }
        """.stripIndent())
        def lockFile = new File(new File(projectDir, 'sub3'), 'dependencies.lock')
        def lockText = LockGenerator.duplicateIntoConfigs('''\
        "test.example:foo": {
            "locked": "1.0.0",
            "transitive": [
                "test:sub2"
            ],
            "viaOverride": "1.0.0"
        },
        "test:sub2": {
            "project": true,
            "transitive": [
                "test:sub4"
            ]
        },
        "test:sub4": {
            "project": true
        }
        '''.stripIndent(), ['runtimeClasspath', 'testRuntimeClasspath'], '''\
        "test:sub4": {
            "project": true
        }
        '''.stripIndent(), ['compileClasspath', 'testCompileClasspath'])

        when:
        runTasks('generateLock', 'saveLock', '-PdependencyLock.override=test.example:foo:1.0.0')

        then:
        lockFile.text == lockText

        when:
        runTasks('updateLock', 'saveLock', '-PdependencyLock.updateDependencies=test.example:qux')

        then:
        def updateLockText = LockGenerator.duplicateIntoConfigs('''\
        "test.example:foo": {
            "locked": "1.0.0",
            "transitive": [
                "test:sub2"
            ]
        },
        "test:sub2": {
            "project": true,
            "transitive": [
                "test:sub4"
            ]
        },
        "test:sub4": {
            "project": true
        }
        '''.stripIndent(), ['runtimeClasspath', 'testRuntimeClasspath'], '''\
        "test:sub4": {
            "project": true
        }
        '''.stripIndent(), ['compileClasspath', 'testCompileClasspath'])
        lockFile.text == updateLockText
    }

    def 'project first level transitives are kept locked during update, using "api" configuration'() {
        setupCommonMultiprojectWithJavaLibraryPlugin()
        addSubproject('sub3', """\
            dependencies {
                api project(':sub4')
            }
        """.stripIndent())

        addSubproject('sub4', """\
            dependencies {
                api project(':sub2')
            }
        """.stripIndent())
        def lockFile = new File(new File(projectDir, 'sub3'), 'dependencies.lock')
        def lockText = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
        "test.example:foo": {
            "locked": "1.0.0",
            "transitive": [
                "test:sub2"
            ],
            "viaOverride": "1.0.0"
        },
        "test:sub2": {
            "project": true,
            "transitive": [
                "test:sub4"
            ]
        },
        "test:sub4": {
            "project": true
        }
        '''.stripIndent())

        when:
        runTasks('generateLock', 'saveLock', '-PdependencyLock.override=test.example:foo:1.0.0')

        then:
        lockFile.text == lockText

        when:
        runTasks('updateLock', 'saveLock', '-PdependencyLock.updateDependencies=test.example:qux')

        then:
        def updateLockText = LockGenerator.duplicateIntoConfigsWhenUsingImplementationConfigurationOnly('''\
        "test.example:foo": {
            "locked": "1.0.0",
            "transitive": [
                "test:sub2"
            ]
        },
        "test:sub2": {
            "project": true,
            "transitive": [
                "test:sub4"
            ]
        },
        "test:sub4": {
            "project": true
        }
        '''.stripIndent())
        lockFile.text == updateLockText
    }

    def 'update fails if the update matches a project coordinate'() {
        setupCommonMultiproject()

        when:
        def result = runTasksAndFail('updateLock', '-PdependencyLock.updateDependencies=test:sub1')

        then:
        result.output.contains('Dependency locks cannot be updated. An update was requested for a project dependency (test:sub1)')
    }

    def 'update fails if updateDependencies has malformed coordiantes'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << FOO_LOCK
        buildFile << BUILD_GRADLE

        when:
        def result = runTasksAndFail('-PdependencyLock.updateDependencies=test.example:foo:2.0.1', '-PdependencyLock.override=test.example:foo:2.0.1', 'updateLock', 'saveLock')


        then:
        result.output.contains("updateDependencies list is invalid")
        result.output.contains("test.example:foo:2.0.1 contains more elements than groupId:module")
    }

    def 'command line override respected while updating lock with malformed coordinates with extension'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << FOO_LOCK
        buildFile << """
        apply plugin: 'java'
        apply plugin: 'com.netflix.nebula.dependency-lock'
        repositories { maven { url = '${Fixture.repo}' } }
        dependencies {
            implementation 'test.example:foo:1.+'
        }
        dependencyLock {
            updateDependenciesFailOnInvalidCoordinates = false
        }
        """.stripIndent()

        when:
        runTasks('-PdependencyLock.updateDependencies=test.example:foo:2.0.1', '-PdependencyLock.override=test.example:foo:2.0.1', 'updateLock', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == NEW_FOO_LOCK
    }

    def 'command line override respected while updating lock with malformed coordinates allowed by property'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << FOO_LOCK
        buildFile << BUILD_GRADLE

        when:
        runTasks('-PdependencyLock.updateDependencies=test.example:foo:2.0.1', '-PdependencyLock.override=test.example:foo:2.0.1', '-PdependencyLock.updateDependenciesFailOnInvalidCoordinates=false', 'updateLock', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == NEW_FOO_LOCK
    }

    def 'updateDependenciesFailOnInvalidCoordinates command line override respected while updating lock with malformed coordinates allowed by extension'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << FOO_LOCK
        buildFile << """
        apply plugin: 'java'
        apply plugin: 'com.netflix.nebula.dependency-lock'
        repositories { maven { url = '${Fixture.repo}' } }
        dependencies {
            implementation 'test.example:foo:1.+'
        }
        dependencyLock {
            updateDependenciesFailOnInvalidCoordinates = false
        }
        """.stripIndent()

        when:
        runTasks('-PdependencyLock.updateDependencies=test.example:foo:2.0.1', '-PdependencyLock.override=test.example:foo:2.0.1', '-PdependencyLock.updateDependenciesFailOnInvalidCoordinates=false', 'updateLock', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == NEW_FOO_LOCK
    }

    def 'updateDependenciesFailOnInvalidCoordinates prefers property over extension'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << FOO_LOCK
        buildFile << """
        apply plugin: 'java'
        apply plugin: 'com.netflix.nebula.dependency-lock'
        repositories { maven { url = '${Fixture.repo}' } }
        dependencies {
            implementation 'test.example:foo:1.+'
        }
        dependencyLock {
            updateDependenciesFailOnInvalidCoordinates = true
        }
        """.stripIndent()

        when:
        runTasks('-PdependencyLock.updateDependencies=test.example:foo:2.0.1', '-PdependencyLock.override=test.example:foo:2.0.1', '-PdependencyLock.updateDependenciesFailOnInvalidCoordinates=false', 'updateLock', 'saveLock')

        then:
        new File(projectDir, 'dependencies.lock').text == NEW_FOO_LOCK
    }

    def 'generateLock interacts well with resolution rules'() {
        buildFile << """\
            buildscript {
                repositories {
                    mavenCentral()
                }
                dependencies {
                    classpath "com.netflix.nebula:gradle-resolution-rules-plugin:latest.release"
                }
            }

            apply plugin: 'com.netflix.nebula.dependency-lock'
            apply plugin: 'com.netflix.nebula.resolution-rules'
            apply plugin: 'java'

            repositories {
                mavenCentral()
            }

            dependencies {
                resolutionRules 'com.netflix.nebula:gradle-resolution-rules:latest.release'

                implementation 'commons-io:commons-io:2.4'
            }
        """.stripIndent()

        when:
        runTasks('generateLock')

        then:
        noExceptionThrown()
    }

    //Spring boot plugin 1.x is using removed runtime configuration. Unless backported for Gradle 7.0 it cannot be used
    @IgnoreIf({ GradleVersion.current().baseVersion >= GradleVersion.version("7.0")})
    @Issue('#86')
    def 'locks win over Spring dependency management'() {
        provideSystemProperty.setProperty("ignoreDeprecations", "true")
        // Deprecation: The classifier property has been deprecated. This is scheduled to be removed in Gradle 7.0. Please use the archiveClassifier property instead.

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
            apply plugin: 'com.netflix.nebula.dependency-lock'
            apply plugin: 'spring-boot'

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation     ('com.hazelcast:hazelcast:3.6-RC1')
                implementation     ('com.hazelcast:hazelcast-spring:3.6-RC1')
                implementation     ('org.mariadb.jdbc:mariadb-java-client:1.1.7')
                implementation     ('org.flywaydb:flyway-core')

                testImplementation ('org.springframework.boot:spring-boot-starter-test')
            }
        """.stripIndent()

        when:
        runTasks('generateLock', 'saveLock')

        then:
        noExceptionThrown()
        def buildFileText = buildFile.text
        buildFile.delete()
        buildFile << buildFileText.replace('com.hazelcast:hazelcast:3.6-RC1', 'com.hazelcast:hazelcast:3.6-EA2')

        when:
        def result = runTasks('dependencies')

        then:
        result.output.contains('\\--- com.hazelcast:hazelcast:3.6-RC1\n')
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

            apply plugin: 'com.netflix.nebula.dependency-lock'
            apply plugin: 'com.android.application'

            repositories {
                mavenCentral()
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
                implementation 'commons-io:commons-io:2.4'
            }
        """.stripIndent()

        when:
        def generateResult = runTasks('generateLock', 'saveLock')

        then: 'all configurations are in the lock file'
        def configList = generateResult.output.readLines().find {
            it.startsWith("configurations=")
        }.split("configurations=")[1]
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
                w << line.replaceAll('2\\.4', '2.3')
            }
        }
        def dependenciesResult = runTasks('dependencies')

        then:
        dependenciesResult.output.contains('\\--- commons-io:commons-io:2.4 -> 2.3\n')
        !dependenciesResult.output.contains('\\--- commons-io:commons-io:2.4\n')
    }

    def 'deprecated lock format message is not output for an empty file'() {
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << """{}"""

        buildFile << BUILD_GRADLE

        when:
        def result = runTasks('dependencies')

        then:
        !result.output.contains("is using a deprecated lock format")
    }

    @Unroll
    def 'global lock with dependencyReport and default dependencies works with #plugin'() {
        disableConfigurationCache()
        addSubproject('one', """\
            plugins {
                id 'java'
                $plugin
                id 'project-report'
            }
            """.stripIndent())

        addSubproject('two', """\
            plugins {
                id 'java'
                $plugin
                id 'project-report'
            }
            """.stripIndent())

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-lock'
            }
            
            subprojects {
                repositories { mavenCentral() }
            }
            """.stripIndent()

        when:
        runTasks('generateGlobalLock', 'saveGlobalLock', 'dependencyReport')

        then:
        noExceptionThrown()

        where:
        plugin << ['id \'checkstyle\'', 'id \'jacoco\''] // removed 'id \'net.saliman.cobertura\' version \'2.5.0\'', because it isn't gradle 5.1 compatible yet
    }

    def 'global lock with skippedConfigurationNamesPrefixes'() {
        disableConfigurationCache()
        addSubproject('one')
        addSubproject('two')

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-lock'
                dependencyLock {
                    skippedConfigurationNamesPrefixes = ['spotless']
                }
                
            }
            
            subprojects {
                apply plugin: 'java'
                repositories { mavenCentral() }
                configurations {
                    spotless123
                }
                dependencies {
                    implementation 'com.google.guava:guava:19.0'
                    spotless123 'com.google.guava:guava:31.1-jre'
                }
            }
            """.stripIndent()

        when:
        def result = runTasks('generateGlobalLock', 'saveGlobalLock')

        then: "Guava is locked to 19.0"
        new File(projectDir, 'global.lock').text == """{
    "_global_": {
        "com.google.guava:guava": {
            "firstLevelTransitive": [
                "global-lock-with-skippedConfigurationNamesPrefixes:one",
                "global-lock-with-skippedConfigurationNamesPrefixes:two"
            ],
            "locked": "19.0"
        },
        "global-lock-with-skippedConfigurationNamesPrefixes:one": {
            "project": true
        },
        "global-lock-with-skippedConfigurationNamesPrefixes:two": {
            "project": true
        }
    }
}"""

        when:
        def resultDependencies = runTasks(':one:dependencies')

        then:
        resultDependencies.output.contains("com.google.guava:guava:31.1-jre")
        !resultDependencies.output.contains("com.google.guava:guava:31.1-jre -> 19.0")
    }

    def 'handle generating a lock with circular dependencies depending on a jar that depends on us'() {
        def builder = new DependencyGraphBuilder()
        builder.addModule(new ModuleBuilder('test.nebula:foo:1.0.0').addDependency('example.nebula:circulartest:1.0.0').build())
        def generator = new GradleDependencyGenerator(builder.build(), "$projectDir/repo")
        generator.generateTestMavenRepo()
        buildFile << """\
            plugins {
                id 'java'
                id 'nebula.maven-publish' version '5.1.5'
            }
            group = 'example.nebula'
            version = '1.1.0'
            apply plugin: 'com.netflix.nebula.dependency-lock'
            
            repositories {
                ${generator.mavenRepositoryBlock}
            }

            dependencyLock {
                includeTransitives = true
            }
            dependencies {
                implementation 'test.nebula:foo:1.+'
            }
            """.stripIndent()
        settingsFile.text = '''\
            rootProject.name = 'circulartest'
            '''.stripIndent()

        when:
        runTasks('generateLock')

        then:
        noExceptionThrown()
        File lock = new File(projectDir, 'build/dependencies.lock')
        lock.text.contains '''\
            |        "example.nebula:circulartest": {
            |            "project": true,
            |            "transitive": [
            |                "test.nebula:foo"
            |            ]
            |        },
            |'''.stripMargin('|')
    }

    def 'handle reading a lock with circular dependencies depending on a jar that depends on us'() {
        def builder = new DependencyGraphBuilder()
        builder.addModule(new ModuleBuilder('test.nebula:foo:1.0.0').addDependency('example.nebula:circulartest:1.0.0').build())
        def generator = new GradleDependencyGenerator(builder.build(), "$projectDir/repo")
        generator.generateTestMavenRepo()
        buildFile << """\
            plugins {
                id 'java'
                id 'project-report'
                id 'nebula.maven-publish' version '5.1.5'
            }
            group = 'example.nebula'
            version = '1.1.0'
            apply plugin: 'com.netflix.nebula.dependency-lock'
            
            repositories {
                ${generator.mavenRepositoryBlock}
            }

            dependencyLock {
                includeTransitives = true
            }
            dependencies {
                implementation 'test.nebula:foo:1.+'
            }
            """.stripIndent()
        settingsFile.text = '''\
            rootProject.name = 'circulartest'
            '''.stripIndent()

        def deplock = new File(projectDir, 'dependencies.lock')
        deplock.text = LockGenerator.duplicateIntoConfigs('''\
            "example.nebula:circulartest": {
                "project": true,
                "transitive": [
                    "test.nebula:foo"
                ]
            },
            "test.nebula:foo": {
                "locked": "1.0.0"
            }
            '''.stripIndent())

        when:
        def result = runTasks('dependencyReport', 'build')

        then:
        noExceptionThrown()
    }

    private void setupCommonMultiproject() {
        addSubproject('sub1', """\
            dependencies {
                implementation 'test.example:foo:2.0.0'
            }
        """.stripIndent())
        addSubproject('sub2', """\
            dependencies {
                implementation 'test.example:foo:1.+'
            }
        """.stripIndent())

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-lock'
                group = 'test'

                dependencyLock {
                    includeTransitives = true
                }
            }
            subprojects {
                apply plugin: 'java'
                repositories { maven { url = '${Fixture.repo}' } }
            }
        """.stripIndent()
    }

    private void setupCommonMultiprojectWithPlugins() {
        addSubproject('sub1', """\
            dependencies {
                implementation 'test.example:foo:2.0.0'
            }
        """.stripIndent())
        addSubproject('sub2', """\
            dependencies {
                implementation 'test.example:foo:1.+'
            }
        """.stripIndent())

        buildFile << """\
            buildscript {
              repositories {
                maven {
                  url = "https://plugins.gradle.org/m2/"
                }
              }
              dependencies {
                classpath "com.github.spotbugs:spotbugs-gradle-plugin:3.0.0"
              }
            }

            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-lock'
                group = 'test'

                dependencyLock {
                    includeTransitives = true
                }
            }
            subprojects {
                apply plugin: 'scala'
                apply plugin: "com.github.spotbugs"
                repositories { 
                    maven { url = '${Fixture.repo}' } 
                    mavenCentral()
                }
            }
        """.stripIndent()
    }

    private void setupCommonMultiprojectWithJavaLibraryPlugin() {
        addSubproject('sub1', """\
            dependencies {
                api 'test.example:foo:2.0.0'
            }
        """.stripIndent())
        addSubproject('sub2', """\
            dependencies {
                api 'test.example:foo:1.+'
            }
        """.stripIndent())

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-lock'
                group = 'test'

                dependencyLock {
                    includeTransitives = true
                }
            }
            subprojects {
                apply plugin: 'java-library'
                repositories { maven { url = '${Fixture.repo}' } }
            }
        """.stripIndent()
    }

    def 'handle case where asked to update non existent dependency'() {
        def builder = new DependencyGraphBuilder()
        builder.addModule('test.nebula:foo:1.0.0').addModule('test.nebula:foo:1.1.0')
        def generator = new GradleDependencyGenerator(builder.build(), "$projectDir/repo")
        generator.generateTestMavenRepo()
        buildFile << """\
            plugins {
                id 'java'
            }
            apply plugin: 'com.netflix.nebula.dependency-lock'
            
            repositories {
                ${generator.mavenRepositoryBlock}
            }

            dependencies {
                implementation 'test.nebula:foo:1.+'
            }
            """.stripIndent()

        def deplock = new File(projectDir, 'dependencies.lock')
        deplock.text = LockGenerator.duplicateIntoConfigs('''\
            "test.nebula:foo": {
                "locked": "1.0.0"
            }
            '''.stripIndent())

        when:
        runTasks('updateLock', 'saveLock', '-PdependencyLock.updateDependencies=test.nebula:bar')

        then:
        def newLock = new File(projectDir, 'build/dependencies.lock').text
        !newLock.contains('"locked": "1.1.0"')
    }

    def 'handle case where asked to update when lock has a bogus dependency'() {
        def builder = new DependencyGraphBuilder()
        builder.addModule('test.nebula:foo:1.0.0').addModule('test.nebula:foo:1.1.0')
        def generator = new GradleDependencyGenerator(builder.build(), "$projectDir/repo")
        generator.generateTestMavenRepo()
        buildFile << """\
            plugins {
                id 'java'
            }
            apply plugin: 'com.netflix.nebula.dependency-lock'
            
            repositories {
                ${generator.mavenRepositoryBlock}
            }

            dependencies {
                implementation 'test.nebula:foo:1.+'
            }
            """.stripIndent()

        def deplock = new File(projectDir, 'dependencies.lock')
        deplock.text = LockGenerator.duplicateIntoConfigs('''\
            "test.nebula:bar": {
                "locked": "1.0.0"
            },
            "test.nebula:foo": {
                "locked": "1.0.0"
            }
            '''.stripIndent())

        when:
        runTasks('updateLock', 'saveLock', '-PdependencyLock.updateDependencies=test.nebula:bar')

        then:
        def newLock = new File(projectDir, 'build/dependencies.lock').text
        println newLock
        !newLock.contains('"test.nebula:bar"')
    }

    def 'save global lock in multiproject - do not lock excluded configurations'() {
        disableConfigurationCache()
        setupCommonMultiprojectWithPlugins()

        when:
        def zincDependencyInsight = runTasks(':sub1:dI', '--dependency', 'scala', '--configuration', 'zinc')

        then:
        zincDependencyInsight.output.contains('org.scala-sbt:zinc_2.13')

        when:
        runTasks('generateGlobalLock', 'saveGlobalLock')

        then:
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

    @Unroll
    def '#platformType platforms can be be locked'() {
        given:
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << '''\
        {
            "compileClasspath": {
                "sample:recommender": {
                    "locked": "1.0"
                }
            }
        }
        '''.stripIndent()

        def repo = new File(projectDir,'repo')
        def sample = new File(repo, 'sample/recommender/1.0')
        sample.mkdirs()
        def sampleFile = new File(sample, 'recommender-1.0.pom')
        sampleFile << '''
            <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId>
              <artifactId>recommender</artifactId>
              <version>1.0</version>

              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>commons-logging</groupId>
                    <artifactId>commons-logging</artifactId>
                    <version>1.1.1</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
        '''

        buildFile << """\
            apply plugin: 'java'
            apply plugin: 'com.netflix.nebula.dependency-lock'
            repositories { maven { url = '${repo.absolutePath}' } }
            dependencies {
                implementation($platformType('sample:recommender:1.1'))
            }
        """.stripIndent()

        when:
        def result = runTasks('dI', '--dependency', 'recommender')

        then:
        result.output.contains outputMessage

        where:
        platformType       | outputMessage
        'platform'         | 'sample:recommender:1.1 -> 1.0'
        'enforcedPlatform' | 'sample:recommender:{strictly 1.1} -> 1.0'
    }

    def 'lock file is applied and local libs dont fail'() {
        Files.createDirectory(Paths.get(projectDir.absolutePath, 'libs'))
        Files.createFile(Paths.get(projectDir.absolutePath, 'libs/testJar.jar'))

        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_FOO_LOCK

        buildFile << """\
            apply plugin: 'java'
            apply plugin: 'com.netflix.nebula.dependency-lock'
            repositories { maven { url = '${Fixture.repo}' } }
            repositories { flatDir { dirs 'libs' } }
            dependencies {
                implementation 'test.example:foo:1.0.1'
                implementation 'test.example:baz:1.0.0'
                implementation name: 'testJar'
            }
        """.stripIndent()

        when:
        def result = runTasks('dependencies')

        then:
        result.output.contains 'test.example:foo:1.0.1 -> 1.0.0'
    }

    def 'does not fail with configuration caching enabled'() {
        when:
        runTasks('help', '--configuration-cache')

        then:
        noExceptionThrown()
    }
}
