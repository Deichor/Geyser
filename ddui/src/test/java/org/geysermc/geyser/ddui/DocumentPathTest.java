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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentPathTest {

    @Test
    void aBracketResolvesAgainstAMapKey() {
        // The custom form publishes its layout as an object keyed by the decimal index, but the
        // client still addresses it as layout[3]. Treating a bracket as a list index only would
        // silently fail to find every component.
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("label", "old");
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("3", entry);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("layout", layout);

        assertTrue(DocumentPath.set(document, "layout[3].label", "new"));
        assertEquals("new", DocumentPath.get(document, "layout[3].label"));
    }

    @Test
    void aBracketAlsoResolvesAgainstAList() {
        List<Object> items = new ArrayList<>(List.of("a", "b"));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("items", items);

        assertTrue(DocumentPath.set(document, "items[1]", "c"));
        assertEquals("c", DocumentPath.get(document, "items[1]"));
    }

    @Test
    void aPathThatDoesNotExistIsNotCreated() {
        // The client only ever reports paths we published; inventing one on write would hide a
        // mistyped binding behind a document that quietly grows.
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("title", "Market");

        assertFalse(DocumentPath.set(document, "subtitle", "x"));
        assertFalse(DocumentPath.set(document, "layout[0].label", "x"));
        assertEquals(1, document.size());
        assertNull(DocumentPath.get(document, "subtitle"));
    }

    @Test
    void splittingHandlesBothSeparators() {
        assertEquals(List.of("layout", "12", "label"), DocumentPath.split("layout[12].label"));
        assertEquals(List.of("button1", "onClick"), DocumentPath.split("button1.onClick"));
        assertEquals(List.of("title"), DocumentPath.split("title"));
    }
}
