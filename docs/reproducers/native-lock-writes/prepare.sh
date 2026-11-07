#!/bin/sh
# Creates a disposable build without applying the dependency-lock plugin.
set -eu

fixture_dir=$(mktemp -d "${TMPDIR:-/tmp}/native-lock-writes.XXXXXX")
mkdir -p "$fixture_dir/empty" "$fixture_dir/libraries/lib" "$fixture_dir/tools/lib"
cat > "$fixture_dir/settings.gradle" <<'EOF'
rootProject.name = 'native-lock-writes'
include ':libraries:lib', ':tools:lib'
EOF
cat > "$fixture_dir/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx512m
org.gradle.workers.max=2
EOF
touch "$fixture_dir/build.gradle"

for module in widget other; do
    for version in 1.0 1.1; do
        artifact_dir="$fixture_dir/repo/fixture/$module/$version"
        mkdir -p "$artifact_dir"
        cat > "$artifact_dir/$module-$version.pom" <<EOF
<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>$module</artifactId><version>$version</version></project>
EOF
        jar --create --file "$artifact_dir/$module-$version.jar" -C "$fixture_dir/empty" .
    done
    cat > "$fixture_dir/repo/fixture/$module/maven-metadata.xml" <<EOF
<metadata><groupId>fixture</groupId><artifactId>$module</artifactId><versioning><latest>1.1</latest><release>1.1</release><versions><version>1.0</version><version>1.1</version></versions></versioning></metadata>
EOF
done

for child in libraries tools; do
    cat > "$fixture_dir/$child/lib/build.gradle" <<'EOF'
plugins { id 'java-library' }
repositories { maven { url = uri(new File(rootDir, 'repo')) } }
dependencies {
    implementation 'fixture:widget:1.+'
    implementation 'fixture:other:1.+'
}
dependencyLocking { lockAllConfigurations() }
EOF
done

printf '%s\n' "$fixture_dir"
