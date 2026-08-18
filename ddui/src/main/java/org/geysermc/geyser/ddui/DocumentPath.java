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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Navigation over a screen document by the same path syntax the client uses, e.g.
 * {@code layout[3].label} or {@code button1.onClick}.
 *
 * <p>A bracket is not necessarily a list index: the custom form serialises its layout as an object
 * keyed by the decimal index, so {@code [3]} has to resolve against a map key too.
 */
public final class DocumentPath {

    private DocumentPath() {
    }

    public static List<String> split(String path) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            switch (c) {
                case '.' -> {
                    if (!current.isEmpty()) {
                        parts.add(current.toString());
                        current.setLength(0);
                    }
                }
                case '[' -> {
                    if (!current.isEmpty()) {
                        parts.add(current.toString());
                        current.setLength(0);
                    }
                }
                case ']' -> {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                default -> current.append(c);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    /**
     * Writes {@code value} at {@code path}, creating nothing: a path that does not already exist in
     * the document is refused, because the client only ever reports paths we published.
     *
     * @return true if the document was changed
     */
    @SuppressWarnings("unchecked")
    public static boolean set(Map<String, Object> document, String path, Object value) {
        List<String> parts = split(path);
        if (parts.isEmpty()) {
            return false;
        }
        Object cursor = document;
        for (int i = 0; i < parts.size() - 1; i++) {
            cursor = child(cursor, parts.get(i));
            if (cursor == null) {
                return false;
            }
        }
        String last = parts.get(parts.size() - 1);
        if (cursor instanceof Map<?, ?> map) {
            if (!map.containsKey(last)) {
                return false;
            }
            ((Map<String, Object>) map).put(last, value);
            return true;
        }
        if (cursor instanceof List<?> list) {
            int index = index(last);
            if (index < 0 || index >= list.size()) {
                return false;
            }
            ((List<Object>) list).set(index, value);
            return true;
        }
        return false;
    }

    public static Object get(Map<String, Object> document, String path) {
        Object cursor = document;
        for (String part : split(path)) {
            cursor = child(cursor, part);
            if (cursor == null) {
                return null;
            }
        }
        return cursor;
    }

    private static Object child(Object cursor, String part) {
        if (cursor instanceof Map<?, ?> map) {
            return map.get(part);
        }
        if (cursor instanceof List<?> list) {
            int index = index(part);
            return index < 0 || index >= list.size() ? null : list.get(index);
        }
        return null;
    }

    private static int index(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
