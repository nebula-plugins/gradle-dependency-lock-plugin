/**
 *
 *  Copyright 2019 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package nebula.plugin.dependencylock.utils

import org.gradle.util.GradleVersion
import spock.lang.Specification

class GradleVersionUtilsSpec extends Specification {
    def "current Gradle version is less than version 99.99"() {
        when:
        def isLessThan = GradleVersionUtils.currentGradleVersionIsLessThan("99.99")

        then:
        assert isLessThan
    }

    def "current Gradle version is not less than version 1.0"() {
        when:
        def isLessThan = GradleVersionUtils.currentGradleVersionIsLessThan("1.0")

        then:
        assert !isLessThan
    }

    def "current Gradle version not less than the current Gradle version"() {
        when:
        def currentVersion = GradleVersion.current().baseVersion.version
        def isLessThan = GradleVersionUtils.currentGradleVersionIsLessThan(currentVersion)

        then:
        assert !isLessThan
    }
}
