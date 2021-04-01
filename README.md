Gradle Dependency Lock Plugin
=============================

![Support Status](https://img.shields.io/badge/nebula-active-green.svg)
[![Gradle Plugin Portal](https://img.shields.io/maven-metadata/v/https/plugins.gradle.org/m2/com.netflix.nebula/gradle-dependency-lock-plugin/maven-metadata.xml.svg?label=gradlePluginPortal)](https://plugins.gradle.org/plugin/nebula.dependency-lock)
[![Maven Central](https://img.shields.io/maven-central/v/com.netflix.nebula/gradle-dependency-lock-plugin)](https://maven-badges.herokuapp.com/maven-central/com.netflix.nebula/gradle-dependency-lock-plugin)
![Build](https://github.com/nebula-plugins/gradle-dependency-lock-plugin/actions/workflows/nebula.yml/badge.svg)
[![Apache 2.0](https://img.shields.io/github/license/nebula-plugins/gradle-dependency-lock-plugin.svg)](http://www.apache.org/licenses/LICENSE-2.0)



A plugin to allow people using dynamic dependency versions to lock them to specific versions.

Some project teams may prefer to have their build.gradle dependencies reflect their ideal world. A latest.release for internal dependencies. A major.+, major.minor.+, or a range \[2.0.0, 4.0.0\). Many also want to lock to specific versions for day to day development, having a tagged version always resolve identically, and for published versions to have specific dependencies.

Inspired by [Bundler](http://bundler.io)

# Quick Start

Refer to the [Gradle Plugin Portal](https://plugins.gradle.org/plugin/nebula.dependency-lock) for instructions on how to apply the plugin.

# Documentation

The project wiki contains the [full documentation](https://github.com/nebula-plugins/gradle-dependency-lock-plugin/wiki) for the plugin.

LICENSE
=======

Copyright 2014-2021 Netflix, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

<http://www.apache.org/licenses/LICENSE-2.0>

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
