#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

# Release tags are documented here; digests keep local and CI scans on identical tools.
# OSV-Scanner v2.6.0; Gitleaks v8.30.1.
osv_image='ghcr.io/google/osv-scanner@sha256:afd838850ac1a0fcc15ff4a041dc9ba11123c3f0d2666217a5f0fcf9222b55fa'
gitleaks_image='ghcr.io/gitleaks/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f'

docker info > /dev/null
./mvnw -B -ntp -Psecurity cyclonedx:makeAggregateBom
reports="$repo_root/target/security"
mkdir -p "$reports"

# Run all scans for useful evidence, but any finding or scanner error fails the command.
status=0
docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges \
  -v "$repo_root/target/bom.json:/input/bom.json:ro" \
  "$osv_image" scan source -L /input/bom.json --format=json --all-packages \
  > "$reports/osv.json" || status=1

# Secret detection is entirely local, with network disabled and output fully redacted.
# Match the host owner so report writes work without DAC-override capabilities on Linux.
docker run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges \
  --user "$(id -u):$(id -g)" \
  -v "$repo_root:/repo:ro" -v "$reports:/reports" \
  "$gitleaks_image" dir /repo --config=/repo/config/gitleaks-worktree.toml --redact=100 \
  --no-banner --timeout=120 --report-format=json --report-path=/reports/gitleaks-worktree.json \
  || status=1

docker run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges \
  --user "$(id -u):$(id -g)" \
  -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0=/repo \
  -v "$repo_root:/repo:ro" -v "$reports:/reports" \
  "$gitleaks_image" git /repo --log-opts=--all --config=/repo/.gitleaks.toml --redact=100 \
  --no-banner --timeout=120 --report-format=json --report-path=/reports/gitleaks-history.json \
  || status=1

if [ "$status" -eq 0 ]; then
  printf 'PASS: dependency, working-tree secret and local Git history scans. Reports: target/security/\n'
else
  printf 'FAIL: security findings or scanner errors. Review output and target/security/ reports.\n' >&2
fi
exit "$status"
