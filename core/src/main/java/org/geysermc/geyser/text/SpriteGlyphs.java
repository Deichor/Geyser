/*
 * Copyright (c) 2019-2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.text;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Java atlas sprites rendered on Bedrock as custom font glyphs.
 *
 * <p>Bedrock has no inline-image primitive for text, so the only way to draw a sprite into a chat
 * line, a scoreboard entry or an item name is a glyph from a {@code font/glyph_XX.png} page that a
 * resource pack supplies. This holds the resulting {@code atlas + sprite -> glyph} table; the pixels
 * live in the pack, and the two are generated together (see {@code tools/sprite-glyphs}).
 *
 * <p>The table is optional. Without it Geyser keeps its previous behaviour of dropping sprites,
 * which is what a client with no such pack has to render anyway.
 */
public final class SpriteGlyphs {
    public static final String FILE_NAME = "sprites.json";

    /**
     * Player heads belong to no atlas, but the table treats them as one: their "sprite" is the hash
     * of the skin they point at. Only a head that is known ahead of time can become a glyph, which
     * covers the decorative heads used as icons - a live player's head cannot, as the pixels would
     * have to be in a pack the client already downloaded.
     */
    private static final Key HEAD_ATLAS = Key.key("minecraft:player_head");

    /**
     * The stand-in for a head with no mapping of its own - a live player's, most of the time. It is
     * not a skin hash, so it can never be shadowed by a real head.
     */
    private static final Key FALLBACK_HEAD = Key.key("fallback");

    /** Bounds what an unbounded stream of unique player profiles could otherwise grow to. */
    private static final int TEXTURE_CACHE_LIMIT = 1024;

    private static volatile Map<String, String> glyphs = Map.of();
    private static final Map<String, String> textureHashes = new ConcurrentHashMap<>();

    private SpriteGlyphs() {
    }

    /**
     * Loads {@value #FILE_NAME} from the Geyser config folder, replacing any table already loaded.
     *
     * @return how many sprites are mapped, or -1 when there is no file to read
     */
    public static int load(Path configFolder) {
        Path file = configFolder.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            glyphs = Map.of();
            return -1;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return load(reader);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + file, e);
        }
    }

    /**
     * Reads a {@code {"<atlas>": {"<sprite>": "<glyph>"}}} document. A glyph may be more than one
     * character: glyph cells are square, so wide art is drawn across consecutive cells.
     */
    public static int load(Reader reader) {
        Map<String, String> parsed = new HashMap<>();
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

        for (Map.Entry<String, JsonElement> atlas : root.entrySet()) {
            // Both halves are namespaced keys on the wire, so they are normalised here rather than
            // at every lookup - "blocks" and "minecraft:blocks" are the same atlas.
            String atlasKey = Key.key(atlas.getKey()).asString();
            for (Map.Entry<String, JsonElement> sprite : atlas.getValue().getAsJsonObject().entrySet()) {
                String glyph = sprite.getValue().getAsString();
                if (!glyph.isEmpty()) {
                    parsed.put(key(atlasKey, Key.key(sprite.getKey()).asString()), glyph);
                }
            }
        }

        glyphs = Map.copyOf(parsed);
        return parsed.size();
    }

    /**
     * The glyph for a sprite, or null when the pack does not carry it.
     */
    public static @Nullable String glyph(Key atlas, Key sprite) {
        return glyphs.get(key(atlas.asString(), sprite.asString()));
    }

    /**
     * The glyph for a player head, or null when it is not one of the heads the pack was built with.
     */
    public static @Nullable String glyph(PlayerHeadObjectContents head) {
        if (glyphs.isEmpty()) {
            return null;
        }

        Key texture = head.texture();
        if (texture != null) {
            String glyph = glyphs.get(key(HEAD_ATLAS.asString(), texture.asString()));
            if (glyph != null) {
                return glyph;
            }
        }

        for (PlayerHeadObjectContents.ProfileProperty property : head.profileProperties()) {
            if (!"textures".equals(property.name())) {
                continue;
            }
            String hash = textureHash(property.value());
            String headKey = hash == null ? null : headKey(hash);
            String glyph = headKey == null ? null : glyphs.get(headKey);
            if (glyph != null) {
                return glyph;
            }
            break;
        }

        // Nothing was built for this head - a live player's skin cannot be, since the glyph pages
        // were downloaded before the client knew whose head it would be asked to draw.
        return glyphs.get(key(HEAD_ATLAS.asString(), FALLBACK_HEAD.asString()));
    }

    /**
     * A skin hash as the table spells it. Hashes carry no namespace, so they are run through the
     * same normalisation {@link #load(Reader)} applies to every key it reads.
     */
    private static @Nullable String headKey(String hash) {
        try {
            return key(HEAD_ATLAS.asString(), Key.key(hash).asString());
        } catch (Exception e) {
            // Not a value a key can hold, so nothing could have been mapped to it either.
            return null;
        }
    }

    /**
     * The skin hash a base64 profile property points at. The base64 itself is not a stable key -
     * the same skin re-encoded differs - and decoding it on every component would be wasteful, so
     * the answer is remembered per distinct value.
     */
    private static @Nullable String textureHash(String base64) {
        String cached = textureHashes.get(base64);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }

        String hash = "";
        try {
            String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            JsonObject skin = JsonParser.parseString(json)
                .getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN");
            String url = skin.get("url").getAsString();
            int slash = url.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < url.length()) {
                hash = url.substring(slash + 1);
            }
        } catch (Exception e) {
            // Not a profile this can read; remembered as a miss so it is only ever tried once.
        }

        if (textureHashes.size() >= TEXTURE_CACHE_LIMIT) {
            textureHashes.clear();
        }
        textureHashes.put(base64, hash);
        return hash.isEmpty() ? null : hash;
    }

    public static boolean isEmpty() {
        return glyphs.isEmpty();
    }

    private static String key(String atlas, String sprite) {
        return atlas + ' ' + sprite;
    }
}
