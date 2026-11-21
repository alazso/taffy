#!/usr/bin/env bash
# Resolves the version and the tag of a run of the Release workflow, in the root of the checkout.
#
# Reads the version line of gradle.properties and these variables:
#   EVENT_NAME        push for a tag push, anything else for a manual run
#   DRY_RUN           true for a manual run that builds and does not publish
#   GITHUB_REF_NAME   the pushed tag, read on a push only
#   GITHUB_OUTPUT     the file that receives the lines version=<version> and tag=<tag>
#
# Exits 1 and writes nothing to GITHUB_OUTPUT when gradle.properties has no version, when the version is a
# snapshot outside a dry run, when the pushed tag is not v<version>, and when a manual run that publishes finds
# no tag v<version> or finds it on a commit other than HEAD. The manual run reads the tags of the checkout, so
# the checkout fetches them.
set -euo pipefail
GRADLE_VERSION="$(sed -n 's/^version=//p' gradle.properties | tr -d '[:space:]')"
if [ -z "$GRADLE_VERSION" ]; then
  echo "Could not read version from gradle.properties" >&2
  exit 1
fi
if [[ "$GRADLE_VERSION" == *-SNAPSHOT ]] && [ "$DRY_RUN" != "true" ]; then
  echo "gradle.properties has the snapshot version ${GRADLE_VERSION}; the CI workflow publishes snapshots." >&2
  exit 1
fi
if [ "$EVENT_NAME" = "push" ]; then
  TAG="${GITHUB_REF_NAME}"
  TAG_VERSION="${TAG#v}"
  if [ "$TAG_VERSION" != "$GRADLE_VERSION" ]; then
    echo "Tag $TAG (${TAG_VERSION}) does not match gradle.properties (${GRADLE_VERSION})" >&2
    exit 1
  fi
else
  TAG="v${GRADLE_VERSION}"
  if [ "$DRY_RUN" != "true" ]; then
    if ! TAG_COMMIT="$(git rev-parse -q --verify "refs/tags/${TAG}^{commit}")"; then
      echo "Tag $TAG does not exist; push it before publishing ${GRADLE_VERSION}." >&2
      exit 1
    fi
    if [ "$TAG_COMMIT" != "$(git rev-parse HEAD)" ]; then
      echo "Tag $TAG is at ${TAG_COMMIT}, not at the commit being built; run the workflow on the tag." >&2
      exit 1
    fi
  fi
fi
echo "version=${GRADLE_VERSION}" >> "$GITHUB_OUTPUT"
echo "tag=${TAG}" >> "$GITHUB_OUTPUT"
