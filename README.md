Gradle Dependency Lock Plugin
=============================

![Support Status](https://img.shields.io/badge/nebula-supported-brightgreen.svg)
[![Build Status](https://travis-ci.org/nebula-plugins/gradle-dependency-lock-plugin.svg?branch=master)](https://travis-ci.org/nebula-plugins/gradle-dependency-lock-plugin)
[![Coverage Status](https://coveralls.io/repos/nebula-plugins/gradle-dependency-lock-plugin/badge.svg?branch=master&service=github)](https://coveralls.io/github/nebula-plugins/gradle-dependency-lock-plugin?branch=master)
[![Gitter](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/nebula-plugins/gradle-dependency-lock-plugin?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge)
[![Apache 2.0](https://img.shields.io/github/license/nebula-plugins/gradle-dependency-lock-plugin.svg)](http://www.apache.org/licenses/LICENSE-2.0)

A plugin to allow people using dynamic dependency versions to lock them to specific versions.

Some project teams may prefer to have their build.gradle dependencies reflect their ideal world. A latest.release for internal dependencies. A major.+, major.minor.+, or a range \[2.0.0, 4.0.0\). Many also want to lock to specific versions for day to day development, having a tagged version always resolve identically, and for published versions to have specific dependencies.

Inspired by [Bundler](http://bundler.io)

# Quick Start

Refer to the [Gradle Plugin Portal](https://plugins.gradle.org/plugin/nebula.dependency-lock) for instructions on how to apply the plugin.

# Documentation

The project wiki contains the [full documentation](https://github.com/nebula-plugins/gradle-dependency-lock-plugin/wiki) for the plugin.

Gradle Compatibility Tested
---------------------------

Built with Oracle JDK7
Tested with Oracle JDK8

| Gradle Version | Works |
| :------------: | :---: |
| 2.2.1          | yes   |
| 2.3            | yes   |
| 2.4            | yes   |
| 2.5            | yes   |
| 2.6            | yes   |
| 2.7            | yes   |
| 2.8            | yes   |
| 2.9            | yes   |
| 2.10           | yes   |
| 2.11           | yes   |
| 2.12           | yes   |
| 2.13           | yes   |
| 2.14.1         | yes   |
| 3.0            | yes   |
| 3.1            | yes   |

LICENSE
=======

Copyright 2014-2016 Netflix, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

<http://www.apache.org/licenses/LICENSE-2.0>

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
