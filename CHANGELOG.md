1.12.4 / 2015-02-27
===================

* Modify plugin so the lock is used by IDEs which skip taskGraph.whenReady
* Plugin can now be applied to a rootProject in a multiproject without configuration
* Added deleteLock and deleteGlobalLock takss
* Added globalLock so multiprojects can have a unified version of all transitive dependencies (useful in IDE)

1.12.3 / 2014-12-11
===================

* Set cacheDynamicVersion to 0 when generating lock
* Documentation improvements
* Deprecated plugin name gradle-dependency-lock in favor of dependency-lock
* Changed dependencyLock.ignore respect the value set on it instead of simple existence

1.12.2 / 2014-10-13
===================

* Add configuration to allow skipping certain dependencies from the lock

1.12.1 / 2014-06-11
===================

* nebula-plugin-plugin to 1.12.1

1.9.11 / 2014-06-05
===================

* Fix first level transitive calculation, makes sure project dependencies do not have locked versions

1.9.10 / 2014-06-04
===================

* Fix to commitLock tag
* Add -PdependencyLock.useGeneratedLock flag

1.9.9 / 2014-06-03
==================

* Allow command line overrides to work if plugin is applied, not just when creating a lock or in the presence of a lock.
* Bugfix: lock first level transitives of any project(':<project>') depended on
* Bugfix: properly respect resolutionStrategy.force 'group:artifact:version' present in build.gradle
* Add commitLock task

1.9.8 / 2014-05-02
==================

* Bug fix: properly skip interproject dependencies in a multi-project build when including transitives in the lock

1.9.7 / 2014-04-30
==================

* Fix handling of circular references in transitive dependencies

1.9.6 / 2014-04-28
==================

* Allow locking of transitive dependencies
* Fix handling of overrides not used by a subproject

1.9.5 / 2014-04-24
==================

* Document if a locked dependency is at a specific version due to a command line override
* improve multi-project support for command line overrides
* make it so generateLock will never be up-to-date

1.9.4 / 2014-04-11
==================

* command line overrides will now affect the dependency resolution during lock generation, dependency.lock will continue to be ignored during lock generation

1.9.3 / 2014-04-08
==================

* add command line overrides
* add command line file overrides

1.9.2 / 2014-03-21
==================

* Fix to exclude inter-project dependencies in an multi-project build
* Documentation updates
* Update build to use nebula-plugin-plugin:1.9.9

1.9.1 / 2014-02-18
==================

* Added task for saving lock to build directory, and task for copying to main directory
* Property to override lock file used
* Property to ignore lock files

1.9.0 / 2014-02-17
==================

* Initial release
