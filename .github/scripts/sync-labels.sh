#!/usr/bin/env bash
# Creates or updates the labels of .github/labels.yml on the repository. Never deletes a label.
# Usage: bash .github/scripts/sync-labels.sh [owner/repo]     (needs the GitHub CLI, authenticated with write access)
set -euo pipefail
repo="${1:-alertmanager12-blip/openchain-intelligence}"
cd "$(dirname "$0")/../.."
python3 - "$repo" <<'PY'
import subprocess, sys, yaml
repo = sys.argv[1]
for label in yaml.safe_load(open(".github/labels.yml")):
    subprocess.run(["gh", "label", "create", label["name"], "--repo", repo, "--force",
                    "--color", str(label["color"]), "--description", label["description"]], check=True)
    print("ok", label["name"])
PY
