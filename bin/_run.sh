#!/usr/bin/env bash
# Avvia una classe del progetto con le dipendenze in target/lib.
# Lucene 10 richiede Java 21+: cerca un JDK adatto (JAVA_HOME, Homebrew, java_home, PATH).
cd "$(dirname "$0")/.." || exit 1

java_major() { "$1" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/{print $2}' | cut -d. -f1; }
JAVA=""
for c in "${JAVA_HOME:+$JAVA_HOME/bin/java}" /opt/homebrew/opt/openjdk/bin/java /usr/local/opt/openjdk/bin/java \
         "$(/usr/libexec/java_home -v 21+ 2>/dev/null)/bin/java" "$(command -v java)"; do
  if [ -n "$c" ] && [ -x "$c" ] && [ "$(java_major "$c")" -ge 21 ] 2>/dev/null; then JAVA="$c"; break; fi
done
if [ -z "$JAVA" ]; then echo "Serve Java 21 o superiore (es. brew install openjdk)" >&2; exit 1; fi

if [ ! -d target/lib ]; then
  echo "Compilo il progetto (mvn -q package)..." >&2
  mvn -q -DskipTests package || exit 1
fi
CLASS="$1"; shift
exec "$JAVA" --enable-native-access=ALL-UNNAMED -Djava.util.logging.config.file=bin/logging.properties -cp "target/classes:target/lib/*" "hw3.$CLASS" "$@"
