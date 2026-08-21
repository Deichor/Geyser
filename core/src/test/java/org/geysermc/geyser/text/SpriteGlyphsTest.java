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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.geysermc.geyser.translator.text.MessageTranslator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SpriteGlyphsTest {
    private static final Key BLOCKS = Key.key("minecraft:blocks");
    private static final Key EMERALD = Key.key("block/emerald_block");

    private static Component sprite(String atlas, String sprite) {
        return Component.object(ObjectContents.sprite(Key.key(atlas), Key.key(sprite)));
    }

    @AfterEach
    void reset() {
        SpriteGlyphs.load(new StringReader("{}"));
    }

    @Test
    void mapsASpriteToItsGlyph() {
        int loaded = SpriteGlyphs.load(new StringReader("""
            {
              "minecraft:blocks": { "block/emerald_block": "\\uE200" },
              "minecraft:items": { "item/apple": "\\uE201" }
            }
            """));

        assertEquals(2, loaded);
        assertEquals("\uE200", SpriteGlyphs.glyph(BLOCKS, EMERALD));
        assertEquals("\uE201", SpriteGlyphs.glyph(Key.key("minecraft:items"), Key.key("item/apple")));
    }

    @Test
    void normalisesUnnamespacedKeys() {
        SpriteGlyphs.load(new StringReader("{ \"blocks\": { \"block/emerald_block\": \"\\uE200\" } }"));

        // The same atlas, whether or not the document spelled out the namespace.
        assertEquals("\uE200", SpriteGlyphs.glyph(BLOCKS, EMERALD));
    }

    @Test
    void skipsEmptyGlyphs() {
        assertEquals(0, SpriteGlyphs.load(new StringReader("{ \"blocks\": { \"block/emerald_block\": \"\" } }")));
        assertNull(SpriteGlyphs.glyph(BLOCKS, EMERALD));
    }

    @Test
    void reportsAMissingFile(@TempDir Path folder) {
        assertEquals(-1, SpriteGlyphs.load(folder));
        assertTrue(SpriteGlyphs.isEmpty());
    }

    @Test
    void readsTheFileFromTheConfigFolder(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve(SpriteGlyphs.FILE_NAME),
            "{ \"minecraft:blocks\": { \"block/emerald_block\": \"\uE200\" } }");

        assertEquals(1, SpriteGlyphs.load(folder));
        assertEquals("\uE200", SpriteGlyphs.glyph(BLOCKS, EMERALD));
    }

    @Test
    void rendersAMappedSpriteIntoBedrockText() {
        SpriteGlyphs.load(new StringReader("{ \"minecraft:blocks\": { \"block/emerald_block\": \"\\uE200\" } }"));

        Component message = Component.text("Bakiye: ").append(sprite("minecraft:blocks", "block/emerald_block"));

        assertEquals("Bakiye: \uE200", MessageTranslator.convertMessageRaw(message, "en_US"));
    }

    @Test
    void dropsAnUnmappedSprite() {
        // Adventure's own fallback would leave "[block/emerald_block]" in the line.
        Component message = Component.text("Bakiye: ").append(sprite("minecraft:blocks", "block/emerald_block"));

        assertEquals("Bakiye: ", MessageTranslator.convertMessageRaw(message, "en_US"));
    }

    @Test
    void dropsALivePlayersHead() {
        // Nothing static to draw: Adventure's own fallback would leave "[Tim203 head]" behind.
        Component message = Component.text("Kapat: ")
            .append(Component.object(ObjectContents.playerHead().name("Tim203").build()));

        assertEquals("Kapat: ", MessageTranslator.convertMessageRaw(message, "en_US"));
    }

    // A profile property pointing at RED_X on Mojang's texture CDN, as carbon-sprite's HeadSprite
    // spells it: {"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/beb588..."}}}
    private static final String RED_X_HASH = "beb588b21a6f98ad1ff4e085c552dcb050efc9cab427f46048f18fc803475f7";
    private static final String RED_X_PROFILE = profile(RED_X_HASH);

    private static String profile(String hash) {
        return Base64.getEncoder().encodeToString(
            ("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static Component head(String base64) {
        return Component.object(ObjectContents.playerHead()
            .profileProperty(PlayerHeadObjectContents.property("textures", base64))
            .build());
    }

    @Test
    void mapsAKnownHeadByItsSkinHash() {
        SpriteGlyphs.load(new StringReader(
            "{ \"minecraft:player_head\": { \"" + RED_X_HASH + "\": \"\\uF1D2\" } }"));

        assertEquals("Kapat: \uF1D2",
            MessageTranslator.convertMessageRaw(Component.text("Kapat: ").append(head(RED_X_PROFILE)), "en_US"));
    }

    @Test
    void dropsAHeadTheTableDoesNotCarry() {
        SpriteGlyphs.load(new StringReader(
            "{ \"minecraft:player_head\": { \"" + RED_X_HASH + "\": \"\\uF1D2\" } }"));

        assertEquals("Kapat: ",
            MessageTranslator.convertMessageRaw(Component.text("Kapat: ").append(head(profile("deadbeef"))), "en_US"));
    }

    @Test
    void fallsBackForAHeadItCannotBuild() {
        SpriteGlyphs.load(new StringReader(
            "{ \"minecraft:player_head\": { \"" + RED_X_HASH + "\": \"\\uF1D2\", \"fallback\": \"\\uF1FF\" } }"));

        // A known head still wins; everything else - a live player, an unknown skin - gets Steve.
        assertEquals("a: \uF1D2",
            MessageTranslator.convertMessageRaw(Component.text("a: ").append(head(RED_X_PROFILE)), "en_US"));
        assertEquals("b: \uF1FF",
            MessageTranslator.convertMessageRaw(Component.text("b: ").append(head(profile("deadbeef"))), "en_US"));
        assertEquals("c: \uF1FF", MessageTranslator.convertMessageRaw(Component.text("c: ")
            .append(Component.object(ObjectContents.playerHead().name("Tim203").build())), "en_US"));
    }

    @Test
    void survivesAProfileItCannotRead() {
        SpriteGlyphs.load(new StringReader(
            "{ \"minecraft:player_head\": { \"" + RED_X_HASH + "\": \"\\uF1D2\" } }"));

        // Not base64, and base64 that is not a profile - neither may escape as an exception.
        assertEquals("a: ",
            MessageTranslator.convertMessageRaw(Component.text("a: ").append(head("not base64!")), "en_US"));
        assertEquals("b: ", MessageTranslator.convertMessageRaw(Component.text("b: ")
            .append(head(Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)))), "en_US"));
    }
}
