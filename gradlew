#!/bin/sh
#
# Gradle start up script for UN*X
#
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
MAX_FD="maximum"
warn () { echo "$*"; }
die () { echo; echo "$*"; echo; exit 1; }
OS_NAME=`uname`
case $OS_NAME in
  CYGWIN* ) cygwin=true  ;;
  Darwin*  ) darwin=true  ;;
  MSYS* | MINGW* ) msys=true ;;
esac
SCRIPT=`(cd "$(dirname "$0")" && pwd -P)`
APP_HOME=$SCRIPT
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
JAVACMD=java
exec "$JAVACMD" $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
