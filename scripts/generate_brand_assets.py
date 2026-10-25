"""Export the supplied brand image for Android, project docs and the admin web app."""

from pathlib import Path
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
SOURCE = ROOT / "branding" / "logo-source.png"
source = Image.open(SOURCE).convert("RGBA")


def square(size: int) -> Image.Image:
    image = source.copy()
    image.thumbnail((size, size), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size), "white")
    canvas.alpha_composite(image, ((size - image.width) // 2, (size - image.height) // 2))
    return canvas


def save(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.suffix == ".webp":
        image.save(path, "WEBP", lossless=True, method=6)
    else:
        image.save(path, "PNG", optimize=True)


# One shared image for all in-app brand locations and the README.
save(square(512), RES / "drawable-nodpi" / "logo_lianai_app.webp")
save(square(512), ROOT / "docs" / "images" / "logo.webp")
save(square(512), ROOT / "docs" / "images" / "app-icon.png")

for folder, size in {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}.items():
    for name in ("ic_launcher.webp", "ic_launcher_round.webp"):
        save(square(size), RES / folder / name)

# Keep the complete supplied artwork. Its existing whitespace plus this inset
# keeps the heart inside Android's adaptive-icon safe area under different masks.
save(Image.new("RGBA", (432, 432), "white"), RES / "drawable-nodpi" / "ic_launcher_background.webp")
foreground = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
foreground.alpha_composite(square(360), (36, 36))
save(foreground, RES / "drawable-nodpi" / "ic_launcher_foreground.webp")

web_public = ROOT / "admin" / "web" / "public"
if not web_public.is_dir():
    web_public = ROOT.parent / "LianAi-admin" / "web" / "public"
if web_public.is_dir():
    save(square(512), web_public / "brand" / "logo.webp")
    save(square(192), web_public / "brand" / "icon.png")

print("Generated brand assets from", SOURCE)
