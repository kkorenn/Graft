#!/bin/zsh
# Downloads popular OptiFine-format shader packs from Modrinth into build/shaderpacks-test for the headless
# compatibility harness (./gradlew :mc1_8_9:shaderCompat). Local testing only: never commit or redistribute them.
# Chocapic13 and SEUS Renewed have no Modrinth release (SEUS needs its EULA accepted on sonicether.com): drop their
# zips into the same folder by hand.
set -e
dir="${0:A:h}/../build/shaderpacks-test"
mkdir -p "$dir"
for slug in bsl-shaders bsl-shaders-classic complementary-reimagined complementary-unbound sildurs-vibrant-shaders \
	sildurs-enhanced-default-shaders sildurs-basic-shaders makeup-ultra-fast-shaders; do
	url=$(curl -fsS "https://api.modrinth.com/v2/project/${slug}/version" | python3 -I -c 'import sys, json; print(json.load(sys.stdin)[0]["files"][0]["url"])')
	name=$(python3 -I -c 'import sys, urllib.parse; print(urllib.parse.unquote(sys.argv[1].rsplit("/", 1)[1]).replace(" ", "_").replace("'"'"'", ""))' "$url")
	[[ -f "$dir/$name" ]] || curl -fsSL -o "$dir/$name" "$url"
	echo "${slug}: ${name}"
done
