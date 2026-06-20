#!/bin/sh
# Gradle start up script for POSIX (généré). Utilisez Android Studio ou
# `./gradlew assembleDebug` après avoir laissé Gradle télécharger le wrapper jar.
APP_HOME=$(cd "$(dirname "$0")" && pwd)
exec "${JAVA_HOME:-/usr}/bin/java" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
