#!/usr/bin/env python3
"""Resolve and download Android/JVM dependencies without Gradle.

Walks each POM's compile-scope dependencies recursively, downloads the artifact
(aar or jar), and for an aar extracts classes.jar. Android packaging needs the
real transitive set, and hand-maintaining that list is how a build silently
breaks on a version bump, so this reads the POMs instead of guessing.
"""
import os
import re
import shutil
import urllib.request
import zipfile
from xml.etree import ElementTree as ET

MIRRORS = [
    "https://maven.aliyun.com/repository/central",
    "https://maven.aliyun.com/repository/google",
    "https://repo1.maven.org/maven2",
]
OUT = os.path.expanduser("~/.local/m2")
CACHE = os.path.expanduser("~/.local/m2-cache")
NS = "{http://maven.apache.org/POM/4.0.0}"
# Optional or packaging-only deps that never belong on the compile classpath.
SKIP_GROUPS = {"com.google.guava"}
# Artifacts whose classes another selected artifact already contains. Gradle's
# variant-aware resolution picks one of the pair; this walker would dex both and
# d8 rejects duplicate classes. Each entry is justified by an observed clash:
#   collection-ktx vs collection-jvm  -> androidx.collection.ArraySetKt
#   core-ktx is likewise folded into core, and the -jvm artifact is the KMP
#   variant that duplicates the Android one.
SKIP_ARTIFACTS = {
    "androidx.collection:collection-ktx",
    "androidx.core:core-ktx",
    "androidx.activity:activity-ktx",
    "androidx.lifecycle:lifecycle-runtime-ktx",
    "androidx.lifecycle:lifecycle-viewmodel-ktx",
    "androidx.compose.runtime:runtime-livedata",
}


def fetch(url):
    name = os.path.join(CACHE, re.sub(r"[^A-Za-z0-9._-]", "_", url))
    if os.path.exists(name):
        return name
    os.makedirs(CACHE, exist_ok=True)
    with urllib.request.urlopen(url, timeout=60) as r, open(name, "wb") as f:
        shutil.copyfileobj(r, f)
    return name


def try_fetch(rel):
    last = None
    for mirror in MIRRORS:
        try:
            return fetch(f"{mirror}/{rel}")
        except Exception as error:  # noqa: BLE001 - report the last mirror failure
            last = error
    raise SystemExit(f"cannot fetch {rel}: {last}")


def text(node, tag):
    el = node.find(NS + tag)
    return el.text.strip() if el is not None and el.text else None


def resolve(group, artifact, version, seen, out):
    key = f"{group}:{artifact}:{version}"
    if key in seen:
        return
    seen.add(key)
    base = f"{group.replace('.', '/')}/{artifact}/{version}"
    root = ET.parse(try_fetch(f"{base}/{artifact}-{version}.pom")).getroot()

    props = {}
    parent_props = {}
    parent = root.find(NS + "parent")
    if parent is not None:
        parent_props = {
            "project.version": text(parent, "version") or version,
            "project.groupId": text(parent, "groupId") or group,
        }
    properties = root.find(NS + "properties")
    if properties is not None:
        for child in properties:
            props[child.tag.replace(NS, "")] = (child.text or "").strip()
    versions = {**parent_props, **props, "project.version": version, "project.groupId": group}

    def substitute(value):
        if value is None:
            return None
        for name, replacement in versions.items():
            value = value.replace("${" + name + "}", replacement or "")
        if "${" in value:
            return None
        # Some POMs declare an exact version as the range "[1.9.3]"; Maven treats
        # that as a hard pin, but the brackets are not part of the path.
        value = value.strip()
        if len(value) > 2 and value[0] in "[(" and value[-1] in "])":
            value = value[1:-1].split(",")[0].strip()
        return value or None

    dependencies = root.find(NS + "dependencies")
    if dependencies is not None:
        for dep in dependencies.findall(NS + "dependency"):
            if text(dep, "scope") not in (None, "compile", "runtime"):
                continue
            if text(dep, "optional") == "true":
                continue
            dg, da, dv = text(dep, "groupId"), text(dep, "artifactId"), text(dep, "version")
            if not dg or not da or dg in SKIP_GROUPS or f"{dg}:{da}" in SKIP_ARTIFACTS:
                continue
            if dv is None:
                # A sibling module of the same parent inherits the parent version.
                if dg == parent_props.get("project.groupId") and parent_props.get("project.version"):
                    dv = parent_props["project.version"]
                else:
                    continue
            dv = substitute(dv)
            if dv:
                resolve(dg, da, dv, seen, out)

    packaging = text(root, "packaging") or "jar"
    ext = "aar" if packaging == "aar" else "jar"
    try:
        path = try_fetch(f"{base}/{artifact}-{version}.{ext}")
    except SystemExit:
        path = try_fetch(f"{base}/{artifact}-{version}.jar")
        ext = "jar"
    out.append((key, path, ext))


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    roots = []
    for line in open(os.path.join(here, "deps.txt")):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        group, artifact, version = line.split(":")
        roots.append((group, artifact, version))

    # The extracted-aar directory is rebuilt every run: it is keyed by resolved
    # coordinates, and leaving old versions behind hands d8 stale classes from a
    # version this run no longer selects.
    shutil.rmtree(os.path.join(OUT, "classes"), ignore_errors=True)

    seen, out = set(), []
    for group, artifact, version in roots:
        resolve(group, artifact, version, seen, out)
    print(f"resolved {len(out)} artifacts")

    # Version arbitration: the walker visits every declared version, so the same
    # artifact can appear at two versions (annotation at 1.0.0 and 1.1.0, for
    # instance). That is harmless for kotlinc, which takes the first match, but
    # d8 rejects the duplicate classes outright. Keep the highest version of each
    # group:artifact, which is what Maven's nearest-wins resolves to here.
    def version_key(value):
        parts = re.split(r"[.\-_]", value)
        return [int(p) if p.isdigit() else 0 for p in parts]

    best = {}
    for key, path, ext in out:
        group, artifact, version = key.split(":")
        current = best.get(f"{group}:{artifact}")
        if current is None or version_key(version) > version_key(current[0]):
            best[f"{group}:{artifact}"] = (version, path, ext)
    # A KMP module can resolve to both `foo` and `foo-jvm`. Dropping the -jvm
    # artifact blindly is wrong: for kotlinx-coroutines the Android artifact is a
    # thin wrapper whose implementation only exists in -jvm, and removing it made
    # every kotlinx.coroutines symbol unresolvable. Only drop -jvm when the
    # sibling is an Android aar, which does carry the classes.
    for key in list(best):
        if not key.endswith("-jvm"):
            continue
        sibling = best.get(key[: -len("-jvm")])
        if sibling is not None and sibling[2] == "aar":
            del best[key]
    # Final guard, applied to the resolved set rather than to each dependency
    # edge: some of these arrive through chains the per-edge filter does not see.
    # Each name is here because dexing failed on a duplicate class it caused.
    DROP_ARTIFACTS = {
        "collection-ktx",
        "core-ktx",
        "activity-ktx",
        "lifecycle-runtime-ktx",
        "lifecycle-viewmodel-ktx",
        "runtime-livedata",
    }
    dropped = [k for k in best if k.split(":")[1] in DROP_ARTIFACTS]
    for key in dropped:
        del best[key]
    if dropped:
        print("dropped duplicate-class artifacts: " + ", ".join(sorted(dropped)))

    deduped = [(f"{k}:{v[0]}", v[1], v[2]) for k, v in sorted(best.items())]
    print(f"after version arbitration: {len(deduped)} artifacts")

    classes = []
    for key, path, ext in deduped:
        if ext == "aar":
            target_dir = os.path.join(OUT, "classes", key.replace(":", "_"))
            os.makedirs(target_dir, exist_ok=True)
            with zipfile.ZipFile(path) as archive:
                if "classes.jar" in archive.namelist():
                    target = os.path.join(target_dir, "classes.jar")
                    with archive.open("classes.jar") as src, open(target, "wb") as dst:
                        shutil.copyfileobj(src, dst)
                    classes.append(target)
            # jni/ and res/ are deliberately ignored: this client ships no native
            # libraries, and aapt2 links resources from its own sources.
        else:
            classes.append(path)

    listing = os.path.join(OUT, "classpath.txt")
    with open(listing, "w") as handle:
        # A trailing newline is required: the build script turns newlines into
        # ':' separators, and without it the next entry is concatenated onto the
        # last path (which silently dropped android.jar from the classpath).
        handle.write("\n".join(classes) + "\n")
    print(f"classpath entries: {len(classes)} -> {listing}")


if __name__ == "__main__":
    main()
