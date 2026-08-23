"""Rebuild the Android launcher icons and splash screens from the JW logo.

The Android project shipped with Capacitor's default artwork, so the app
appeared on the home screen, and at every launch, as a blue Capacitor mark.

Run from the repository root after changing public/icon-512.png or
public/logo.png, then rebuild the APK:

    python3 scripts/make-android-icons.py

Needs Pillow. The generated PNGs are committed, so this only has to run when
the artwork itself changes.
"""
from PIL import Image, ImageDraw

CREAM = (253, 251, 247)          # matches background_color in the web manifest
RES = "android/app/src/main/res"

def keyed(img):
    """White background out, mark left opaque, edges ramped rather than jagged."""
    img = img.convert("RGB")
    out = img.convert("RGBA")
    px, ax = img.load(), out.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            # How far this pixel is from white, as 0..1.
            ink = (255 - min(r, g, b)) / 255
            alpha = 0 if ink < 0.06 else min(1.0, ink / 0.35)
            ax[x, y] = (r, g, b, int(alpha * 255))
    return out

def tight(img):
    mask = img.split()[-1].point(lambda p: 255 if p > 8 else 0)
    return img.crop(mask.getbbox())

# The monogram: the full mark with its two words painted out. They sit clear of
# the J, the W and the leaves, so this loses the words and nothing else.
source = Image.open("public/icon-512.png").convert("RGB")
d = ImageDraw.Draw(source)
d.rectangle((0, 240, 214, 320), fill="white")     # GARDEN
d.rectangle((250, 330, 512, 400), fill="white")   # SERVICES
monogram = tight(keyed(source))
print("monogram:", monogram.size)

# The full wordmark, for the splash where there is room to read it.
wordmark = tight(Image.open("public/logo.png").convert("RGBA"))
print("wordmark:", wordmark.size)

def centred(mark, canvas_size, fraction, background):
    """Fit mark to `fraction` of the canvas and centre it."""
    canvas = Image.new("RGBA", canvas_size, background)
    cw, ch = canvas_size
    limit_w, limit_h = cw * fraction, ch * fraction
    scale = min(limit_w / mark.width, limit_h / mark.height)
    resized = mark.resize((max(1, round(mark.width * scale)),
                           max(1, round(mark.height * scale))), Image.LANCZOS)
    canvas.alpha_composite(resized, ((cw - resized.width) // 2,
                                     (ch - resized.height) // 2))
    return canvas

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

for name, scale in DENSITIES.items():
    legacy = round(48 * scale)
    adaptive = round(108 * scale)

    # Adaptive foreground: transparent, and small enough to survive the mask.
    # Android crops an adaptive icon to the middle 72 of 108dp, so anything
    # outside that can be cut off by whatever shape the launcher uses.
    centred(monogram, (adaptive, adaptive), 0.52, (0, 0, 0, 0)) \
        .save(f"{RES}/mipmap-{name}/ic_launcher_foreground.png")

    # Legacy square, for Android before adaptive icons.
    centred(monogram, (legacy, legacy), 0.74, CREAM + (255,)) \
        .save(f"{RES}/mipmap-{name}/ic_launcher.png")

    # Legacy round.
    round_icon = Image.new("RGBA", (legacy, legacy), (0, 0, 0, 0))
    circle = Image.new("L", (legacy * 4, legacy * 4), 0)
    ImageDraw.Draw(circle).ellipse((0, 0, legacy * 4 - 1, legacy * 4 - 1), fill=255)
    round_icon.paste(Image.new("RGBA", (legacy, legacy), CREAM + (255,)),
                     (0, 0), circle.resize((legacy, legacy), Image.LANCZOS))
    mark = centred(monogram, (legacy, legacy), 0.66, (0, 0, 0, 0))
    round_icon.alpha_composite(mark)
    round_icon.save(f"{RES}/mipmap-{name}/ic_launcher_round.png")

# Splash screens. Sized to match the files Capacitor generated, so the
# density buckets still line up with what the manifest expects.
SPLASH = {
    ("port", "mdpi"): (320, 480), ("port", "hdpi"): (480, 800),
    ("port", "xhdpi"): (720, 1280), ("port", "xxhdpi"): (960, 1600),
    ("port", "xxxhdpi"): (1280, 1920),
    ("land", "mdpi"): (480, 320), ("land", "hdpi"): (800, 480),
    ("land", "xhdpi"): (1280, 720), ("land", "xxhdpi"): (1600, 960),
    ("land", "xxxhdpi"): (1920, 1280),
}
for (orient, density), size in SPLASH.items():
    centred(wordmark, size, 0.55, CREAM + (255,)) \
        .convert("RGB").save(f"{RES}/drawable-{orient}-{density}/splash.png")

# The unqualified drawable is the fallback when no bucket matches.
centred(wordmark, (480, 320), 0.55, CREAM + (255,)).convert("RGB") \
    .save(f"{RES}/drawable/splash.png")
print("done")
