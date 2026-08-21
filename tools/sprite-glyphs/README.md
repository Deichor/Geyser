# sprite-glyphs

Renders Java atlas sprites on Bedrock.

A Java `object`/`atlas` text component (`{"type":"object","object":"atlas","atlas":"minecraft:blocks",
"sprite":"block/emerald_block"}`, MC 1.21.9+) draws an 8×8 square out of a client-side atlas. Bedrock
has no inline-image primitive for text, so the only way to get one into a chat line, a scoreboard
entry or an item name is a glyph from a `font/glyph_XX.png` page that a resource pack supplies.

`generate.py` builds both halves in one run, from a vanilla **Java** client jar — the sprite key is
already a path into it, so coverage is complete and the art is pixel-identical to what Java players
see. Matching against Bedrock's own textures instead only lines up for about 44% of keys.

```bash
python3 generate.py \
  --client /path/to/26.2.jar \
  --atlas-manifest ../../../Carbon/carbon-sprite/src/test/resources/atlas/sprites.json \
  --out build/
```

Two outputs, which must ship together — both are addressed by codepoint, so regenerating against a
different sprite list reshuffles them:

| Output | Goes to |
|---|---|
| `font/glyph_XX.png` | the Bedrock resource pack the server sends |
| `sprites.json` | the Geyser config folder |

Geyser reads `sprites.json` at startup (`SpriteGlyphs`) and `MessageTranslator` swaps each sprite for
its glyph. With no file present, sprites render as nothing — the same as on a client without the
pack, and quieter than Adventure's `[block/emerald_block]` fallback.

## Player heads

A player-head object component splits in two, and only one half can be a glyph.

**Heads known ahead of time** — the decorative ones used as icons, `HeadSprite` and anything built
with `customHead` — are just a fixed skin behind a base64 profile, so they precompute like any other
sprite. `--heads` takes a json list or map of those base64 values (or bare skin hashes), fetches each
skin from Mojang's CDN once into `--skin-cache`, composites the face with the hat layer over it, and
maps it under `minecraft:player_head`:

```bash
python3 generate.py --client 26.2.jar --heads heads.json --out build/
```

The mapping is keyed by **skin hash**, not by the base64 — the same profile re-encoded gives
different base64 but the same hash, so a head keeps working across encodings. Geyser decodes each
distinct profile once and caches the result.

**A live player's head** cannot, and fetching the skin is not what stands in the way — Geyser can
already do that. Glyph pages are downloaded *before* the client enters the world, and no packet adds
a glyph to a pack that is already applied, so pixels that depend on who is being talked about cannot
arrive in time.

`--fallback-head` adds a Steve glyph, taken from the client jar, that any head with no mapping of
its own falls back to. One cell, no network. Without it such heads render as nothing, which is also
fine — the player's name is normally right next to the head.

Where a live head genuinely matters, use a channel that fetches at runtime instead: a Cumulus form
image takes `FormImage.Type.URL`, so a form button can point straight at a head API, and a real
player-head *item* in a chest menu already works on Bedrock.

## Budget

Vanilla uses glyph pages `E0`, `E1` and `F9`–`FF`, leaving `E2`–`F8`: 23 pages × 256 cells = 5888.
Against the 26.2 client the full blocks + items + gui manifest plus carbon-sprite's 57 heads resolves
2637 entries into 16 pages, ~800 KB at the default 32px cells (`--cell 16` costs ~590 KB). The 64
misses are armor trim overlays, which Java generates from palette permutations rather than shipping as files.

Cells are square. Wider art is split across consecutive cells and the mapping records the whole run,
so a 40×8 badge is one four-character string — never split across a page boundary.

## Caveats

- Glyphs do not render in Ore UI screens, which includes DDUI. Use a `Form.Image` there instead: it
  can point straight at a texture path, with nothing to download.
- The pages carry Mojang's artwork; that is the same footing as any Bedrock pack that reuses vanilla
  textures, but it is worth knowing before publishing one.
