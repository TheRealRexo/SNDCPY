#!/usr/bin/env bash
#
#  clean_and_redeploy.sh
#  Run this script from the root of the SNDCPY repo.
#  Terminal session must be authenticated with GitHub (e.g. via SSH key or HTTPS + PAT).
#  -------------------------------------------------

set -euo pipefail

# 1️⃣  OPTIONAL:  If you’re not happy about the huge DerivedData, remove it first
echo "Deleting local build artefacts…"
# (These are large – adjust if you prefer to keep a few)
rm -rf iosclient/DerivedData
rm -rf iosclient/build/ipa*
rm -rf iosclient/build/SNDCPY-iOS-*
rm -rf androidclient/build
rm -rf androidserver/app/build

# 2️⃣  Remove the pre‑existing main branch locally, create a clean orphan commit
echo "Cleaning git history…"
git checkout --orphan clean
git reset
git add -A
git commit -m "Clean push of version 1.0.0 -- source only, no artefacts"
git branch -D main
git branch -m main

# 3️⃣  Delete any old tags on the remote (so we’re free to push a fresh v1.0.0)
echo "Pruning old tags…"
# List tags first – you’ll see an empty list if there are none
git ls-remote --tags origin | awk '{print $2}' | tr -d 'refs/tags/' | while read t; do
  git push origin :refs/tags/$t
done

# 4️⃣  Push the clean repository to GitHub
echo "Pushing cleaned repo…"
git push --force origin main

# 5️⃣  Tag as the new release (this will trigger the GitHub CI workflow)
echo "Tagging v1.0.0 and pushing…"
git tag -f v1.0.0
git push --force origin v1.0.0

echo
echo "✅  Cleaned repo and created new v1.0.0 tag."
echo "The next GitHub Actions release will rebuild all artifacts."
echo
echo "When the workflow completes you’ll find:"
echo
echo " • Android:    androidclient/build/outputs/apk/release/SNDCPY-Client-1.0.0.apk"
echo "               androidserver/app/build/outputs/apk/release/SNDCPY-Server-1.0.0.apk"
echo
echo " • iOS (device): iosclient/build/SNDCPY-iOS-1.0.0-device.ipa"
echo " • iOS (simulator ZIP): iosclient/build/SNDCPY-iOS-simulator-1.0.0.zip"
echo
echo "Install instructions:"
echo
echo " ── Android (phone/tablet) ────────────────────────────────────────"
echo "   • Use adb sideload:   adb install /path/to/SNDCPY-Client-1.0.0.apk"
echo "   • Or copy the APK to the device and open it in the Files app."
echo
echo " ── iOS (iPhone/iPad) ─────────────────────────────────────────────"
echo "   • Copy the .ipa to your Mac."
echo "   • Launch Xcode → \"Window\" → \"Devices and Simulators\"."
echo "   • Drag the .ipa onto your device’s \"Installed Apps\" list."
echo "   • Alternatively, sideload with AltStore / Cydia Impactor."
echo
echo "Once the artifacts are on the device, you’ll be able to run SNDCPY end‑to‑end."
echo
