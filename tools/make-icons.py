#!/usr/bin/env python3
"""Generate the launcher icons from the harness's own favicon.

Origin: written for this repository.

There is no SVG rasteriser in this VM (no rsvg-convert, inkscape, ImageMagick,
or cairosvg) and the Gradle-free build must not depend on one, so this script
flattens the SVG's curves itself and supersamples with Pillow. The icon is the
product's own mark, so re-drawing it by hand was not an option.

Why this is small enough to trust: the source is one `<path>` with four subpaths
and only `M`, `C`, and `Z` commands, which is checked before anything is drawn.
A path outside that set fails loudly instead of producing a subtly wrong icon.

Outputs, per density:
  legacy square icon      ic_launcher.png
  adaptive foreground     ic_launcher_foreground.png   (mark inside the safe zone)
  adaptive background     ic_launcher_background.png   (flat brand colour)

The mark is drawn black on white. The source SVG is black in light mode and white
in dark mode via a media query; an icon needs one form, and the mark's interior is
cut out rather than filled, so it reads as an outline.
"""

from __future__ import annotations

import os
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent


def find_source() -> Path:
    """Locate the harness's favicon in this machine's DSH installation.

    Not a fixed path: the install location differs per machine (a global npm tree,
    `~/.local`, a profile directory), and a public checkout has to work on someone
    else's. `DSH_FAVICON` overrides the search.
    """
    override = os.environ.get("DSH_FAVICON")
    if override:
        return Path(override)
    pattern = "**/node_modules/@deepseek-ai/dsh-web-frontend/dist/favicon.svg"
    roots = [
        Path.home() / ".local/node-22.23.2/lib",
        Path.home() / ".local/lib",
        Path.home() / ".dsh/profiles",
        Path("/usr/lib"),
        Path("/usr/local/lib"),
    ]
    for root in roots:
        for hit in sorted(root.glob(pattern)):
            return hit
    raise SystemExit(
        "favicon.svg not found; set DSH_FAVICON to the harness's "
        "@deepseek-ai/dsh-web-frontend/dist/favicon.svg"
    )


SOURCE = find_source()
RES = ROOT / "app/src/main/res"

# Black on white. The mark is a thin outline with holes (see `render_mark`), so it
# needs a background it contrasts with rather than a same-colour fill.
BACKGROUND = (0xFF, 0xFF, 0xFF, 0xFF)
MARK = (0x00, 0x00, 0x00, 0xFF)

# Android densities: mdpi is 48dp at 1x.
DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}
LEGACY_DP = 48
# An adaptive icon is a 108dp canvas, but only the middle 72dp is guaranteed
# visible; the mark is drawn at 66dp so no launcher mask can clip it.
ADAPTIVE_DP = 108
MARK_DP = 66

SUPERSAMPLE = 8

NUMBER = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
COMMAND = re.compile(r"([MmCcZzLlHhVvSsQqTtAa])")


def parse_path(data: str) -> list[list[tuple[float, float]]]:
    """Flatten an `M`/`C`/`Z` path into closed polygons."""
    if re.search(r"[LlHhVvSsQqTtAa]", data):
        raise SystemExit(
            "the source path uses a command this rasteriser does not implement "
            "(only M, C and Z are supported); update tools/make-icons.py"
        )

    tokens = COMMAND.split(data.replace(",", " "))
    polygons: list[list[tuple[float, float]]] = []
    current: list[tuple[float, float]] = []
    cursor = (0.0, 0.0)
    start = (0.0, 0.0)
    command = None

    for token in tokens:
        token = token.strip()
        if not token:
            continue
        if COMMAND.fullmatch(token):
            command = token
            continue
        values = [float(n) for n in NUMBER.findall(token)]
        if command in ("M", "m"):
            # `M` opens a subpath; a following coordinate pair is an implicit
            # line, but this source only uses M as a start.
            if len(values) % 2:
                raise SystemExit(f"odd coordinate count in move: {token!r}")
            for index in range(0, len(values), 2):
                point = (values[index], values[index + 1])
                if command == "m":
                    point = (cursor[0] + point[0], cursor[1] + point[1])
                if current:
                    polygons.append(current)
                current = [point]
                cursor = point
                start = point
        elif command in ("C", "c"):
            if len(values) % 6:
                raise SystemExit(f"odd coordinate count in curve: {token!r}")
            for index in range(0, len(values), 6):
                points = [
                    (values[index + n], values[index + n + 1]) for n in range(0, 6, 2)
                ]
                if command == "c":
                    points = [(cursor[0] + x, cursor[1] + y) for x, y in points]
                current.extend(cubic(cursor, points[0], points[1], points[2]))
                cursor = points[2]
        elif command in ("Z", "z"):
            if current:
                current.append(start)
                polygons.append(current)
                current = []
            cursor = start
        else:
            raise SystemExit(f"unexpected command {command!r}")
    if current:
        polygons.append(current)
    return polygons


def cubic(p0, p1, p2, p3, steps: int = 24) -> list[tuple[float, float]]:
    """Sample one cubic Bezier, exclusive of its start point."""
    out = []
    for step in range(1, steps + 1):
        t = step / steps
        u = 1 - t
        x = u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0]
        y = u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1]
        out.append((x, y))
    return out


def render_mark(size: int, color: tuple[int, int, int, int] = MARK) -> Image.Image:
    """The mark alone, white on transparent, fitted into a square of `size`."""
    svg = SOURCE.read_text()
    view = re.search(r'viewBox="([^"]+)"', svg)
    if not view:
        raise SystemExit("the source SVG has no viewBox")
    min_x, min_y, width, height = (float(n) for n in view.group(1).split())
    path = re.search(r'<path\b[^>]*\bd="([^"]+)"', svg, re.S)
    if not path:
        raise SystemExit("the source SVG has no path")

    big = size * SUPERSAMPLE
    scale = big / max(width, height)
    polygons = [
        [((x - min_x) * scale, (y - min_y) * scale) for x, y in polygon]
        for polygon in parse_path(path.group(1))
    ]

    # Even-odd fill, which is what the source needs: the mark is an outline with
    # three subpaths that lie *inside* it (an eye and two fin notches). Filling by
    # union -- SVG's `nonzero` default, and the first thing tried here -- paints
    # the whole silhouette solid and loses every internal contour.
    #
    # Parity is counted properly rather than approximated by XORing antialiased
    # pieces: XOR of two pixels that are each half covered gives a covered pixel,
    # so the holes came out stippled instead of clean.
    layer = Image.new("L", (big, big), 0)
    for polygon in polygons:
        piece = Image.new("L", (big, big), 0)
        ImageDraw.Draw(piece).polygon(polygon, fill=1)
        layer = Image.frombytes(
            "L", (big, big), bytes(
                ((a + b) & 1) * 255 for a, b in zip(layer.tobytes(), piece.tobytes())
            )
        )
    mark = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    mark.paste(color, (0, 0), layer)
    return mark.resize((size, size), Image.LANCZOS)


def square_icon(px: int) -> Image.Image:
    """The legacy icon: brand background, mark inset by a fifth."""
    icon = Image.new("RGBA", (px, px), BACKGROUND)
    inset = round(px * 0.2)
    icon.alpha_composite(render_mark(px - 2 * inset), (inset, inset))
    return icon


def adaptive_foreground(px: int, color: tuple[int, int, int, int] = MARK) -> Image.Image:
    """The adaptive foreground: the mark inside the guaranteed-visible zone."""
    layer = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    mark_px = round(px * MARK_DP / ADAPTIVE_DP)
    layer.alpha_composite(render_mark(mark_px, color), ((px - mark_px) // 2, (px - mark_px) // 2))
    return layer


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"icon source not found: {SOURCE}")

    written = []
    for density, factor in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)

        legacy = round(LEGACY_DP * factor)
        square_icon(legacy).save(folder / "ic_launcher.png")

        adaptive = round(ADAPTIVE_DP * factor)
        adaptive_foreground(adaptive).save(folder / "ic_launcher_foreground.png")
        # The monochrome layer is the same shape in white: on API 33+ a themed
        # launcher takes the alpha channel and tints it itself.
        adaptive_foreground(adaptive, color=(0xFF, 0xFF, 0xFF, 0xFF)).save(
            folder / "ic_launcher_monochrome.png"
        )
        Image.new("RGBA", (adaptive, adaptive), BACKGROUND).save(
            folder / "ic_launcher_background.png"
        )
        written.append(f"{folder.name}: {legacy}px, {adaptive}px")

    print("\n".join(written))
    print(f"\nsource: {SOURCE}")


if __name__ == "__main__":
    sys.exit(main())
