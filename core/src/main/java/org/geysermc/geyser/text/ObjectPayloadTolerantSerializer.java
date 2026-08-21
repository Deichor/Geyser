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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/**
 * Reads a component whose custom click event carries its payload as an object.
 *
 * <p>A component reaches us as NBT, which MCProtocolLib turns into JSON before Adventure reads it —
 * so the NBT compound behind a {@code custom} click event arrives as a JSON object, while Adventure
 * expects that field to be a string holding SNBT. The mismatch throws while the packet is still
 * being decoded, which costs the whole packet: a system chat message carrying one never reaches the
 * player at all.
 *
 * <p>The payload itself is of no interest here. Bedrock text has no click events, so the value is
 * flattened to its JSON text purely to keep the shape Adventure demands - the same reasoning behind
 * {@link DummyLegacyHoverEventSerializer}, which stands in for hover data we equally never read.
 */
public final class ObjectPayloadTolerantSerializer implements GsonComponentSerializer {
    private static final String CLICK_EVENT = "click_event";
    private static final String PAYLOAD = "payload";

    /** How deep a component tree is followed. Matches the flattener's own limit. */
    private static final int NESTING_LIMIT = 30;

    private final GsonComponentSerializer delegate;

    public ObjectPayloadTolerantSerializer(GsonComponentSerializer delegate) {
        this.delegate = delegate;
    }

    @Override
    public Component deserializeFromTree(JsonElement input) {
        flattenPayloads(input, 0);
        return delegate.deserializeFromTree(input);
    }

    @Override
    public Component deserialize(String input) {
        return deserializeFromTree(JsonParser.parseString(input));
    }

    /**
     * Rewrites every object-valued click event payload in the tree to its JSON text, in place.
     *
     * <p>In place because the tree is ours: MCProtocolLib builds a fresh one out of the NBT for
     * every component it reads, and nothing else holds a reference to it.
     */
    private static void flattenPayloads(JsonElement element, int depth) {
        if (depth > NESTING_LIMIT) {
            return;
        }

        if (element instanceof JsonArray array) {
            for (JsonElement entry : array) {
                flattenPayloads(entry, depth + 1);
            }
            return;
        }

        if (!(element instanceof JsonObject object)) {
            return;
        }

        JsonElement clickEvent = object.get(CLICK_EVENT);
        if (clickEvent instanceof JsonObject click) {
            JsonElement payload = click.get(PAYLOAD);
            if (payload != null && !payload.isJsonPrimitive()) {
                click.addProperty(PAYLOAD, payload.toString());
            }
        }

        // Any value can hold a nested component - "extra", a translate argument, a hover event's
        // contents - so the whole object is walked rather than a list of known keys.
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!CLICK_EVENT.equals(entry.getKey())) {
                flattenPayloads(entry.getValue(), depth + 1);
            }
        }
    }

    @Override
    public String serialize(Component component) {
        return delegate.serialize(component);
    }

    @Override
    public JsonElement serializeToTree(Component component) {
        return delegate.serializeToTree(component);
    }

    @Override
    public Gson serializer() {
        return delegate.serializer();
    }

    @Override
    public UnaryOperator<com.google.gson.GsonBuilder> populator() {
        return delegate.populator();
    }

    @Override
    public Builder toBuilder() {
        return delegate.toBuilder();
    }
}
