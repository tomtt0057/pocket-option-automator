#!/usr/bin/env sh
set -e

GRADLE_WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
GRADLE_WRAPPER_PROPERTIES="gradle/wrapper/gradle-wrapper.properties"

# Validate wrapper jar exists
if [ ! -f "$GRADLE_WRAPPER_JAR" ]; then
    echo "Gradle wrapper jar not found. Downloading..."
fi

# Set JVM options
DEFAULT_JVM_OPTS="-Xmx1024m -Xms256m"

# Find java
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

# Run gradle wrapper
exec "$JAVACMD" $DEFAULT_JVM_OPTS \
    -classpath "$GRADLE_WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain "$@"
