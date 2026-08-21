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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

public class ObjectPayloadTolerantSerializerTest {
    private static final GsonComponentSerializer PLAIN = GsonComponentSerializer.gson();
    private static final GsonComponentSerializer TOLERANT = new ObjectPayloadTolerantSerializer(PLAIN);

    /** What a custom click event looks like once its NBT compound has been turned into JSON. */
    private static final String OBJECT_PAYLOAD = """
        {"text":"Click","click_event":{"action":"custom","id":"titan:dialog","payload":{"page":2}}}
        """;

    private static String plainText(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void adventureAloneCannotReadAnObjectPayload() {
        // The failure this exists for: it throws while the packet is still being decoded, so the
        // message never reaches the player.
        assertThrows(Exception.class, () -> PLAIN.deserializeFromTree(JsonParser.parseString(OBJECT_PAYLOAD)));
    }

    @Test
    void readsAComponentWhosePayloadIsAnObject() {
        Component component = TOLERANT.deserializeFromTree(JsonParser.parseString(OBJECT_PAYLOAD));

        assertEquals("Click", plainText(component));
    }

    @Test
    void leavesAStringPayloadAlone() {
        String json = "{\"text\":\"a\",\"click_event\":{\"action\":\"custom\",\"id\":\"titan:d\",\"payload\":\"{page:2}\"}}";

        assertEquals("a", plainText(TOLERANT.deserializeFromTree(JsonParser.parseString(json))));
    }

    @Test
    void reachesAPayloadNestedInExtra() {
        String json = "{\"text\":\"a\",\"extra\":[" + OBJECT_PAYLOAD.trim() + "]}";

        assertEquals("aClick", plainText(TOLERANT.deserializeFromTree(JsonParser.parseString(json))));
    }

    @Test
    void readsAComponentWithNoClickEventAtAll() {
        assertEquals("hello", plainText(TOLERANT.deserialize("{\"text\":\"hello\"}")));
    }

    @Test
    void serialisingIsUntouched() {
        Component styled = Component.text("hello").append(Component.text(" there"));

        assertEquals(PLAIN.serialize(styled), TOLERANT.serialize(styled));
        assertEquals("hello there", plainText(TOLERANT.deserialize(TOLERANT.serialize(styled))));
    }
}
