#!/usr/bin/env python3
"""Generate the launcher icons: the letters DSH, set as a monogram.

Origin: written for this repository.

Three letters are the whole mark. An earlier attempt drew a phone outline with a
terminal prompt inside; at 48px it read as a smudge, because it packed three ideas
(a frame, a notch, a glyph) into a space that fits one. A monogram survives the size
because there is nothing to resolve: the letters are their own shape.

The face is DejaVu Sans Bold, the boldest sans available here, and the letters are
capped to the canvas width rather than a nominal point size so the monogram fills
its box at every density. Deliberately not the harness vendor's logo: shipping
someone else's brand mark in your own app invites confusion about who made it.

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

from PIL import Image, ImageDraw, ImageFont

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
# How much of the canvas width the letters span. Three letters have a ~3:1 aspect,
# so a horizontal monogram always leaves vertical room; 0.86 of the width puts the
# letters as large as they can be while still reading as a mark rather than as a
# word running edge to edge.
TEXT_SPAN = 0.86
# An adaptive icon is a 108dp canvas, but only the middle 72dp is guaranteed
# visible; the mark is drawn at 62dp so no launcher mask can clip it.
ADAPTIVE_DP = 108
# The guaranteed-visible area of an adaptive icon is the middle 72dp. The letters
# span TEXT_SPAN of the mark, so a mark of 72/TEXT_SPAN dp makes the *text* stop
# exactly at that boundary -- for a wide monogram, sizing the mark for its height
# would let the letters run past it.
SAFE_DP = 72
MARK_DP = SAFE_DP / TEXT_SPAN

SUPERSAMPLE = 8

FONT_CANDIDATES = (
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/system/fonts/Roboto-Bold.ttf",
)


def load_font(size: int):
    """The boldest available sans, at `size`."""
    for path in FONT_CANDIDATES:
        if Path(path).exists():
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                continue
    raise SystemExit(
        "no usable font found; add a path to FONT_CANDIDATES "
        f"(tried: {', '.join(FONT_CANDIDATES)})"
    )


def render_mark(size: int, colour=MARK) -> Image.Image:
    """The monogram alone on transparent, filling `TEXT_SPAN` of `size` pixels."""
    import math

    big = size * SUPERSAMPLE
    # Ask for a large point size and then scale the drawn result to the target
    # width: a nominal point size means different coverage in different faces, which
    # is exactly what the icon must not depend on.
    probe = load_font(big)
    left, top, right, bottom = probe.getbbox("DSH")
    drawn_w, drawn_h = right - left, bottom - top
    target_w = big * TEXT_SPAN
    scale_factor = target_w / drawn_w
    font = load_font(max(1, round(big * scale_factor)))

    a, b, c, d = font.getbbox("DSH")
    text_w, text_h = c - a, d - b
    layer = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    ImageDraw.Draw(layer).text(
        ((big - text_w) / 2 - a, (big - text_h) / 2 - b), "DSH", font=font, fill=colour,
    )
    return layer.resize((size, size), Image.LANCZOS)


def legacy_icon(px: int) -> Image.Image:
    """The legacy icon: the mark inset on the background."""
    icon = Image.new("RGBA", (px, px), BACKGROUND)
    # The legacy icon has no mask, so the margin only has to look deliberate: the
    # letters span TEXT_SPAN of the canvas whether or not a mask is applied.
    inset = round(px * (1 - TEXT_SPAN) / 2)
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


def masked_icon(px: int, shape: str) -> Image.Image:
    """The composed adaptive icon as a launcher would draw it."""
    foreground, background = adaptive_layers(px)
    icon = Image.alpha_composite(background, foreground)
    # A launcher insets the 108dp canvas before applying its mask, so the visible
    # disc is smaller than the layer; matching that is what makes this a faithful
    # preview rather than a flattering one.
    inset = round(px * 0.0833)
    mask = Image.new("L", (px, px), 0)
    draw = ImageDraw.Draw(mask)
    box = (inset, inset, px - inset - 1, px - inset - 1)
    if shape == "circle":
        draw.ellipse(box, fill=255)
    elif shape == "squircle":
        draw.rounded_rectangle(box, radius=round(px * 0.22), fill=255)
    icon.putalpha(mask)
    return icon


def export_previews() -> None:
    """Write `docs/images/icon-*.png`: reproducible previews for the README.

    Generated from the same code that draws the real icons, so the documented mark
    cannot drift from the shipped one.
    """
    out = ROOT / "docs/images"
    out.mkdir(parents=True, exist_ok=True)
    for shape in ("circle", "squircle"):
        masked_icon(384, shape).save(out / f"icon-{shape}.png")
    legacy_icon(384).save(out / "icon-legacy.png")
    print("\n".join(f"docs/images/icon-{name}.png" for name in ("circle", "squircle", "legacy")))


def main() -> None:
    if "--export" in sys.argv:
        export_previews()
        return
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
        mark_px = round(adaptive * MARK_DP / ADAPTIVE_DP)
        layer = Image.new("RGBA", (adaptive, adaptive), (0, 0, 0, 0))
        layer.alpha_composite(
            render_mark(mark_px, colour=(0xFF, 0xFF, 0xFF, 0xFF)),
            ((adaptive - mark_px) // 2, (adaptive - mark_px) // 2),
        )
        layer.save(folder / "ic_launcher_monochrome.png")
        written.append(f"{folder.name}: {legacy}px, {adaptive}px")

    print("\n".join(written))


if __name__ == "__main__":
    sys.exit(main())
