#!/usr/bin/env bash
# Publishes a new version that installed copies of the app will offer to update to.
#   ./scripts/release.sh 1.0.1 "What changed"
# Needs: keystore.properties (./scripts/make-keystore.sh), a clean git tree, and the GitHub CLI (gh) logged in for step 5.
# Every release must be signed with the SAME key, or Android refuses to update the installed app in place.
set -euo pipefail
cd "$(dirname "$0")/.."

VER="${1:-}"
NOTES="${2:-Release $VER}"
[[ "$VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Usage: $0 X.Y.Z \"release notes\"   (for example 1.0.1)"; exit 1; }
[ -f keystore.properties ] || { echo "No keystore.properties. Run ./scripts/make-keystore.sh first."; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Commit or stash your changes first; the release commit should contain only the version bump."; exit 1; }
git rev-parse "v$VER" >/dev/null 2>&1 && { echo "Tag v$VER already exists."; exit 1; }

echo "1/5 Setting version $VER"
sed -i.bak -E "s/val appVersion = \"[0-9.]+\"/val appVersion = \"$VER\"/" app/build.gradle.kts && rm -f app/build.gradle.kts.bak

echo "2/5 Testing and building the signed release APK"
./gradlew :app:testDebugUnitTest :app:assembleRelease
mkdir -p build
OUT="build/creta-remote-$VER.apk"
cp app/build/outputs/apk/release/app-release.apk "$OUT"

echo "3/5 Committing and tagging"
git add app/build.gradle.kts
# the version may already be $VER (a first release), in which case there is nothing to commit and the tag goes on the current commit
git diff --cached --quiet || git commit -m "Release $VER"
git tag "v$VER"

echo "4/5 Pushing"
git push origin HEAD "v$VER"

echo "5/5 Publishing the GitHub release"
if command -v gh >/dev/null 2>&1; then
  gh release create "v$VER" "$OUT" --title "Creta Remote $VER" --notes "$NOTES"
  echo "Done. Installed copies will offer version $VER the next time they open."
else
  echo "gh is not installed. Create the release by hand: https://github.com/debarun1234/creta-remote/releases/new"
  echo "  tag: v$VER   attach: $OUT"
fi
