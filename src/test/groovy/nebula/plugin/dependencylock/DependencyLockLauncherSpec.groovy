/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nebula.plugin.dependencylock

import nebula.plugin.dependencylock.dependencyfixture.Fixture
import nebula.test.IntegrationSpec
import org.gradle.BuildResult

class DependencyLockLauncherSpec extends IntegrationSpec {
    def setup() {
        fork = true
    }

    static final String SPECIFIC_BUILD_GRADLE = """\
        apply plugin: 'java'
        apply plugin: 'gradle-dependency-lock'
        repositories { maven { url '${Fixture.repo}' } }
        dependencies {
            compile 'test.example:foo:1.0.1'
        }
    """.stripIndent()

    static final String BUILD_GRADLE = """\
        apply plugin: 'java'
        apply plugin: 'gradle-dependency-lock'
        repositories { maven { url '${Fixture.repo}' } }
        dependencies {
            compile 'test.example:foo:1.+'
        }
    """.stripIndent()

    static final String NEW_BUILD_GRADLE = """\
        apply plugin: 'java'
        apply plugin: 'gradle-dependency-lock'
        repositories { maven { url '${Fixture.repo}' } }
        dependencies {
            compile 'test.example:foo:2.+'
        }
    """.stripIndent()

    static final String OLD_FOO_LOCK = '''\
        {
          "test.example:foo": { "locked": "1.0.0", "requested": "1.+" }
        }
    '''.stripIndent()

    static final String FOO_LOCK = '''\
        {
          "test.example:foo": { "locked": "1.0.1", "requested": "1.+" }
        }
    '''.stripIndent()

    static final String NEW_FOO_LOCK = '''\
        {
          "test.example:foo": { "locked": "2.0.1", "requested": "1.+", "viaOverride": "2.0.1" }
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
            apply plugin: 'gradle-dependency-lock'
            repositories { maven { url '${Fixture.repo}' } }
            dependencyLock {
                skippedDependencies = [ 'test.example:foo' ]
            }
            dependencies {
                compile 'test.example:foo:2.+'
                compile 'test.example:baz:1.+'
            }
        """.stripIndent()

        def lockWithSkips = '''\
            {
              "test.example:baz": { "locked": "1.1.0", "requested": "1.+" }
            }
        '''.stripIndent()

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
        def dependenciesLock = new File(projectDir, 'dependencies.lock')
        dependenciesLock << OLD_FOO_LOCK
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
        def result = runTasksWithFailure('build')

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
        testLock << NEW_FOO_LOCK
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
                apply plugin: 'dependency-lock'
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
              "test.example:foo": { "locked": "2.0.0" },
              "test.example:baz": { "locked": "1.0.0" }
            }
        '''.stripIndent()

        when:
        runTasksSuccessfully('-PdependencyLock.overrideFile=override.lock', 'generateLock', 'saveLock')

        then:
        String lockText1 = '''\
            {
              "test.example:foo": { "locked": "2.0.0", "requested": "2.+", "viaOverride": "2.0.0" }
            }
        '''.stripIndent()
        new File(sub1, 'dependencies.lock').text == lockText1
        String lockText2 = '''\
            {
              "test.example:baz": { "locked": "1.0.0", "requested": "1.+", "viaOverride": "1.0.0" }
            }
        '''.stripIndent()
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
              "test.example:bar": { "locked": "1.1.0", "transitive": [ "test.example:transitive", "test:sub1" ] },
              "test.example:baz": { "locked": "1.0.0", "transitive": [ "test.example:foobaz" ] },
              "test.example:foo": { "locked": "2.0.1", "transitive": [ "test.example:bar", "test.example:foobaz", "test:sub1" ] },
              "test.example:foobaz": { "locked": "1.0.0", "transitive": [ "test.example:transitive" ] },
              "test.example:transitive": { "locked": "1.0.0", "transitive": [ "test:sub2" ] },
              "test:sub1": { "project": true },
              "test:sub2": { "project": true }
            }
        '''.stripIndent()
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
              "test.example:bar": { "locked": "1.1.0", "transitive": [ "test.example:transitive", "test:sub1" ] },
              "test.example:baz": { "locked": "1.0.0", "transitive": [ "test.example:foobaz" ] },
              "test.example:foo": { "locked": "2.0.1", "transitive": [ "test.example:bar", "test.example:foobaz", "test:sub1" ] },
              "test.example:foobaz": { "locked": "1.0.0", "transitive": [ "test.example:transitive" ] },
              "test.example:transitive": { "locked": "1.0.0", "transitive": [ "test:sub2" ] },
              "test:sub1": { "project": true },
              "test:sub2": { "project": true }
            }
        '''.stripIndent()
        new File(projectDir, 'build/global.lock').text == globalLockText
    }

    def 'create global lock in multiproject with subproject depending on top-level'() {
        addSubproject('sub1', """\
            dependencies {
                compile project(':')
            }
        """.stripIndent())
        addSubproject('sub2', """\
            dependencies {
                compile project(':sub1')
            }
        """.stripIndent())

        buildFile << """\
            allprojects {
                ${applyPlugin(DependencyLockPlugin)}
                group = 'test'
            }
            allprojects {
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
        String globalLockText = """\
            {
              "test:${moduleName}": { "project": true, "transitive": [ "test:sub1" ] },
              "test:sub1": { "project": true, "transitive": [ "test:sub2" ] },
              "test:sub2": { "project": true }
            }
        """.stripIndent()
        new File(projectDir, 'build/global.lock').text == globalLockText
    }

    def 'save global lock in multiproject'() {
        setupCommonMultiproject()

        when:
        runTasksSuccessfully('generateGlobalLock', 'saveGlobalLock')

        then:
        String globalLockText = '''\
            {
              "test.example:foo": { "locked": "2.0.0", "transitive": [ "test:sub1", "test:sub2" ] },
              "test:sub1": { "project": true },
              "test:sub2": { "project": true }
            }
        '''.stripIndent()
        new File(projectDir, 'global.lock').text == globalLockText
    }

    def 'locks are correct when applying to all projects'() {
        setupCommonMultiproject()

        when:
        runTasksSuccessfully('generateLock', 'saveLock')

        then:
        String lockText = '''\
            {

            }
        '''.stripIndent()
        new File(projectDir, 'dependencies.lock').text == lockText
        String lockText1 = '''\
            {
              "test.example:foo": { "locked": "2.0.0", "requested": "2.0.0" }
            }
        '''.stripIndent()
        new File(projectDir, 'sub1/dependencies.lock').text == lockText1
        String lockText2 = '''\
            {
              "test.example:foo": { "locked": "1.0.1", "requested": "1.+" }
            }
        '''.stripIndent()
        new File(projectDir, 'sub2/dependencies.lock').text == lockText2
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
