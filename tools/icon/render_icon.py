"""Render assets/icon.png (512x512) from the same shapes as ic_light.xml.

The app icon is an original design (not iGPSPORT's): a stylised bike headlight — a rounded lamp
housing with an amber lens and 3 beam rays — on a dark rounded-square background. The coordinates
below are the ic_light.xml vector's 24x24 viewport, scaled up to a 512x512 canvas.

Usage (from the repo root, with Pillow installed into tools/probe/.venv):
    tools/probe/.venv/Scripts/python.exe tools/icon/render_icon.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 512
SCALE = SIZE / 24  # ic_light.xml's viewport is 24x24

BACKGROUND = (0x1E, 0x2A, 0x33, 255)
HOUSING = (0xFF, 0xFF, 0xFF, 255)
AMBER = (0xFF, 0xC1, 0x07, 255)

# Same coordinates as the vector drawable's pathData, in the 24x24 viewport.
BG_CORNER_RADIUS = 5
HOUSING_RECT = (5, 8, 13, 16)  # x0, y0, x1, y1
HOUSING_CORNER_RADIUS = 1.6
LENS_CENTER = (9, 12)
LENS_RADIUS = 2
RAYS = [
    ((13.6, 9.4), (19, 6.6)),
    ((14.2, 12), (20.4, 12)),
    ((13.6, 14.6), (19, 17.4)),
]
RAY_WIDTH = 1.6


def px(value: float) -> float:
    return value * SCALE


def scaled_box(box: tuple[float, float, float, float]) -> tuple[float, float, float, float]:
    x0, y0, x1, y1 = box
    return (px(x0), px(y0), px(x1), px(y1))


def draw_round_capped_line(draw: ImageDraw.ImageDraw, p0, p1, width: float, fill) -> None:
    """A stroked line with round caps: PIL's line() has flat caps, so add a circle at each end."""
    x0, y0 = px(p0[0]), px(p0[1])
    x1, y1 = px(p1[0]), px(p1[1])
    w = px(width)
    draw.line([(x0, y0), (x1, y1)], fill=fill, width=round(w))
    r = w / 2
    draw.ellipse([x0 - r, y0 - r, x0 + r, y0 + r], fill=fill)
    draw.ellipse([x1 - r, y1 - r, x1 + r, y1 + r], fill=fill)


def render() -> Image.Image:
    image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    draw.rounded_rectangle(
        [0, 0, SIZE - 1, SIZE - 1], radius=px(BG_CORNER_RADIUS), fill=BACKGROUND,
    )
    draw.rounded_rectangle(
        scaled_box(HOUSING_RECT), radius=px(HOUSING_CORNER_RADIUS), fill=HOUSING,
    )
    cx, cy = px(LENS_CENTER[0]), px(LENS_CENTER[1])
    r = px(LENS_RADIUS)
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=AMBER)
    for p0, p1 in RAYS:
        draw_round_capped_line(draw, p0, p1, RAY_WIDTH, AMBER)

    return image


def main() -> None:
    out_path = Path(__file__).resolve().parent.parent.parent / "assets" / "icon.png"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    render().save(out_path)
    print(f"Wrote {out_path} ({SIZE}x{SIZE})")


if __name__ == "__main__":
    main()
