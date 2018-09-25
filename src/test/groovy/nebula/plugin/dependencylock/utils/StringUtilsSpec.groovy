package nebula.plugin.dependencylock.utils

import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

@Subject(StringUtils)
@Unroll
class StringUtilsSpec extends Specification {

    def 'checking if "#str" string is empty, results in #expectedResult'() {
        when:
        boolean result = StringUtils.isEmpty(str)

        then:
        result == expectedResult

        where:
        str       | expectedResult
        null      | true
        ""        | true
        " "       | false
        "foo"     | false
        "  foo  " | false
    }

    def 'substringAfter for #str results in #expectedResult'() {
        when:
        String substring = StringUtils.substringAfter(str, separator)

        then:
        substring == expectedResult

        where:
        str             | separator | expectedResult
        'fooXXbarXXbaz' | 'XX'      | 'barXXbaz'
        null            | null      | null
        null            | ''        | null
        null            | 'XX'      | null
        ''              | null      | ""
        ''              | ''        | ''
        ''              | 'XX'      | ''
        'foot'          | 'o'       | 'ot'
        'abc'           | 'a'       | 'bc'
        'abcba'         | 'b'       | 'cba'
        'abc'           | 'c'       | ''
        'abc'           | 'd'       | ''
    }

    def 'uncapitalize for #str results in #expectedResult'() {
        when:
        String uncapitalized = StringUtils.uncapitalize(str)

        then:
        uncapitalized == expectedResult

        where:
        str   | expectedResult
        'foo' | 'foo'
        'Foo' | 'foo'
        'FOO' | 'fOO'
        'X'   | 'x'
    }
}
