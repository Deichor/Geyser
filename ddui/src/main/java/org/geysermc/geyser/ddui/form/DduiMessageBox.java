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

package org.geysermc.geyser.ddui.form;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundDataDrivenScreenClosedPacket.CloseReason;
import org.geysermc.geyser.ddui.RawMessage;
import org.geysermc.geyser.ddui.ScreenSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * The vanilla {@code minecraft:message_box} screen - a title, a body and two buttons.
 */
public final class DduiMessageBox {

    private final RawMessage title;
    private final RawMessage body;
    private final RawMessage button1;
    private final @Nullable RawMessage tooltip1;
    private final RawMessage button2;
    private final @Nullable RawMessage tooltip2;

    private int selected = -1;

    public DduiMessageBox(RawMessage title, RawMessage body, RawMessage button1, @Nullable RawMessage tooltip1,
                          RawMessage button2, @Nullable RawMessage tooltip2) {
        this.title = title;
        this.body = body;
        this.button1 = button1;
        this.tooltip1 = tooltip1;
        this.button2 = button2;
        this.tooltip2 = tooltip2;
    }

    public DduiMessageBox(String title, String body, String button1, String button2) {
        this(RawMessage.text(title), RawMessage.text(body), RawMessage.text(button1), null,
                RawMessage.text(button2), null);
    }

    /**
     * Which button the player pressed, if any. Empty until one is.
     */
    public OptionalInt selected() {
        return selected < 0 ? OptionalInt.empty() : OptionalInt.of(selected);
    }

    public Map<String, Object> serialize() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("title", title.serialize());
        document.put("body", body.serialize());
        document.put("button1", button(button1, tooltip1));
        document.put("button2", button(button2, tooltip2));
        return document;
    }

    public void show(ScreenSession session, @Nullable Consumer<CloseReason> onClosed) {
        session.listen("button1.onClick", value -> selected = 1);
        session.listen("button2.onClick", value -> selected = 2);
        session.show(serialize(), onClosed);
    }

    private static Map<String, Object> button(RawMessage label, @Nullable RawMessage tooltip) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("label", label.serialize());
        map.put("tooltip", RawMessage.serialize(tooltip));
        map.put("onClick", 0.0d);
        return map;
    }
}
