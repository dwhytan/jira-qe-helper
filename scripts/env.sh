# Entorno común de los scripts locales: JDK 21 y Maven (el de IntelliJ si no hay otro en PATH).
if [[ -x /usr/libexec/java_home ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)" || { echo "No se encontró JDK 21"; exit 1; }
  export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
fi
MVN="$(command -v mvn || echo "/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn")"
