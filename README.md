gradle-dependency-lock-plugin
=============================

Cloudbees Jenkins release: [![Build Status](https://netflixoss.ci.cloudbees.com/job/nebula-plugins/job/gradle-dependency-lock-plugin-1.9-release/badge/icon)](https://netflixoss.ci.cloudbees.com/job/nebula-plugins/job/gradle-dependency-lock-plugin-1.9-release/)

Cloudbees Jenkins snapshot: [![Build Status](https://netflixoss.ci.cloudbees.com/job/nebula-plugins/job/gradle-dependency-lock-plugin-1.9-snapshot/badge/icon)](https://netflixoss.ci.cloudbees.com/job/nebula-plugins/job/gradle-dependency-lock-plugin-1.9-snapshot/)

A plugin to allow people using dynamic dependency versions to lock them to specific versions.

Some project teams may prefer to have their build.gradle dependencies reflect their ideal world. A latest.release for internal dependencies. A major.+, major.minor.+, or a range \[2.0.0, 4.0.0\). Many also want to lock to specific versions for day to day development, having a tagged version always resolve identically, and for published versions to have specific dependencies.

Inspired by [Bundler](http://bundler.io)

## Usage

### Applying the Plugin

To include, add the following to your build.gradle

    buildscript {
      repositories { jcenter() }

      dependencies {
        classpath 'com.netflix.nebula:gradle-dependency-lock-plugin:1.9.+'
      }
    }

    apply plugin: 'gradle-dependency-lock'

### Tasks Provided

When the following tasks are run any existing `dependency.lock` file will be ignored. Command line overrides via `-PdependencyLock.override` or `-PdependencyLock.overrideFile` will win.

* generateLock - Generate a lock file into the build directory
* saveLock - depends on generateLock, copies generated lock into the project directory
* commitLock - If a [gradle-scm-plugin](https://github.com/nebula-plugins/gradle-scm-plugin) implementation is applied. Will commit dependencies.lock to the configured SCM. Exists only on the rootProject. Assumes scm root is at the same level as the root build.gradle.

### Extensions Provided

#### dependencyLock Extension

*Properties*

* lockFile - Name of the file to read/write dependency locks, defaults to 'dependencies.lock'
* configurations - Collection of the configuration names to read, defaults to 'testRuntime'. For java projects testRuntime is good since it extends compile, runtime, and testCompile.
* includeTransitives - Boolean if true transitvie dependencies will be included in the lock

Use the extension if you wish to configure. Each project where gradle-dependency-lock will have its own dependencyLock extension.

    dependencyLock {
      lockFile = 'dependencies.lock'
      configurationNames = ['testRuntime']
      includeTransitives = false
    }

#### commitDependencyLock Extension

*Properties*

* commitMessage - Commit message to use.
* shouldCreateTag - Boolean to tell the commitLock to create a tag, defaults to false.
* tag - A 0 argument closure that returns a String. Needs to generate a unique tag name.
* remoteRetries - Number of times to update from remote repository and retry commits.

Use the following to configure. There will be only one commitDependencyLock extension attached to the rootProject in a multiproject.  

    commitDependencyLock {
      message = 'Committing dependency lock files'
      shouldCreateTag = false
      tag = { "LockCommit-${new Date().format('yyyyMMddHHmmss')}" }
      remoteRetries = 3
    }

### Properties that Affect the Plugin

*dependencyLock.lockFile*

Allows the user to override the configured lockFile name via the command line.

    ./gradlew -PdependencyLock.lockFile=<filename> <tasks>

*dependencyLock.ignore*

Allows the user to ignore any present lockFile and/or command line overrides falling back to standard gradle dependency
resolution. Plugin checks for the existence of the property, any value will cause the fallback to standard.

    ./gradlew -PdependencyLock.ignore=true <tasks>

*dependencyLock.includeTransitives*

Allows the user to set if transitive dependencies should be included in the lock file.

    ./gradlew -PdependencyLock.includeTransitives=true <tasks>

*dependencyLock.overrideFile*

Allows the user to specify a file of overrides. This file should be in the lock file format specified below in the Lock
File Format section. These will override the locked values in the dependencies.lock file. They will be respected when
running generateLock.

    ./gradlew -PdependencyLock.overrideFile=override.lock <tasks>

*dependencyLock.override*

Allows the user to specify overrides to libraries on the command line. This override will be used over any from `dependencyLock.overrideFile`

    ./gradlew -PdependencyLock.override=group:artifact:version <tasks>

or to override multiple libraries

    ./gradlew -PdependencyLock.override=group0:artifact0:version0,group1:artifact1:version1 <tasks>

*commitDependencyLock.message*

Allows the user to override the commit message.

    ./gradlew -PcommitDependencyLock.message='commit message' <tasks> commitLock

*commitDependencyLock.tag*

Allows the user to specify a String for the tagname. If present commitLock will tag the commit with the given String.

    ./gradlew -PcommitDependencyLock.tag=mytag <tasks> commitLock

## Lock File Format

The lock file is written in a json format. The keys of the map are made up of "\<group\>:\<artifact\>". The requested entry is informational to let users know what version or range of versions was initially asked for. The locked entry is the version of the dependency the plugin will lock to.

    {
      "<group0>:<artifact0>": { "locked": "<version0>", "requested": "<requestedVersion0>" },
      "<group1>:<artifact1>": { "locked": "<version1>", "requested": "<requestedVersion1>" }
    }

If a dependency version selection was influenced by a command line argument we add a viaOverride field. The viaOverride field is informational.

    {
      "<group0>:<artifact0>": { "locked": "<version0>", "requested": "<requestedVersion0>", "viaOverride": "<overrideVersion0>" }
    }

If we include transitive dependencies.

    {
      "<directgroup>:<directartifact>": { "locked": "<directversion>", "requested": "<directrequested>" },
      "<group>:<artifact>": { "locked": "<version>", "transitive": [ "<directgroup>:<directartifact>" ]}
    }

If we don't include all transitive dependencies we still need to include the transitive information from the direct dependencies of other projects in our multi-project which we depend on. 

    {
      "<directgroup>:<directartifact>": { "locked": "<directversion>", "requested": "<directrequested>" },
      "<group>:<artifact>": { "locked": "<version>", "firstLevelTransitive": [ "<mygroup>:<mypeer>" ]},
      "<mygroup>:<mypeer>": { "project": true }
    }

And we document project dependencies.

If you have

    ...
    dependencies {
      compile project(':common')
      ...
    }

The lock will have

    {
      "group:common": { "project": true }
    }

## Example

*build.gradle*

    buildscript {
      repositories { jcenter() }
      dependencies {
        classpath 'com.netflix.nebula:gradle-dependency-lock-plugin:1.9.+'
      }
    }

    apply plugin: 'java'
    apply plugin: 'gradle-dependency-lock'

    repositories {
      mavenCentral()
    }

    dependencies {
      compile 'com.google.guava:guava:14.+'
      testCompile 'junit:junit:4.+
    }

When you run

    ./gradlew generateLock

It will output

*dependencies.lock*

    {
      "com.google.guava:guava": { "locked": "14.0.1", "requested": "14.+" },
      "junit:junit": { "locked": "4.11", "requested": "4.+" }
    }

# Possible Future Changes

### Determine Version Requested for Locked Transitives, Output Format

    {
      ...
      "<transitivegroup>:<transitiveartifact>": { "locked": "<transitiveLockedVersion>", "transitive": { "<group>:<artifact>": "<requestedVersion>", "<group1>:<artifact1>": "<requestedVersion1>" } }
      ...
    }
