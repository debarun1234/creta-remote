#!/usr/bin/env bash
# One-time: creates the release signing key and the keystore.properties that app/build.gradle.kts reads.
# The key is stored OUTSIDE the repo. Back it up: if it is lost you can never ship an update that installs over this app.
set -euo pipefail

cd "$(dirname "$0")/.."
KEYTOOL="${KEYTOOL:-$(command -v keytool || true)}"
if [ -z "$KEYTOOL" ]; then
  for p in "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" "$JAVA_HOME/bin/keytool"; do
    [ -x "$p" ] && KEYTOOL="$p" && break
  done
fi
[ -n "$KEYTOOL" ] || { echo "keytool not found. Set KEYTOOL=/path/to/keytool (it ships with Android Studio's JBR)."; exit 1; }

DIR="$HOME/.creta-remote"
STORE="$DIR/creta-release.jks"
ALIAS="creta"
[ -e "$STORE" ] && { echo "$STORE already exists. Refusing to overwrite it."; exit 1; }
[ -e keystore.properties ] && { echo "keystore.properties already exists. Refusing to overwrite it."; exit 1; }

mkdir -p "$DIR" && chmod 700 "$DIR"
read -r -s -p "Choose a keystore password (min 8 chars): " PW; echo
read -r -s -p "Repeat it: " PW2; echo
[ "$PW" = "$PW2" ] && [ "${#PW}" -ge 8 ] || { echo "Passwords differ or are too short."; exit 1; }

"$KEYTOOL" -genkeypair -v -keystore "$STORE" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PW" -keypass "$PW" -dname "CN=Debarun, O=Creta Remote, L=Bengaluru, C=IN"

umask 077
cat > keystore.properties <<EOF
storeFile=$STORE
storePassword=$PW
keyAlias=$ALIAS
keyPassword=$PW
EOF
echo
echo "Done. Keystore: $STORE"
echo "BACK UP that file and the password somewhere safe (password manager + a copy off this Mac)."
echo "Build the signed APK with:  ./gradlew :app:assembleRelease"
