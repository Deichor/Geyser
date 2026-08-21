#!/usr/bin/env python3
"""Build Bedrock glyph pages, and the sprites.json that names them, from a Java client jar.

Bedrock cannot draw an atlas sprite inline, so a sprite is only renderable in chat, on a
scoreboard or in an item name as a glyph out of a font page. The pixels and the mapping have to
agree exactly, so both come out of this one run:

    font/glyph_E2.png ...   -> into the Bedrock resource pack the server sends
    sprites.json            -> into the Geyser config folder

Both halves are addressed by codepoint, so regenerating with a different sprite list reshuffles
them; ship the pair together.

Usage:
    generate.py --client 26.2.jar --atlas-manifest sprites.json --out build/
    generate.py --client 26.2.jar --sprite minecraft:items=item/apple --out build/

Vanilla occupies glyph pages E0, E1 and F9-FF, leaving E2-F8 - 23 pages of 256 cells.
"""

import argparse
import base64
import hashlib
import io
import json
import math
import os
import ssl
import subprocess
import sys
import urllib.request
import uuid
import zipfile
from collections import OrderedDict

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is required: pip install Pillow")

# Pages vanilla does not use. Each holds a 16x16 grid, so 256 cells.
FREE_PAGES = [f"{p:02X}" for p in range(0xE2, 0xF9)]
CELLS_PER_PAGE = 256

# Where an atlas' sprite keys live inside the client jar. The gui atlas is the odd one: its keys
# are relative to textures/gui/sprites, the others to textures.
ATLAS_ROOTS = {
    "minecraft:gui": "assets/minecraft/textures/gui/sprites/",
}
DEFAULT_ROOT = "assets/minecraft/textures/"

# Player heads are not in any atlas, but from the mapping's point of view they behave like one:
# keyed by the skin's texture hash instead of a sprite path. Geyser looks them up under this name.
HEAD_ATLAS = "minecraft:player_head"

# The stand-in a head that resolves to nothing falls back to. Not a hash, so it can never collide
# with a real one. Its pixels come out of the client jar, so building it costs no network.
FALLBACK_HEAD_KEY = "fallback"
FALLBACK_HEAD_SKIN = "assets/minecraft/textures/entity/player/wide/steve.png"


def texture_hash(value):
    """The skin hash a base64 profile property points at - stable across re-encodings of the same
    profile, which the base64 itself is not. Passed through if it already is a bare hash."""
    value = value.strip()
    if "/" not in value and "{" not in value and len(value) < 128:
        return value
    profile = json.loads(base64.b64decode(value))
    return profile["textures"]["SKIN"]["url"].rstrip("/").rsplit("/", 1)[-1]


def fetch(url):
    """Mojang's texture CDN. urllib first; a TLS-inspecting network fails it while curl, which
    reads the system trust store, still gets through."""
    try:
        return urllib.request.urlopen(url, timeout=20, context=ssl.create_default_context()).read()
    except (ssl.SSLError, urllib.error.URLError):
        done = subprocess.run(["curl", "-sSfL", url], capture_output=True)
        if done.returncode != 0:
            raise RuntimeError(done.stderr.decode().strip() or f"could not fetch {url}")
        return done.stdout


def head_image(skin, hat=True):
    """The 8x8 head a player-head object component draws: the face, with the hat layer over it."""
    head = skin.crop((8, 8, 16, 16))
    if hat:
        overlay = skin.crop((40, 8, 48, 16))
        head.paste(overlay, (0, 0), overlay)
    return head


def load_heads(args):
    """Requested heads as an ordered {hash: base64-or-hash}, so a repeat costs one cell."""
    values = list(args.head)
    if args.heads:
        document = json.load(open(args.heads, encoding="utf-8"))
        values += list(document.values()) if isinstance(document, dict) else list(document)

    heads = OrderedDict()
    for value in values:
        heads.setdefault(texture_hash(value), value)
    return heads


def resolve_heads(heads, cache_dir):
    """Downloads each skin once, caching by hash so re-runs are offline."""
    if cache_dir:
        os.makedirs(cache_dir, exist_ok=True)

    resolved, failed = [], []
    for digest in heads:
        cached = os.path.join(cache_dir, digest + ".png") if cache_dir else None
        try:
            if cached and os.path.exists(cached):
                raw = open(cached, "rb").read()
            else:
                raw = fetch("https://textures.minecraft.net/texture/" + digest)
                if cached:
                    open(cached, "wb").write(raw)
            skin = Image.open(io.BytesIO(raw)).convert("RGBA")
            skin.load()
        except Exception as error:  # noqa: BLE001 - one bad head must not lose the rest
            failed.append(f"{digest[:16]}... ({error})")
            continue
        resolved.append((HEAD_ATLAS, digest, head_image(skin)))
    return resolved, failed


def sprite_paths(atlas, key):
    """Where to look, best first. The gui atlas stitches from more than one root - mob effect
    icons, for one, sit outside gui/sprites - so its keys need both."""
    root = ATLAS_ROOTS.get(atlas, DEFAULT_ROOT)
    paths = [root + key + ".png"]
    if root != DEFAULT_ROOT:
        paths.append(DEFAULT_ROOT + key + ".png")
    return paths


def load_requested(args):
    """The sprites to build, as an ordered [(atlas, key)] - order fixes codepoint assignment."""
    requested = OrderedDict()
    if args.atlas_manifest:
        manifest = json.load(open(args.atlas_manifest, encoding="utf-8"))
        for atlas in sorted(manifest):
            if args.atlas and atlas not in args.atlas:
                continue
            for key in sorted(manifest[atlas]):
                requested[(atlas, key)] = None
    for pair in args.sprite:
        atlas, _, key = pair.partition("=")
        requested[(atlas, key)] = None
    return list(requested)


def art_size(image, cell, ratio):
    """The pixel size the art is drawn at inside the glyph box, preserving aspect.

    Not the whole cell. A glyph is drawn at the size it occupies in the page, so art that fills its
    cell renders taller than the text beside it and, with nothing left on the right, runs into the
    glyph after it. Vanilla's letters sit at about half a 16px cell and its emoji at three quarters;
    [ratio] is which of those to match.
    """
    height = max(1, round(cell * ratio))
    width = max(1, round(image.width * height / image.height)) if image.height else height
    return width, height


def cells_for(image, cell, ratio):
    """How many square cells this sprite needs. Wide art spans consecutive cells."""
    width, _ = art_size(image, cell, ratio)
    return max(1, math.ceil(width / cell))


# A stable identity for the pack, so a client updating it replaces the one it holds rather than
# stacking a second copy. Fixed, because the pack is the same pack; only its version moves.
PACK_UUID = "8f2b6c14-0d3a-4f77-9c21-6a5e1b0d4e33"
MODULE_UUID = "c41d7e05-3b62-4a19-8f0e-27d9b4c6a1f8"


def write_mcpack(out_dir, name, pages):
    """A minimal Bedrock pack carrying nothing but the glyph pages.

    The version is derived from the pages themselves. Bedrock caches a pack by uuid+version, so a
    regenerated pack under an unchanged version reaches no client that already holds the old one —
    the same trap carbon-bedrock-ui hit when a composed pack kept a pinned identity.
    """
    digest = hashlib.sha256()
    for page in pages:
        digest.update(open(os.path.join(out_dir, "font", f"glyph_{page}.png"), "rb").read())
    fingerprint = int(digest.hexdigest()[:6], 16)
    version = [1, fingerprint // 1000, fingerprint % 1000]

    manifest = {
        "format_version": 2,
        "header": {
            "name": "Titan sprite glyphs",
            "description": "Java atlas sprites as Bedrock font glyphs",
            "uuid": PACK_UUID,
            "version": version,
            "min_engine_version": [1, 16, 0],
        },
        "modules": [{
            "description": "Glyph pages",
            "type": "resources",
            "uuid": MODULE_UUID,
            "version": version,
        }],
    }

    path = os.path.join(out_dir, name + ".mcpack")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as pack:
        pack.writestr("manifest.json", json.dumps(manifest, indent=2))
        for page in pages:
            rel = f"font/glyph_{page}.png"
            pack.write(os.path.join(out_dir, rel), rel)
    print(f"{name}.mcpack  {os.path.getsize(path) // 1024} KB  version={version}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--client", help="vanilla Java client jar to take pixels from")
    parser.add_argument("--atlas-manifest", help="{atlas: [sprite keys]} json (carbon-sprite's export)")
    parser.add_argument("--atlas", action="append", default=[], help="restrict the manifest to this atlas (repeatable)")
    parser.add_argument("--sprite", action="append", default=[], help="one extra atlas=key (repeatable)")
    parser.add_argument("--heads", help="json list/map of base64 profile values or skin hashes")
    parser.add_argument("--head", action="append", default=[], help="one extra base64 profile or hash (repeatable)")
    parser.add_argument("--skin-cache", default=".skins", help="where downloaded skins are kept (default .skins)")
    parser.add_argument("--fallback-head", action="store_true",
                        help="add a Steve glyph that heads with no mapping of their own fall back to")
    parser.add_argument("--out", required=True, help="output directory")
    parser.add_argument("--mcpack", metavar="NAME",
                        help="also write NAME.mcpack (the glyph pages plus a manifest) next to sprites.json")
    # Cell size is not resolution alone: a 16px cell (a 256x256 page) is the box one text character
    # occupies, and doubling it doubles how big the glyph is drawn. Raise it for detail only
    # alongside a matching drop in --art-ratio, or the art simply gets bigger.
    parser.add_argument("--cell", type=int, default=16, help="cell size in px; page is 16x this (default 16)")
    parser.add_argument("--art-ratio", type=float, default=0.5,
                        help="fraction of the cell the art fills - this is what governs how big a "
                             "glyph looks (default 0.5, matching the height of a capital letter)")
    parser.add_argument("--start-page", default="E2", help="first glyph page to fill (default E2)")
    args = parser.parse_args()

    requested = load_requested(args)
    heads = load_heads(args)
    if not requested and not heads:
        sys.exit("Nothing requested: pass --atlas-manifest, --sprite, --heads or --head.")

    if args.start_page.upper() not in FREE_PAGES:
        sys.exit(f"--start-page must be one of {FREE_PAGES[0]}..{FREE_PAGES[-1]}")
    pages = FREE_PAGES[FREE_PAGES.index(args.start_page.upper()):]

    jar = zipfile.ZipFile(args.client) if (requested or args.fallback_head) else None
    names = set(jar.namelist()) if jar else set()

    # Resolve first, so a sprite the jar does not carry costs no codepoint.
    resolved, missing = [], []
    for atlas, key in requested:
        path = next((candidate for candidate in sprite_paths(atlas, key) if candidate in names), None)
        if path is None:
            missing.append(f"{atlas} {key}")
            continue
        with jar.open(path) as handle:
            image = Image.open(handle).convert("RGBA")
            image.load()
        resolved.append((atlas, key, image))

    head_images, head_failures = resolve_heads(heads, args.skin_cache)
    resolved += head_images

    if args.fallback_head:
        if FALLBACK_HEAD_SKIN not in names:
            sys.exit(f"--fallback-head needs {FALLBACK_HEAD_SKIN}; pass the client jar with --client.")
        with jar.open(FALLBACK_HEAD_SKIN) as handle:
            steve = Image.open(handle).convert("RGBA")
            steve.load()
        resolved.append((HEAD_ATLAS, FALLBACK_HEAD_KEY, head_image(steve)))

    needed = sum(cells_for(image, args.cell, args.art_ratio) for _, _, image in resolved)
    capacity = len(pages) * CELLS_PER_PAGE
    if needed > capacity:
        sys.exit(f"{needed} cells needed but only {capacity} free from page {args.start_page}.")

    os.makedirs(os.path.join(args.out, "font"), exist_ok=True)
    mapping, sheets = {}, {}
    slot = 0

    for atlas, key, image in resolved:
        span = cells_for(image, args.cell, args.art_ratio)
        # Never split a sprite across two pages: the glyph would break at the page edge.
        page_index, column = divmod(slot, CELLS_PER_PAGE)
        if column + span > CELLS_PER_PAGE:
            slot = (page_index + 1) * CELLS_PER_PAGE
            page_index, column = divmod(slot, CELLS_PER_PAGE)

        page = pages[page_index]
        sheet = sheets.get(page)
        if sheet is None:
            sheet = sheets[page] = Image.new("RGBA", (args.cell * 16, args.cell * 16), (0, 0, 0, 0))

        # Drawn into a box the width of the cells it spans, so the leftover sits on the right as
        # the gap to the next glyph - the same shape vanilla uses.
        width, height = art_size(image, args.cell, args.art_ratio)
        box = Image.new("RGBA", (args.cell * span, args.cell), (0, 0, 0, 0))
        box.paste(image.resize((width, height), Image.NEAREST), (0, (args.cell - height) // 2))

        chars = ""
        for part in range(span):
            index = column + part
            row, col = divmod(index, 16)
            sheet.paste(box.crop((part * args.cell, 0, (part + 1) * args.cell, args.cell)),
                        (col * args.cell, row * args.cell))
            chars += chr(int(page, 16) * 256 + index)

        mapping.setdefault(atlas, {})[key] = chars
        slot += span

    for page, sheet in sheets.items():
        sheet.save(os.path.join(args.out, "font", f"glyph_{page}.png"))

    with open(os.path.join(args.out, "sprites.json"), "w", encoding="utf-8") as handle:
        json.dump(mapping, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")

    if args.mcpack:
        write_mcpack(args.out, args.mcpack, sorted(sheets))

    total = sum(len(v) for v in mapping.values())
    print(f"{total} sprites -> {len(sheets)} page(s) {sorted(sheets)} at {args.cell}px cells")
    for page in sorted(sheets):
        path = os.path.join(args.out, "font", f"glyph_{page}.png")
        print(f"  font/glyph_{page}.png  {os.path.getsize(path) // 1024} KB")
    if missing:
        print(f"{len(missing)} sprite(s) not in the jar, left unmapped, e.g. {missing[:5]}")
    if head_failures:
        print(f"{len(head_failures)} head(s) could not be fetched, left unmapped: {head_failures[:5]}")


if __name__ == "__main__":
    main()
