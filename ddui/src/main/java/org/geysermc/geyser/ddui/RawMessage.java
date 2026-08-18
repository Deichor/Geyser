/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.ddui;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The text shape a DDUI screen accepts: literal text, a translation key with substitutions, or a
 * sequence of both. It is the same rawtext Bedrock uses everywhere else, carried as a datastore
 * value rather than as JSON.
 */
public final class RawMessage {

    private final Map<String, Object> value;

    private RawMessage(Map<String, Object> value) {
        this.value = value;
    }

    public static RawMessage text(String text) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("text", text);
        return new RawMessage(map);
    }

    public static RawMessage translate(String key) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("translate", key);
        return new RawMessage(map);
    }

    public static RawMessage translate(String key, List<String> with) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("translate", key);
        map.put("with", new ArrayList<String>(with));
        return new RawMessage(map);
    }

    public static RawMessage translate(String key, RawMessage with) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("translate", key);
        map.put("with", with.serialize());
        return new RawMessage(map);
    }

    public static RawMessage rawText(List<RawMessage> parts) {
        List<Object> serialized = new ArrayList<>(parts.size());
        for (RawMessage part : parts) {
            serialized.add(part.serialize());
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rawtext", serialized);
        return new RawMessage(map);
    }

    /**
     * An empty message, which is what an omitted label or tooltip has to serialise to - the client
     * reads the field either way.
     */
    public static RawMessage empty() {
        return text("");
    }

    public static Map<String, Object> serialize(@Nullable RawMessage message) {
        return message == null ? empty().serialize() : message.serialize();
    }

    public Map<String, Object> serialize() {
        return new LinkedHashMap<>(value);
    }
}
