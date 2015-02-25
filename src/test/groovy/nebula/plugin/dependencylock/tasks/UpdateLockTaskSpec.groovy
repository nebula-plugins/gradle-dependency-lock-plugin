package nebula.plugin.dependencylock.tasks

import nebula.plugin.dependencylock.dependencyfixture.Fixture
import nebula.test.ProjectSpec
import org.gradle.testfixtures.ProjectBuilder


class UpdateLockTaskSpec extends ProjectSpec {
    final String taskName = 'updateLock'

    def setupSpec() {
        Fixture.createFixtureIfNotCreated()
    }

    def setup() {
        project.apply plugin: 'java'
        project.repositories { maven { url Fixture.repo } }
    }

    UpdateLockTask createTask() {
        def task = project.tasks.create(taskName, UpdateLockTask)
        task.dependenciesLock = new File(project.buildDir, 'dependencies.lock')
        task.configurationNames = [ 'testRuntime' ]
        task
    }

    def 'a dependency should only update relevant dependencies from the current lock file'() {
        project.dependencies {
            compile 'test.example:foo:2.0.0'
        }

        def task = createTask()

        def lockFile = new File(project.projectDir, 'dependencies.lock')
        lockFile.text = '''\
            {
              "test.example:baz": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:foo": { "locked": "1.0.0", "requested": "1.0.0" }
            }
        '''.stripIndent()

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:foo": { "locked": "2.0.0", "requested": "2.0.0" }
            }
        '''.stripIndent()

        task.dependenciesLock.text == lockText
    }

    def 'by default a dependency is updated when another dependency requires a higher version transitively'(useTransitive, lockText) {
        project.dependencies {
            compile 'test.example:foo:1.0.0'
            compile 'test.example:bar:1.1.0'
        }

        def task = createTask()
        task.includeTransitives = useTransitive

        def lockFile = new File(project.projectDir, 'dependencies.lock')
        lockFile.text = '''\
            {
              "test.example:foo": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:bar": { "locked": "1.0.0", "requested": "1.0.0" },
            }
        '''.stripIndent()

        when:
        task.execute()

        then:
        task.dependenciesLock.text == lockText

        where:
        useTransitive || lockText
        false         || '''\
            {
              "test.example:bar": { "locked": "1.1.0", "requested": "1.1.0" },
              "test.example:foo": { "locked": "1.0.1", "requested": "1.0.0" }
            }
        '''.stripIndent()
        true          || '''\
            {
              "test.example:bar": { "locked": "1.1.0", "requested": "1.1.0" },
              "test.example:foo": { "locked": "1.0.1", "requested": "1.0.0", "transitive": [ "test.example:bar" ] }
            }
        '''.stripIndent()
    }

    def 'by default the filter is respected'() {
        project.dependencies {
            compile 'test.example:baz:2.0.0'
            compile 'test.example:foo:2.0.0'
        }

        def task = createTask()
        task.filter = { group, artifact, version ->
            artifact == 'foo'
        }

        def lockFile = new File(project.projectDir, 'dependencies.lock')
        lockFile.text = '''\
            {
              "test.example:baz": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:foo": { "locked": "1.0.0", "requested": "1.0.0" }
            }
        '''.stripIndent()

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:baz": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:foo": { "locked": "2.0.0", "requested": "2.0.0" }
            }
        '''.stripIndent()

        task.dependenciesLock.text == lockText
    }

    def 'when a top-level dependency is updated and transitives are included, its transitive dependencies are updated'() {
        project.dependencies {
            compile 'test.example:bar:1.1.0'
        }

        def task = createTask()
        task.includeTransitives = true

        def lockFile = new File(project.projectDir, 'dependencies.lock')
        lockFile.text = '''\
            {
              "test.example:bar": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:foo": { "locked": "test.example:foo:1.0.0", "transitive": ["test.example:bar"] }
            }
        '''.stripIndent()

        when:
        task.execute()

        then:
        def lockText = lockFile.text = '''\
            {
              "test.example:bar": { "locked": "1.1.0", "requested": "1.1.0" },
              "test.example:foo": { "locked": "1.0.1", "transitive": [ "test.example:bar" ] }
            }
        '''.stripIndent()

        task.dependenciesLock.text == lockText
    }

    def 'specifying a set of dependencies restricts the dependencies updated'(dependencies, lockText) {
        project.dependencies {
            compile 'test.example:baz:2.0.0'
            compile 'test.example:foo:2.0.0'
            compile 'test.example:bar:1.1.0'
        }

        def task = createTask()
        task.dependencies = dependencies

        def lockFile = new File(project.projectDir, 'dependencies.lock')
        lockFile.text = '''\
            {
              "test.example:bar": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:baz": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:foo": { "locked": "1.0.0", "requested": "1.0.0" }
            }
        '''.stripIndent()

        when:
        task.execute()

        then:
        task.dependenciesLock.text == lockText

        where:
        dependencies                                || lockText
        [ 'test.example:foo' ]                      || '''\
            {
              "test.example:bar": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:baz": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:foo": { "locked": "2.0.0", "requested": "2.0.0" }
            }
        '''.stripIndent()
        [ 'test.example:foo', 'test.example:baz' ]  || '''\
            {
              "test.example:bar": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:baz": { "locked": "2.0.0", "requested": "2.0.0" },
              "test.example:foo": { "locked": "2.0.0", "requested": "2.0.0" }
            }
        '''.stripIndent()
    }

    def 'when dependencies are specified, the filter is ignored' () {
        project.dependencies {
            compile 'test.example:baz:2.0.0'
            compile 'test.example:foo:2.0.0'
        }

        def task = createTask()
        task.filter = { group, artifact, version ->
            false
        }

        def lockFile = new File(project.projectDir, 'dependencies.lock')
        lockFile.text = '''\
            {
              "test.example:baz": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:foo": { "locked": "1.0.0", "requested": "1.0.0" }
            }
        '''.stripIndent()

        task.dependencies = [ 'test.example:foo' ]

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:baz": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:foo": { "locked": "2.0.0", "requested": "2.0.0" }
            }
        '''.stripIndent()

        task.dependenciesLock.text == lockText
    }

    def 'dependencies can be specified via a comma-separated list'(input, dependencies) {
        def task = createTask()

        when:
        task.setDependencies(input as String)

        then:
        task.dependencies == dependencies as Set

        where:
        input                             || dependencies
        'com.example:foo'                 || ['com.example:foo']
        'com.example:baz,com.example:foo' || ['com.example:baz', 'com.example:foo']
    }


    def 'by default filtered dependencies do not get updated when they are also included transitively'() {
        project.dependencies {
            compile 'test.example:foo:1.0.0'
            compile 'test.example:foobaz:1.0.0'
        }

        def task = createTask()
        task.dependencies = ["test.example:foobaz"]

        def lockFile = new File(project.projectDir, 'dependencies.lock')
        lockFile.text = '''\
            {
              "test.example:foo": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:foobaz": { "locked": "1.0.0", "requested": "1.0.0" },
            }
        '''.stripIndent()

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:foo": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:foobaz": { "locked": "1.0.0", "requested": "1.0.0" }
            }
        '''.stripIndent()

        task.dependenciesLock.text == lockText
    }

    def 'by default a filtered dependency is not updated when it is also a transitive dependency of an updated top-level dependency'() {
        project.dependencies {
            compile 'test.example:foo:2.0.0'
            compile 'test.example:foobaz:1.0.0'

        }

        def task = createTask()
        task.dependencies = ["test.example:foobaz"]

        def lockFile = new File(project.projectDir, 'dependencies.lock')
        lockFile.text = '''\
            {
              "test.example:foo": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:foobaz": { "locked": "1.0.0", "requested": "1.0.0" },
            }
        '''.stripIndent()

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:foo": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:foobaz": { "locked": "1.0.0", "requested": "1.0.0" }
            }
        '''.stripIndent()

        task.dependenciesLock.text == lockText
    }

    // The spec should probably be to do a minimal update where the hard version is ignored and only the latest
    // greatest transitive version is included, but this is vastly more sophisticated.
    def 'a filtered dependency is updated when the update is performed including transitives'() {
        project.dependencies {
            compile 'test.example:foo:2.0.0'
            compile 'test.example:foobaz:1.0.0'

        }

        def task = createTask()
        task.dependencies = ["test.example:foobaz"]
        task.includeTransitives = true

        def lockFile = new File(project.projectDir, 'dependencies.lock')
        lockFile.text = '''\
            {
              "test.example:baz": { "locked": "1.0.0", "transitive": [ "test.example:foobaz" ] },
              "test.example:foo": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:foobaz": { "locked": "1.0.0", "requested": "1.0.0" }
            }
        '''.stripIndent()

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:baz": { "locked": "1.0.0", "transitive": [ "test.example:foobaz" ] },
              "test.example:foo": { "locked": "2.0.0", "transitive": [ "test.example:foobaz" ] },
              "test.example:foobaz": { "locked": "1.0.0", "requested": "1.0.0" }
            }
        '''.stripIndent()

        task.dependenciesLock.text == lockText
    }

    def 'check circular dependency does not loop infinitely'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'circular:a:1.+'
        }

        def task = createTask()
        task.includeTransitives = true

        def lockFile = new File(project.projectDir, 'dependencies.lock')
        lockFile.text = '''\
            {
              "circular:a": { "locked": "1.0.0", "requested": "1.+", "transitive": [ "circular:b" ] },
              "circular:b": { "locked": "1.0.0", "transitive": [ "circular:a" ] }
            }
        '''.stripIndent()

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "circular:a": { "locked": "1.0.0", "requested": "1.+", "transitive": [ "circular:b" ] },
              "circular:b": { "locked": "1.0.0", "transitive": [ "circular:a" ] }
            }
        '''.stripIndent()

        task.dependenciesLock.text == lockText
    }

    def 'check for deeper circular dependency'() {
        project.apply plugin: 'java'

        project.repositories { maven { url Fixture.repo } }
        project.dependencies {
            compile 'circular:oneleveldeep:1.+'
        }

        def task = createTask()
        task.includeTransitives = true

        def lockFile = new File(project.projectDir, 'dependencies.lock')
        lockFile.text = '''\
            {
              "circular:a": { "locked": "1.0.0", "transitive": [ "circular:b", "circular:oneleveldeep" ] },
              "circular:b": { "locked": "1.0.0", "transitive": [ "circular:a" ] },
              "circular:oneleveldeep": { "locked": "1.0.0", "requested": "1.+" }
            }
        '''.stripIndent()

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "circular:a": { "locked": "1.0.0", "transitive": [ "circular:b", "circular:oneleveldeep" ] },
              "circular:b": { "locked": "1.0.0", "transitive": [ "circular:a" ] },
              "circular:oneleveldeep": { "locked": "1.0.0", "requested": "1.+" }
            }
        '''.stripIndent()

        task.dependenciesLock.text == lockText
    }

    def 'multi-project inter-project dependencies should be updated'() {
        def common = ProjectBuilder.builder().withName('common').withProjectDir(new File(projectDir, 'common')).withParent(project).build()
        project.subprojects.add(common)
        def app = ProjectBuilder.builder().withName('app').withProjectDir(new File(projectDir, 'app')).withParent(project).build()
        project.subprojects.add(app)

        project.subprojects {
            apply plugin: 'java'
            group = 'test.nebula'
            repositories { maven { url Fixture.repo } }
            projectDir.mkdir()
        }

        app.dependencies {
            compile app.project(':common')
            compile 'test.example:foo:2.+'
        }

        common.dependencies {
            compile 'test.example:baz:2.0.0'
        }

        GenerateLockTask task = app.tasks.create(taskName, UpdateLockTask)
        task.dependenciesLock = new File(app.buildDir, 'dependencies.lock')
        task.configurationNames = [ 'testRuntime' ]

        def lockFile = new File(app.projectDir, 'dependencies.lock')
        lockFile.text = '''\
            {
              "test.example:azz": { "locked": "-42.0", "requested": "0.24-" },
              "test.example:foo": { "locked": "1.0.1", "requested": "1.0.+" },
              "test.nebula:common": { "project": true }
            }
        '''.stripIndent()

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:baz": { "locked": "2.0.0", "firstLevelTransitive": [ "test.nebula:common" ] },
              "test.example:foo": { "locked": "2.0.1", "requested": "2.+" },
              "test.nebula:common": { "project": true }
            }
        '''.stripIndent()

        task.dependenciesLock.text == lockText
    }

    def 'updating a filtered multi-project inter-project dependency should updated first-level transitives'() {
        def common = ProjectBuilder.builder().withName('common').withProjectDir(new File(projectDir, 'common')).withParent(project).build()
        project.subprojects.add(common)
        def app = ProjectBuilder.builder().withName('app').withProjectDir(new File(projectDir, 'app')).withParent(project).build()
        project.subprojects.add(app)

        project.subprojects {
            apply plugin: 'java'
            group = 'test.nebula'
            repositories { maven { url Fixture.repo } }
            projectDir.mkdir()
        }

        app.dependencies {
            compile app.project(':common')
            compile 'test.example:foo:2.+'
        }

        common.dependencies {
            compile 'test.example:baz:2.0.0'
        }

        GenerateLockTask task = app.tasks.create(taskName, UpdateLockTask)
        task.dependenciesLock = new File(app.buildDir, 'dependencies.lock')
        task.configurationNames = [ 'testRuntime' ]
        task.dependencies = [ 'test.nebula:common' ]

        def lockFile = new File(app.projectDir, 'dependencies.lock')
        lockFile.text = '''\
            {
              "test.example:azz": { "locked": "-42.0", "requested": "0.24-" },
              "test.example:baz": { "locked": "1.0.0", "firstLevelTransitive": [ "test.nebula:common" ] },
              "test.example:foo": { "locked": "1.0.1", "requested": "2.+" },
              "test.nebula:common": { "project": true }
            }
        '''.stripIndent()

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:baz": { "locked": "2.0.0", "firstLevelTransitive": [ "test.nebula:common" ] },
              "test.example:foo": { "locked": "1.0.1", "requested": "2.+" },
              "test.nebula:common": { "project": true }
            }
        '''.stripIndent()

        task.dependenciesLock.text == lockText
    }

    def 'a filtered dependency should still be updated when it is a first-level transitive of a project'() {
        def app = ProjectBuilder.builder().withName('app').withProjectDir(new File(projectDir, 'app')).withParent(project).build()
        project.subprojects.add(app)

        project.subprojects {
            apply plugin: 'java'
            group = 'test.nebula'
            repositories { maven { url Fixture.repo } }
            projectDir.mkdir()
        }

        app.dependencies {
            compile 'test.example:foo:2.0.0'
        }

        project.dependencies {
            compile project.project(':app')
            compile 'test.example:bar:1.1.0'
            compile 'test.example:foo:1.0.0'
        }

        def task = createTask()
        task.dependencies = [ 'test.nebula:app' ]

        def lockFile = new File(project.projectDir, 'dependencies.lock')
        lockFile.text = '''\
            {
              "test.example:bar": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:foo": { "locked": "1.0.0", "requested": "1.0.0" }
            }
        '''.stripIndent()

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:bar": { "locked": "1.0.0", "requested": "1.0.0" },
              "test.example:foo": { "locked": "2.0.0", "firstLevelTransitive": [ "test.nebula:app" ] },
              "test.nebula:app": { "project": true }
            }
        '''.stripIndent()

        task.dependenciesLock.text == lockText
    }

    def 'multi-project inter-project dependencies should update lock for first levels'() {
        def common = ProjectBuilder.builder().withName('common').withProjectDir(new File(projectDir, 'common')).withParent(project).build()
        project.subprojects.add(common)
        def lib = ProjectBuilder.builder().withName('lib').withProjectDir(new File(projectDir, 'lib')).withParent(project).build()
        project.subprojects.add(lib)
        def app = ProjectBuilder.builder().withName('app').withProjectDir(new File(projectDir, 'app')).withParent(project).build()
        project.subprojects.add(app)

        project.subprojects {
            apply plugin: 'java'
            group = 'test.nebula'
            repositories { maven { url Fixture.repo } }
            projectDir.mkdir()
        }

        common.dependencies {
            compile 'test.example:foo:2.+'
            compile 'test.example:baz:2.+'
        }

        lib.dependencies {
            compile lib.project(':common')
            compile 'test.example:baz:1.+'
        }

        app.dependencies {
            compile app.project(':lib')
        }

        def task = app.tasks.create(taskName, UpdateLockTask)

        task.dependenciesLock = new File(app.buildDir, 'dependencies.lock')
        task.configurationNames= [ 'testRuntime' ]

        def lockFile = new File(app.projectDir, 'dependencies.lock')
        lockFile.text = '''\
            {
              "test.example:azz": { "locked": "-42.0", "firstLevelTransitive": [ "test.nebula:lib" ] },
              "test.example:baz": { "locked": "1.0.0", "firstLevelTransitive": [ "test.nebula:common", "test.nebula:lib" ] },
              "test.example:foo": { "locked": "1.0.0", "firstLevelTransitive": [ "test.nebula:common" ] },
              "test.nebula:common": { "project": true, "firstLevelTransitive": [ "test.nebula:lib" ] },
              "test.nebula:lib": { "project": true }
            }
        '''.stripIndent()

        when:
        task.execute()

        then:
        String lockText = '''\
            {
              "test.example:baz": { "locked": "2.0.0", "firstLevelTransitive": [ "test.nebula:common", "test.nebula:lib" ] },
              "test.example:foo": { "locked": "2.0.1", "firstLevelTransitive": [ "test.nebula:common" ] },
              "test.nebula:common": { "project": true, "firstLevelTransitive": [ "test.nebula:lib" ] },
              "test.nebula:lib": { "project": true }
            }
        '''.stripIndent()

        task.dependenciesLock.text == lockText
    }
}
