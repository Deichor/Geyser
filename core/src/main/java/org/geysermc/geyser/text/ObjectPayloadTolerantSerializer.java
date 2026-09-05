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
 * Repairs the two shapes an NBT-born component arrives in that Adventure's reader rejects outright.
 *
 * <p>Both come from the same place: a component reaches us as NBT, and MCProtocolLib turns that into
 * JSON before Adventure sees it. NBT has no type that survives the trip intact in either case, and
 * Adventure's reader is strict, so it throws while the packet is still being decoded — which costs
 * the WHOLE packet, not the one field. That is what makes these worth a wrapper: the damage is
 * never local to the component that carries the offending value.
 *
 * <ul>
 *   <li><b>A custom click event's payload.</b> The NBT compound behind a {@code custom} click event
 *       arrives as a JSON object where Adventure expects a string holding SNBT. Bedrock text has no
 *       click events, so the value is flattened to its JSON text purely to keep the shape Adventure
 *       demands — the same reasoning behind {@link DummyLegacyHoverEventSerializer}, which stands in
 *       for hover data we equally never read.
 *   <li><b>A player head object's {@code hat}.</b> NBT spells a boolean as a byte, so it lands as
 *       the number 0 or 1 where Adventure calls {@code nextBoolean()}. Measured on prod-smp
 *       2026-09-05: TAB draws rank badges as inline player-head objects in every tabprefix, so the
 *       packet that died was the tab list — and with it every {@code PlayerListEntry}. Geyser then
 *       refuses to spawn a player it has no entry for ("Haven't received PlayerListEntry packet
 *       before spawning player"), so Bedrock players saw no Java players at all while Java players
 *       saw them. Nothing in either log named the badge.
 * </ul>
 *
 * <p>{@code hat} is the only boolean an object component has ({@code ObjectContentsType}), and the
 * key is not valid anywhere else in a component, so coercing it wherever it appears as a number is
 * unambiguous.
 */
public final class ObjectPayloadTolerantSerializer implements GsonComponentSerializer {
    private static final String CLICK_EVENT = "click_event";
    private static final String PAYLOAD = "payload";
    private static final String HAT = "hat";

    /** How deep a component tree is followed. Matches the flattener's own limit. */
    private static final int NESTING_LIMIT = 30;

    private final GsonComponentSerializer delegate;

    public ObjectPayloadTolerantSerializer(GsonComponentSerializer delegate) {
        this.delegate = delegate;
    }

    @Override
    public Component deserializeFromTree(JsonElement input) {
        repair(input, 0);
        return delegate.deserializeFromTree(input);
    }

    @Override
    public Component deserialize(String input) {
        return deserializeFromTree(JsonParser.parseString(input));
    }

    /**
     * Rewrites the tree in place so Adventure can read it.
     *
     * <p>In place because the tree is ours: MCProtocolLib builds a fresh one out of the NBT for
     * every component it reads, and nothing else holds a reference to it.
     */
    private static void repair(JsonElement element, int depth) {
        if (depth > NESTING_LIMIT) {
            return;
        }

        if (element instanceof JsonArray array) {
            for (JsonElement entry : array) {
                repair(entry, depth + 1);
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

        JsonElement hat = object.get(HAT);
        if (hat != null && hat.isJsonPrimitive() && hat.getAsJsonPrimitive().isNumber()) {
            // Any non-zero byte is true, matching how NbtMap#getBoolean reads the same value on the
            // NBT path in MessageTranslator.
            object.addProperty(HAT, hat.getAsInt() != 0);
        }

        // Any value can hold a nested component - "extra", a translate argument, a hover event's
        // contents - so the whole object is walked rather than a list of known keys.
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!CLICK_EVENT.equals(entry.getKey())) {
                repair(entry.getValue(), depth + 1);
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
