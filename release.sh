#!/usr/bin/env bash
set -euo pipefail

# ── USAGE ──────────────────────────────────────────────────────────────
# ./release.sh <version> [release-title]
# ./release.sh 1.7.0
# ./release.sh 1.7.0 "Whitelist & Gamerule editor"

VERSION="${1:-}"

if [[ -z "$VERSION" ]]; then
  LATEST=$(git describe --tags --abbrev=0 2>/dev/null || echo "none")
  echo "Usage : ./release.sh <version> [title]"
  echo "Latest: $LATEST"
  echo ""
  echo "Unreleased commits:"
  git log "${LATEST}..HEAD" --oneline 2>/dev/null || git log --oneline -5
  exit 1
fi

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Error: version must be x.y.z (e.g. 1.7.0)"
  exit 1
fi

TAG="v${VERSION}"
TITLE="${2:-$TAG}"
JAR="build/libs/ServerDashboard-${VERSION}.jar"
PREV_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")

# ── GUARD ──────────────────────────────────────────────────────────────
if git rev-parse "$TAG" &>/dev/null 2>&1; then
  echo "Error: tag $TAG already exists."
  exit 1
fi

echo ""
echo "  Release : $TAG  —  $TITLE"
[[ -n "$PREV_TAG" ]] && echo "  Since   : $PREV_TAG"
echo ""

# ── SHOW PENDING CHANGES ───────────────────────────────────────────────
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "Modified files:"
  git status --short
  echo ""
fi

# ── BUMP VERSIONS ──────────────────────────────────────────────────────
sed -i "s/^version = \".*\"/version = \"$VERSION\"/" build.gradle.kts
sed -i "s/^version: '.*'/version: '$VERSION'/" src/main/resources/plugin.yml
echo "[1/4] Version bumped → $VERSION"

# ── BUILD ───────────────────────────────────────────────────────────────
echo "[2/4] Building..."
./gradlew shadowJar -q

if [[ ! -f "$JAR" ]]; then
  echo "Error: JAR not found at $JAR"
  exit 1
fi

SIZE=$(du -h "$JAR" | cut -f1)
echo "      $JAR  ($SIZE)"

# ── COMMIT ──────────────────────────────────────────────────────────────
echo "[3/4] Committing..."
git add -u
git commit -m "chore: release $TAG"
git push
echo "      Pushed."

# ── GITHUB RELEASE ──────────────────────────────────────────────────────
echo "[4/4] Creating GitHub release..."

if [[ -n "$PREV_TAG" ]]; then
  NOTES=$(git log "${PREV_TAG}..HEAD~1" --pretty=format:"- %s" 2>/dev/null || echo "")
else
  NOTES=$(git log --pretty=format:"- %s" HEAD~10..HEAD~1 2>/dev/null || echo "")
fi

RELEASE_URL=$(gh release create "$TAG" "$JAR" \
  --title "$TITLE" \
  --notes "$NOTES" \
  --json url -q .url)

echo ""
echo "Done! $RELEASE_URL"
