package nebula.plugin.dependencylock.utils

class StringUtils {

    static String uncapitalize(String str) {
        int strLen
        return str != null && (strLen = str.length()) != 0 ? (new StringBuilder(strLen)).append(Character.toLowerCase(str.charAt(0))).append(str.substring(1)).toString() : str
    }

    static String substringAfter(String str, String separator) {
        if (isEmpty(str)) {
            return str
        } else if (separator == null) {
            return ""
        } else {
            int pos = str.indexOf(separator)
            return pos == -1 ? "" : str.substring(pos + separator.length())
        }
    }

    static boolean isEmpty(String str) {
        return str == null || str.length() == 0
    }
}
