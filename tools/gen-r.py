#!/usr/bin/env python3
"""Generate library R.java sources from an aapt2 symbol dump.

The aars in this dependency graph ship no R.class in their classes.jar (verified
by inspecting them), because R classes are a build-time product. The Gradle-free
build therefore has to create one per library package, or compiled library code
throws NoClassDefFoundError the first time it touches its own R — which is how
the app died inside Compose's setContent on
androidx.customview.poolingcontainer.R$id.

`--extra-packages` cannot do this in one pass: it accepts a single package name
(handing it a comma-separated list produces a directory literally named
"a,b/R.java"). So the symbols are read from aapt2's own text dump once and the
sources are written here instead, which also guarantees every package sees the
same resource ids.

Usage: gen-r.py <R.txt> <output-java-dir> <package> [<package> ...]
"""
import os
import sys

HEADER = """/* Generated from the aapt2 symbol dump. Do not edit. */
package {pkg};

public final class R {{
"""

# Plain string, not an f-string: braces are literal here.
FOOTER = """    private R() {}
}
"""


def read_symbols(path):
    """R.txt lines look like `int id foo 0x7f080001`."""
    by_type = {}
    for line in open(path):
        parts = line.split()
        if len(parts) != 4 or parts[0] != "int":
            continue
        _kind, res_type, name, value = parts
        by_type.setdefault(res_type, []).append((name, value))
    return by_type


def write_package(by_type, out_dir, pkg):
    pkg_dir = os.path.join(out_dir, *pkg.split("."))
    os.makedirs(pkg_dir, exist_ok=True)
    path = os.path.join(pkg_dir, "R.java")
    with open(path, "w") as handle:
        handle.write(HEADER.format(pkg=pkg))
        for res_type in sorted(by_type):
            handle.write(f"    public static final class {res_type} {{\n")
            for name, value in sorted(by_type[res_type]):
                handle.write(f"        public static final int {name} = {value};\n")
            handle.write("    }\n")
        handle.write(FOOTER)
    return path


def main():
    symbols_path, out_dir = sys.argv[1], sys.argv[2]
    packages = sys.argv[3:]
    by_type = read_symbols(symbols_path)
    written = 0
    for pkg in packages:
        if not pkg:
            continue
        write_package(by_type, out_dir, pkg)
        written += 1
    print(f"   generated R.java for {written} library packages "
          f"({sum(len(v) for v in by_type.values())} symbols each)")


if __name__ == "__main__":
    main()
