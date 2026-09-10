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

The mark is drawn white on the DeepSeek brand blue. The source SVG is black in
light mode and white in dark mode via a media query; an icon needs one form, and
white-on-blue is the one that reads on both light and dark launchers.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SOURCE = Path(
    "/home/cxxiao/.local/node-22.23.2/lib/node_modules/@deepseek-ai/dsh/node_modules/"
    "@deepseek-ai/dsh-web-frontend/dist/favicon.svg"
)
RES = ROOT / "app/src/main/res"

# Brand blue from the DeepSeek mark; the app's own palette is a dark neutral.
BACKGROUND = (0x4D, 0x6B, 0xFE, 0xFF)
MARK = (0xFF, 0xFF, 0xFF, 0xFF)

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


def render_mark(size: int) -> Image.Image:
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

    # Each subpath is rasterised on its own and the results are unioned, which is
    # SVG's default `nonzero` fill: the mark's features are separate closed
    # shapes, not holes. Feeding every subpath to one `polygon()` call instead
    # paints a bridge between them and loses the tail.
    layer = Image.new("L", (big, big), 0)
    for polygon in polygons:
        piece = Image.new("L", (big, big), 0)
        ImageDraw.Draw(piece).polygon(polygon, fill=255)
        layer = Image.frombytes(
            "L", (big, big), bytes(max(a, b) for a, b in zip(layer.tobytes(), piece.tobytes()))
        )
    mark = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    mark.putalpha(layer)
    mark.paste(MARK, (0, 0), layer)
    return mark.resize((size, size), Image.LANCZOS)


def square_icon(px: int) -> Image.Image:
    """The legacy icon: brand background, mark inset by a fifth."""
    icon = Image.new("RGBA", (px, px), BACKGROUND)
    inset = round(px * 0.2)
    icon.alpha_composite(render_mark(px - 2 * inset), (inset, inset))
    return icon


def adaptive_foreground(px: int) -> Image.Image:
    """The adaptive foreground: the mark inside the guaranteed-visible zone."""
    layer = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    mark_px = round(px * MARK_DP / ADAPTIVE_DP)
    layer.alpha_composite(render_mark(mark_px), ((px - mark_px) // 2, (px - mark_px) // 2))
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
        Image.new("RGBA", (adaptive, adaptive), BACKGROUND).save(
            folder / "ic_launcher_background.png"
        )
        written.append(f"{folder.name}: {legacy}px, {adaptive}px")

    print("\n".join(written))
    print(f"\nsource: {SOURCE}")


if __name__ == "__main__":
    sys.exit(main())
