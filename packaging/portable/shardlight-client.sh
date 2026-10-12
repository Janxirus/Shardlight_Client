#!/bin/sh
# Shardlight Client portable start script for Linux and macOS.
# Needs a Java 21 or newer runtime: SHARDLIGHT_JAVA, then JAVA_HOME, then java on the PATH.
HERE=$(cd "$(dirname "$0")" && pwd)

major_of() {
    "$1" -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p' | cut -d. -f1
}

JAVA=""
for c in "$SHARDLIGHT_JAVA" "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java 2>/dev/null)"; do
    [ -n "$c" ] && [ -x "$c" ] || continue
    m=$(major_of "$c")
    if [ -n "$m" ] && [ "$m" -ge 21 ] 2>/dev/null; then JAVA="$c"; break; fi
done
if [ -z "$JAVA" ]; then
    echo "Shardlight Client needs Java 21 or newer. Install one (for example from https://adoptium.net)" >&2
    echo "or set SHARDLIGHT_JAVA to its java binary." >&2
    exit 1
fi

case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) FX=mac-aarch64 ;;
    Darwin-*) FX=mac ;;
    Linux-x86_64|Linux-amd64) FX=linux ;;
    *) echo "This portable build has no JavaFX for $(uname -s) $(uname -m)." >&2; exit 1 ;;
esac

exec "$JAVA" -Xss2m -XX:+UseG1GC -Dprism.lcdtext=false --enable-native-access=ALL-UNNAMED \
    -cp "$HERE/lib/*:$HERE/lib/javafx-$FX/*" dev.shardlight.client.app.Main "$@"
