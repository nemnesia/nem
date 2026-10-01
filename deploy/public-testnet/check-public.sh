#!/usr/bin/env bash
set -euo pipefail

host=${1:?usage: check-public.sh PUBLIC_HOST}
"$(dirname "${BASH_SOURCE[0]}")"/check.sh "http://$host:7890"
