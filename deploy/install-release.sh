#!/usr/bin/env bash
set -euo pipefail

if (($# != 1)); then
  echo "Usage: $0 <release-tag>" >&2
  exit 2
fi

release_tag="$1"
repository="luokd97/milky-way-telescope-next"
install_dir="/opt/telescope-next"
temporary_dir="$(mktemp -d)"

cleanup() {
  rm -rf "$temporary_dir"
}
trap cleanup EXIT

curl --fail --location --silent --show-error \
  "https://github.com/${repository}/releases/download/${release_tag}/telescope-next.jar" \
  --output "${temporary_dir}/telescope-next.jar"

curl --fail --location --silent --show-error \
  "https://github.com/${repository}/releases/download/${release_tag}/telescope-next.jar.sha256" \
  --output "${temporary_dir}/telescope-next.jar.sha256"

(
  cd "$temporary_dir"
  sha256sum --check telescope-next.jar.sha256
)

install -d -m 0755 "$install_dir"
install -m 0644 "${temporary_dir}/telescope-next.jar" "${install_dir}/app.jar.new"
mv "${install_dir}/app.jar.new" "${install_dir}/app.jar"

echo "Installed ${repository} ${release_tag} to ${install_dir}/app.jar"
echo "Restart was not performed. Review the service, then restart it manually."
