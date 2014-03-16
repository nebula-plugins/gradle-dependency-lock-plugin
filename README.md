gradle-dependency-lock-plugin
=============================

Cloudbees Jenkins release: [![Build Status](https://netflixoss.ci.cloudbees.com/job/nebula-plugins/job/gradle-dependency-lock-plugin-release/badge/icon)](https://netflixoss.ci.cloudbees.com/job/nebula-plugins/job/gradle-dependency-lock-plugin-release/)

Cloudbees Jenkins snapshot: [![Build Status](https://netflixoss.ci.cloudbees.com/job/nebula-plugins/job/gradle-dependency-lock-plugin-snapshot/badge/icon)](https://netflixoss.ci.cloudbees.com/job/nebula-plugins/job/gradle-dependency-lock-plugin-snapshot/)

A plugin to allow people using dynamic dependency versions to lock them to specific versions.

Some project teams may prefer to have their build.gradle dependencies reflect their ideal world. A latest.release for
internal dependencies. A major.+, major.minor.+, or a range \[2.0.0, 4.0.0\). Many also want to lock to specific versions
for day to day development, having a tagged version always resolve identically, and for published versions to have
specific dependencies.

Inspired by [Bundler](http://bundler.io)

## Usage

### Applying the Plugin

To include, add the following to your build.gradle

    buildscript {
      repositories { jcenter() }

      dependencies {
        classpath 'com.netflix.nebula:gradle-dependency-lock-plugin:1.9.1'
      }
    }

    apply plugin: 'gradle-dependency-lock'

### Tasks Provided

* generateLock - Generate a lock file into the build directory
* saveLock - depends on generateLock, copies generated lock into the project directory

### Extensions Provided

* lockFile - Name of the file to read/write dependency locks, defaults to 'dependencies.lock'
* configurations - Collection of the configuration names to read, defaults to 'testRuntime'. For java projects
testRuntime is good since it extends compile, runtime, and testCompile.

Use the extension if you wish to configure.

    dependencyLock {
      lockFile = 'dependencies.lock'
      configurationNames = ['testRuntime']
    }

### Properties that Effect the Plugin

*dependencyLock.lockFile*

Allows the user to override the configured lockFile name via the command line.

    ./gradlew -PdependencyLock.lockFile=<filename> <tasks>

*dependencyLock.ignore*

Allows the user to ignore any present lockFile and fall back to standard gradle dependency resolution. Plugin checks for
the existence of the property, any value will cause the fallback to standard.

    ./gradlew -PdependencyLock.ignore=true <tasks>

## Lock File Format

The lock file is written in a json format. The keys of the map are made up of "\<group\>:\<artifact\>".
The locked entry is the version of the dependency the plugin will lock to.

    {
      "group0:artifact0": { "locked": "<version0>" },
      "group1:artifact1": { "locked": "<version1>" }
    }

## Example

*build.gradle*

    buildscript {
      repositories { jcenter() }
      dependencies {
        classpath 'com.netflix.nebula:gradle-dependency-lock-plugin:1.9.1'
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

    ./gradlew lockDependencies

It will output

*dependencies.lock*

    {
      "com.google.guava:guava": { "locked": "14.0.1" },
      "junit:junit": { "locked": "4.11" },
      "org.hamcrest:hamcrest-core": { "locked": "1.3" }
    }
