#!/usr/bin/env python3
"""Merge resource trees for a Gradle-free aapt2 build.

aapt2 derives a resource's *name* from the file name, so renaming staged files to
avoid collisions renames the resources themselves — `lib21__notification_action_
background.xml` becomes a drawable called `lib21__notification_action_background`
and nothing that references `@drawable/notification_action_background` resolves.
An earlier version of this script did exactly that.

So nothing is renamed. Collisions are handled by kind instead:

* `values*/**.xml` files are merged element-wise: every `<resources>` child of
  every library is appended into one document per configuration. Several
  libraries ship `values/values.xml`, and only merging preserves all of their
  entries.
* Every other type (drawable, layout, mipmap, ...) is copied verbatim. Two
  libraries declaring the same resource name is a genuine conflict upstream, not
  something this script should paper over.

Usage: merge-res.py <staging-dir> <tree> [<tree> ...]
"""
import os
import shutil
import sys
from xml.etree import ElementTree as ET


def is_values_dir(name):
    return name == "values" or name.startswith("values-")


def merge_values(target_dir, sources):
    """Append every <resources> child from `sources` into one document."""
    os.makedirs(target_dir, exist_ok=True)
    merged = ET.Element("resources")
    for path in sources:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            print(f"   skipping unparsable {path}: {error}", file=sys.stderr)
            continue
        for child in list(root):
            merged.append(child)
    # aapt2 accepts the merged document under any file name in the type dir.
    ET.ElementTree(merged).write(
        os.path.join(target_dir, "merged.xml"), encoding="utf-8", xml_declaration=True
    )
    return len(list(merged))


def main():
    staging = sys.argv[1]
    trees = sys.argv[2:]
    shutil.rmtree(staging, ignore_errors=True)
    os.makedirs(staging, exist_ok=True)

    # Group values files by their configuration directory (values, values-zh, ...).
    values_by_config = {}
    copied = 0
    for tree in trees:
        if not os.path.isdir(tree):
            continue
        for type_dir in sorted(os.listdir(tree)):
            type_path = os.path.join(tree, type_dir)
            if not os.path.isdir(type_path):
                continue
            if is_values_dir(type_dir):
                for root, _dirs, files in os.walk(type_path):
                    rel = os.path.relpath(root, type_path)
                    config = type_dir if rel == "." else os.path.join(type_dir, rel)
                    for name in files:
                        if name.endswith(".xml"):
                            values_by_config.setdefault(config, []).append(os.path.join(root, name))
                continue
            # Non-values: copy the tree as-is, preserving qualifier directories.
            for root, _dirs, files in os.walk(type_path):
                rel = os.path.relpath(root, tree)
                dest_dir = os.path.join(staging, rel)
                os.makedirs(dest_dir, exist_ok=True)
                for name in files:
                    shutil.copyfile(os.path.join(root, name), os.path.join(dest_dir, name))
                    copied += 1

    entries = 0
    for config, sources in sorted(values_by_config.items()):
        entries += merge_values(os.path.join(staging, config), sources)
    print(f"   copied {copied} files, merged {entries} value entries "
          f"across {len(values_by_config)} configurations")


if __name__ == "__main__":
    main()
