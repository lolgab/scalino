#!/usr/bin/env bash
# `scalino publish-local` / `scalino publish` (cli/Publish.scala):
#  - publish-local of a Scala Native and a Scala.js library into ~/.ivy2/local, consumed by a second
#    project through a plain `//> using dep org::name::version` (no repository: coursier's default
#    ivy2Local), with an inline method and a transitive dependency of the library;
#  - jar contents, checksums, reproducible bytes, POM/ivy.xml shape;
#  - --m2 into a Maven local repository, consumed with `-r file://...`;
#  - publish to a directory, signed with a throwaway gpg key (PGP_SECRET/PGP_PASSPHRASE);
#  - publish to Maven Central against a local mock of the Central Portal API (tests/publish/mock_central.py):
#    bundle layout, signatures, auth header, status polling, a FAILED deployment, -SNAPSHOT PUTs;
#  - the Central metadata check.
#
# Writes under ~/.ivy2/local/scalino.test.publish.<random> and removes it at exit.
#
# Usage: tests/publish/run.sh [path-to-scalino]   (default: dist/scalino)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALINO="$(cd "$(dirname "${1:-$ROOT/dist/scalino}")" && pwd)/$(basename "${1:-$ROOT/dist/scalino}")"
WORK="$(mktemp -d)"
ORG="scalino.test.publish.$RANDOM$RANDOM"
# gpg-agent's socket goes in GNUPGHOME on macOS, which must stay short
GNUPG="$(mktemp -d /tmp/sgt.XXXXXX)"
MOCK_PID=""
cleanup() {
  [[ -n "$MOCK_PID" ]] && { kill "$MOCK_PID"; wait "$MOCK_PID"; } 2>/dev/null || true
  gpgconf --homedir "$GNUPG" --kill all 2>/dev/null || true
  rm -rf "$WORK" "$GNUPG" "$HOME/.ivy2/local/$ORG"
}
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
SN="$("$SCALINO" version | sed -n 's/.*Scala Native \([0-9]*\.[0-9]*\).*/\1/p')"
NATIVE="greeter_native${SN}_3"

mkdir -p "$WORK/lib" "$WORK/app"
cat > "$WORK/lib/Greeter.scala" <<EOF
//> using publish.organization $ORG
//> using publish.name greeter
//> using publish.version 0.1.0
//> using publish.description "Says hello"
//> using publish.license Apache-2.0
//> using publish.url https://example.com/greeter
//> using publish.versionControl github:example/greeter
//> using publish.developer "jdoe|Jane Doe|https://example.com/jdoe|jane@example.com"
//> using publish.versionScheme early-semver
//> using dep com.lihaoyi::fansi::0.5.1
package greeter

object Greeter:
  def greet(name: String): String = fansi.Color.Green(s"hello, \$name").plainText
  inline def twice(x: Int): Int = x * 2
EOF
mkdir -p "$WORK/lib/resources"
echo "greeting=hi" > "$WORK/lib/resources/greeter.properties"
echo "//> using resourceDir resources" >> "$WORK/lib/Greeter.scala"
cat > "$WORK/app/Main.scala" <<EOF
//> using dep $ORG::greeter::0.1.0
@main def main = println(greeter.Greeter.greet("world") + " " + greeter.Greeter.twice(21))
EOF

echo "== publish-local (Scala Native) into ~/.ivy2/local"
cd "$WORK/lib"
"$SCALINO" publish-local . > "$WORK/out" 2>&1 || { cat "$WORK/out" >&2; fail "publish-local"; }
grep -q "depend on it with: //> using dep $ORG::greeter::0.1.0" "$WORK/out" || fail "no usage hint: $(cat "$WORK/out")"
IVY="$HOME/.ivy2/local/$ORG/$NATIVE/0.1.0"
for f in jars/$NATIVE.jar srcs/$NATIVE-sources.jar docs/$NATIVE-javadoc.jar poms/$NATIVE.pom ivys/ivy.xml; do
  for ext in "" .md5 .sha1; do [[ -f "$IVY/$f$ext" ]] || fail "missing $f$ext"; done
  [[ "$(shasum -a 1 "$IVY/$f" | cut -c1-40)" == "$(cat "$IVY/$f.sha1")" ]] || fail "$f.sha1 is wrong"
  [[ "$( (md5 -q "$IVY/$f" 2>/dev/null || md5sum "$IVY/$f") | cut -c1-32)" == "$(cat "$IVY/$f.md5")" ]] || fail "$f.md5 is wrong"
done
unzip -Z1 "$IVY/jars/$NATIVE.jar" > "$WORK/entries"
for e in META-INF/MANIFEST.MF greeter/Greeter.tasty greeter/Greeter.nir 'greeter/Greeter$.nir' greeter.properties; do
  grep -qxF "$e" "$WORK/entries" || fail "main jar lacks $e: $(cat "$WORK/entries")"
done
unzip -tq "$IVY/jars/$NATIVE.jar" > /dev/null || fail "main jar is corrupt"
[[ "$(unzip -Z1 "$IVY/srcs/$NATIVE-sources.jar" | grep -v '/$')" == "$(printf 'META-INF/MANIFEST.MF\nGreeter.scala')" ]] || fail "sources jar: $(unzip -Z1 "$IVY/srcs/$NATIVE-sources.jar")"
POM="$IVY/poms/$NATIVE.pom"
for s in "<artifactId>$NATIVE</artifactId>" "<artifactId>fansi_native${SN}_3</artifactId>" "<artifactId>nativelib_native${SN}_3</artifactId>" \
         "<artifactId>scala3-library_3</artifactId>" "<url>https://spdx.org/licenses/Apache-2.0.html</url>" \
         "<connection>scm:git:github.com/example/greeter.git</connection>" "<email>jane@example.com</email>" \
         "<info.versionScheme>early-semver</info.versionScheme>"; do
  grep -qF "$s" "$POM" || fail "POM lacks $s"
done
grep -q 'SNAPSHOT' "$POM" && fail "the POM depends on a snapshot"
grep -qF "name=\"fansi_native${SN}_3\" rev=\"0.5.1\" conf=\"compile->default(compile)\"" "$IVY/ivys/ivy.xml" || fail "ivy.xml lacks fansi"
cp "$IVY/jars/$NATIVE.jar" "$WORK/first.jar"
"$SCALINO" publish-local . -q > /dev/null 2>&1 || fail "second publish-local"
cmp -s "$WORK/first.jar" "$IVY/jars/$NATIVE.jar" || fail "republishing the same sources changed the jar bytes"

echo "== a second project depends on it"
cd "$WORK/app"
[[ "$("$SCALINO" run . -q 2>/dev/null)" == "hello, world 42" ]] || { "$SCALINO" run . >&2 || true; fail "native consumer"; }

echo "== publish-local --js, consumed with --js"
cd "$WORK/lib"
"$SCALINO" publish-local . --js -q > /dev/null 2>&1 || fail "publish-local --js"
JS="$HOME/.ivy2/local/$ORG/greeter_sjs1_3/0.1.0"
unzip -Z1 "$JS/jars/greeter_sjs1_3.jar" | grep -qxF 'greeter/Greeter$.sjsir' || fail "js jar lacks .sjsir"
unzip -Z1 "$JS/jars/greeter_sjs1_3.jar" | grep -q '\.nir$' && fail "js jar has .nir"
grep -qF '<artifactId>scalajs-library_2.13</artifactId>' "$JS/poms/greeter_sjs1_3.pom" || fail "js POM lacks scalajs-library"
grep -qF '<artifactId>fansi_sjs1_3</artifactId>' "$JS/poms/greeter_sjs1_3.pom" || fail "js POM lacks fansi_sjs1_3"
if command -v node > /dev/null; then
  cd "$WORK/app"
  [[ "$("$SCALINO" run . --js -q 2>/dev/null | tail -1)" == "hello, world 42" ]] || fail "js consumer"
fi

echo "== publish-local --m2-home, consumed through -r"
cd "$WORK/lib"
"$SCALINO" publish-local . --m2-home "$WORK/m2" --project-version 0.2.0 -q > /dev/null 2>&1 || fail "publish-local --m2-home"
M2="$WORK/m2/$(echo "$ORG" | tr . /)/$NATIVE"
for f in 0.2.0/$NATIVE-0.2.0.jar 0.2.0/$NATIVE-0.2.0-sources.jar 0.2.0/$NATIVE-0.2.0.pom maven-metadata-local.xml; do
  [[ -f "$M2/$f" ]] || fail "m2: missing $f"
done
mkdir -p "$WORK/app2"
printf '//> using dep %s::greeter::0.2.0\n@main def main = println(greeter.Greeter.twice(2))\n' "$ORG" > "$WORK/app2/Main.scala"
cd "$WORK/app2"
[[ "$("$SCALINO" run . -q -r "file://$WORK/m2" 2>/dev/null)" == "4" ]] || fail "m2 consumer"

echo "== publish to a directory, signed with PGP_SECRET"
gpg --homedir "$GNUPG" --batch --passphrase pw --quick-gen-key 'Scalino Test <test@example.invalid>' ed25519 sign never > /dev/null 2>&1
export PGP_SECRET="$(gpg --homedir "$GNUPG" --batch --pinentry-mode loopback --passphrase pw --armor --export-secret-keys | base64)"
export PGP_PASSPHRASE=pw
cd "$WORK/lib"
"$SCALINO" publish . -R "$WORK/repo" --signer gpg -q > /dev/null 2>&1 || fail "publish to a directory"
R="$WORK/repo/$(echo "$ORG" | tr . /)/$NATIVE"
for f in $NATIVE-0.1.0.jar $NATIVE-0.1.0-sources.jar $NATIVE-0.1.0-javadoc.jar $NATIVE-0.1.0.pom; do
  gpg --homedir "$GNUPG" --verify "$R/0.1.0/$f.asc" "$R/0.1.0/$f" 2>&1 | grep -q 'Good signature' || fail "bad signature on $f"
done
grep -q '<version>0.1.0</version>' "$R/maven-metadata.xml" || fail "no maven-metadata.xml"

echo "== publish to Central (mock Portal API)"
python3 "$ROOT/tests/publish/mock_central.py" "$WORK/mock" > "$WORK/port" &
MOCK_PID=$!
mkdir -p "$WORK/mock"
for _ in $(seq 50); do [[ -s "$WORK/port" ]] && break; sleep 0.1; done
export SCALINO_CENTRAL_URL="http://127.0.0.1:$(cat "$WORK/port")" SCALINO_CENTRAL_POLL_MS=50
SONATYPE_USERNAME=user SONATYPE_PASSWORD=token "$SCALINO" publish . > "$WORK/out" 2>&1 || { cat "$WORK/out" >&2; fail "publish to central"; }
grep -q "Published $ORG:$NATIVE:0.1.0 to Maven Central" "$WORK/out" || fail "no success line: $(cat "$WORK/out")"
python3 - "$WORK/mock/requests.jsonl" <<'EOF' || fail "upload request"
import json, sys
reqs = [json.loads(l) for l in open(sys.argv[1])]
up = reqs[0]
assert up["path"] == "/api/v1/publisher/upload", up
assert up["query"]["publishingType"] == ["AUTOMATIC"], up
assert up["field"] == "bundle", up
assert up["auth"] == "Bearer dXNlcjp0b2tlbg==", up  # base64("user:token")
assert [r["path"] for r in reqs[1:]] == ["/api/v1/publisher/status"] * 3, reqs
EOF
unzip -Z1 "$WORK/mock/bundle.zip" | sort > "$WORK/bundle"
DIR="$(echo "$ORG" | tr . /)/$NATIVE/0.1.0"
for f in $NATIVE-0.1.0.jar $NATIVE-0.1.0-sources.jar $NATIVE-0.1.0-javadoc.jar $NATIVE-0.1.0.pom; do
  for ext in "" .asc .md5 .sha1; do grep -qxF "$DIR/$f$ext" "$WORK/bundle" || fail "bundle lacks $f$ext"; done
done
[[ "$(wc -l < "$WORK/bundle")" -eq 16 ]] || fail "unexpected bundle entries: $(cat "$WORK/bundle")"
mkdir "$WORK/b" && (cd "$WORK/b" && unzip -q "$WORK/mock/bundle.zip")
gpg --homedir "$GNUPG" --verify "$WORK/b/$DIR/$NATIVE-0.1.0.jar.asc" "$WORK/b/$DIR/$NATIVE-0.1.0.jar" 2>&1 | grep -q 'Good signature' || fail "bundle signature"

echo "== --manual-release, then a FAILED deployment"
: > "$WORK/mock/requests.jsonl"
SONATYPE_USERNAME=user SONATYPE_PASSWORD=token "$SCALINO" publish . --manual-release -q > /dev/null 2>&1 || true
grep -q '"publishingType": \["USER_MANAGED"\]' "$WORK/mock/requests.jsonl" || fail "--manual-release is not USER_MANAGED"
kill "$MOCK_PID"; wait "$MOCK_PID" 2>/dev/null || true
rm -f "$WORK/port"
MOCK_FAIL=1 python3 "$ROOT/tests/publish/mock_central.py" "$WORK/mock" > "$WORK/port" &
MOCK_PID=$!
for _ in $(seq 50); do [[ -s "$WORK/port" ]] && break; sleep 0.1; done
export SCALINO_CENTRAL_URL="http://127.0.0.1:$(cat "$WORK/port")"
if SONATYPE_USERNAME=user SONATYPE_PASSWORD=token "$SCALINO" publish . > "$WORK/out" 2>&1; then fail "a FAILED deployment exited 0"; fi
grep -q "Missing signature for file" "$WORK/out" || fail "the portal's errors are not shown: $(cat "$WORK/out")"

echo "== -SNAPSHOT goes to the snapshots repository by PUT"
: > "$WORK/mock/requests.jsonl"
SONATYPE_USERNAME=user SONATYPE_PASSWORD=token "$SCALINO" publish . --project-version 0.3.0-SNAPSHOT -q > "$WORK/out" 2>&1 || { cat "$WORK/out" >&2; fail "snapshot publish"; }
S="$WORK/mock/put/repository/maven-snapshots/$(echo "$ORG" | tr . /)/$NATIVE"
for f in 0.3.0-SNAPSHOT/$NATIVE-0.3.0-SNAPSHOT.jar 0.3.0-SNAPSHOT/$NATIVE-0.3.0-SNAPSHOT.jar.sha1 0.3.0-SNAPSHOT/$NATIVE-0.3.0-SNAPSHOT.pom.asc maven-metadata.xml; do
  [[ -f "$S/$f" ]] || fail "snapshot repo lacks $f"
done
grep -q '"method": "PUT".*"auth": "Basic dXNlcjp0b2tlbg=="' "$WORK/mock/requests.jsonl" || fail "snapshot PUT without basic auth"

echo "== Central needs the POM metadata"
mkdir -p "$WORK/bare"
printf '//> using publish.organization %s\n//> using publish.version 1.0.0\nobject A\n' "$ORG" > "$WORK/bare/A.scala"
cd "$WORK/bare"
if "$SCALINO" publish . --dummy > "$WORK/out" 2>&1; then fail "publish without metadata succeeded"; fi
grep -q "Maven Central requires publish.url, publish.license, publish.versionControl, publish.developer, publish.description" "$WORK/out" || fail "wrong message: $(cat "$WORK/out")"

echo "OK"
