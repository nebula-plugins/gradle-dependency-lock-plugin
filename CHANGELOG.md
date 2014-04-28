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
