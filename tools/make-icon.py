#!/usr/bin/env python3
"""Generate the launcher icons from a mark drawn here, not from anyone's logo.

Origin: written for this repository.

The mark is a rounded phone outline holding a terminal prompt (`>` and `_`). That
is what this app is: a native client for a command-driven harness. It is
deliberately not the harness vendor's logo -- shipping someone's brand mark in your
own app invites confusion about who made it -- so it is composed from primitives
here and the project's MIT licence covers it like the rest of the code.

Shapes are drawn as filled polygons rather than strokes, because the shapes are the
same whether they end up in a PNG or in a preview: `stroke` in Pillow has no cap or
join control, which shows at 48px.

Outputs, per density:
  legacy square icon      ic_launcher.png
  adaptive foreground     ic_launcher_foreground.png   (mark inside the safe zone)
  adaptive monochrome     ic_launcher_monochrome.png   (white, for themed icons)
  adaptive background     ic_launcher_background.png

Run with `--preview` to print the legacy icon as ASCII, which is how the shapes were
checked without an image viewer.
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"

BACKGROUND = (0xFF, 0xFF, 0xFF, 0xFF)
MARK = (0x11, 0x13, 0x18, 0xFF)

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}
LEGACY_DP = 48
# An adaptive icon is a 108dp canvas, but only the middle 72dp is guaranteed
# visible; the mark is drawn at 62dp so no launcher mask can clip it.
ADAPTIVE_DP = 108
MARK_DP = 62

SUPERSAMPLE = 8

# The mark is defined on a 100x100 grid and scaled to whatever size is asked for,
# so one set of numbers drives every density.
GRID = 100.0
PHONE = (27.0, 10.0, 73.0, 90.0)   # left, top, right, bottom
PHONE_RADIUS = 12.0
PHONE_STROKE = 6.5
NOTCH = (44.0, 16.5, 56.0, 19.5)
PROMPT_STROKE = 7.5
# The prompt is the app's silhouette: a terminal chevron, bold enough to read at
# 48px where a thinner stroke turns to grey.
CHEVRON = ((32.0, 38.5), (47.0, 50.0), (32.0, 61.5))   # apex, tip, base
UNDERSCORE = (55.0, 58.0, 66.5, 64.5)                  # left, top, right, bottom


def rounded_rect_outline(box, radius, width, steps: int = 24) -> list[tuple[float, float]]:
    """A rounded-rectangle ring as one closed polygon (outer edge, inner edge)."""
    left, top, right, bottom = box
    inner = (left + width, top + width, right - width, bottom - width)
    inner_radius = max(radius - width, 0.0)

    def corners(b, r):
        points = []
        for cx, cy, start in (
            (b[2] - r, b[1] + r, -90.0),
            (b[2] - r, b[3] - r, 0.0),
            (b[0] + r, b[3] - r, 90.0),
            (b[0] + r, b[1] + r, 180.0),
        ):
            for step in range(steps + 1):
                angle = (start + 90.0 * step / steps) * 3.141592653589793 / 180.0
                import math

                points.append((cx + r * math.cos(angle), cy + r * math.sin(angle)))
        return points

    return corners(box, radius) + corners(inner, inner_radius)[::-1]


def chevron_outline(apex, tip, base, width: float) -> list[tuple[float, float]]:
    """A `>` as a closed seven-point outline: outer edge down, inner edge back."""
    import math

    def offset(a, b, amount):
        dx, dy = b[0] - a[0], b[1] - a[1]
        length = math.hypot(dx, dy) or 1.0
        # left normal
        return (a[0] - dy / length * amount, a[1] + dx / length * amount)

    half = width / 2.0
    return [
        offset(apex, tip, half),
        offset(tip, base, half),
        offset(base, tip, half),
        offset(tip, apex, half),
        offset(tip, apex, -half),
        offset(base, tip, -half),
        offset(tip, base, -half),
        offset(apex, tip, -half),
    ]


def scale(points, size: float):
    factor = size / GRID
    return [(x * factor, y * factor) for x, y in points]


def render_mark(size: int, colour=MARK) -> Image.Image:
    """The mark alone on transparent, at `size` pixels square."""
    big = size * SUPERSAMPLE
    layer = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    draw.polygon(scale(rounded_rect_outline(PHONE, PHONE_RADIUS, PHONE_STROKE), big), fill=colour)
    draw.polygon(scale([(NOTCH[0], NOTCH[1]), (NOTCH[2], NOTCH[1]), (NOTCH[2], NOTCH[3]), (NOTCH[0], NOTCH[3])], big), fill=colour)
    draw.polygon(scale(chevron_outline(*CHEVRON, PROMPT_STROKE), big), fill=colour)
    draw.polygon(
        scale([(UNDERSCORE[0], UNDERSCORE[1]), (UNDERSCORE[2], UNDERSCORE[1]),
               (UNDERSCORE[2], UNDERSCORE[3]), (UNDERSCORE[0], UNDERSCORE[3])], big),
        fill=colour,
    )
    return layer.resize((size, size), Image.LANCZOS)


def legacy_icon(px: int) -> Image.Image:
    """The legacy icon: the mark inset on the background."""
    icon = Image.new("RGBA", (px, px), BACKGROUND)
    inset = round(px * 0.16)
    icon.alpha_composite(render_mark(px - 2 * inset), (inset, inset))
    return icon


def adaptive_layers(px: int) -> tuple[Image.Image, Image.Image]:
    """Foreground (mark in the safe zone) and background for an adaptive icon."""
    foreground = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    mark_px = round(px * MARK_DP / ADAPTIVE_DP)
    foreground.alpha_composite(render_mark(mark_px), ((px - mark_px) // 2, (px - mark_px) // 2))
    background = Image.new("RGBA", (px, px), BACKGROUND)
    return foreground, background


def ascii_preview(path: Path, cols: int = 56) -> str:
    """The legacy icon as characters, dark meaning ink."""
    image = Image.open(path).convert("L")
    rows = max(1, round(cols * image.height / image.width / 2))
    small = image.resize((cols, rows), Image.LANCZOS)
    ramp = " .:-=+*#%@"
    lines = []
    for y in range(rows):
        lines.append("".join(ramp[min(9, int((255 - small.getpixel((x, y))) / 255 * 9))] for x in range(cols)))
    return "\n".join(lines)


def main() -> None:
    if "--preview" in sys.argv:
        # Both shapes a launcher may draw: the square legacy icon, and the adaptive
        # foreground seen through a circular mask (the tightest common one).
        tmp = Path("/tmp/dsh-native-icon-preview.png")
        legacy_icon(160).save(tmp)
        print("legacy icon (square):")
        print(ascii_preview(tmp))
        foreground, background = adaptive_layers(288)
        icon = Image.alpha_composite(background, foreground)
        mask = Image.new("L", icon.size, 0)
        ImageDraw.Draw(mask).ellipse((0, 0, icon.width - 1, icon.height - 1), fill=255)
        icon.putalpha(mask)
        icon.save(tmp)
        print()
        print("adaptive icon through a circular mask:")
        print(ascii_preview(tmp))
        return

    written = []
    for density, factor in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)

        legacy = round(LEGACY_DP * factor)
        legacy_icon(legacy).save(folder / "ic_launcher.png")

        adaptive = round(ADAPTIVE_DP * factor)
        foreground, background = adaptive_layers(adaptive)
        foreground.save(folder / "ic_launcher_foreground.png")
        background.save(folder / "ic_launcher_background.png")
        # The monochrome layer is the same shape in white: a themed launcher takes
        # its alpha channel and tints it itself.
        render_mark(round(adaptive * MARK_DP / ADAPTIVE_DP), colour=(0xFF, 0xFF, 0xFF, 0xFF)).save(
            folder / "ic_launcher_monochrome_tmp.png"
        )
        tmp = folder / "ic_launcher_monochrome_tmp.png"
        layer = Image.new("RGBA", (adaptive, adaptive), (0, 0, 0, 0))
        layer.alpha_composite(Image.open(tmp), ((adaptive - Image.open(tmp).width) // 2,) * 2)
        layer.save(folder / "ic_launcher_monochrome.png")
        tmp.unlink()
        written.append(f"{folder.name}: {legacy}px, {adaptive}px")

    print("\n".join(written))


if __name__ == "__main__":
    sys.exit(main())
