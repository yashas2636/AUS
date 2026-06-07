#!/usr/bin/env bash
# Updates the image newTag in a kustomization.yaml overlay.
# Called by CI after a successful build to trigger ArgoCD sync.
#
# Required env vars:
#   APPLICATION_NAME  e.g. fibonacci-service
#   ENVIRONMENT       e.g. test | prod-mel | prod-syd
#   IMAGE_TAG         e.g. v1.0.1@sha256:<digest>
set -euo pipefail

fail() { printf '%s\n' "$1" >&2; exit 1; }

[[ -z "${APPLICATION_NAME:-}" ]] && fail "APPLICATION_NAME must be set"
[[ -z "${ENVIRONMENT:-}"      ]] && fail "ENVIRONMENT must be set"
[[ -z "${IMAGE_TAG:-}"        ]] && fail "IMAGE_TAG must be set"

# Guard against path traversal
case "$APPLICATION_NAME" in *"/"*|*".."*) fail "Invalid APPLICATION_NAME" ;; esac
case "$ENVIRONMENT"      in *"/"*|*".."*) fail "Invalid ENVIRONMENT"      ;; esac

KUSTOMIZATION="$(pwd)/gitops/applications/fibonacci/overlays/${ENVIRONMENT}/kustomization.yaml"
[[ -f "$KUSTOMIZATION" ]] || fail "Kustomization not found: ${KUSTOMIZATION}"

EXPECTED_IMAGE="registry.gitlab.com/connective-au/devops/application-images/${APPLICATION_NAME}"
export EXPECTED_IMAGE IMAGE_TAG

match_count="$(yq e '[.images[] | select(.name == strenv(EXPECTED_IMAGE))] | length' "$KUSTOMIZATION")"
[[ "${match_count}" -ge 1 ]] || fail "No image entry for ${EXPECTED_IMAGE} in ${KUSTOMIZATION}"

yq e -i '(.images[] | select(.name == strenv(EXPECTED_IMAGE))).newTag = strenv(IMAGE_TAG)' "$KUSTOMIZATION"
printf 'Updated newTag in %s → %s\n' "$KUSTOMIZATION" "$IMAGE_TAG"
