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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The vanilla {@code minecraft:custom_form} screen, driven from the server.
 *
 * <p>It is the closest DDUI equivalent of a Cumulus custom form, with the difference that matters:
 * a button press is a datastore write, not a form response, so the screen stays open and the server
 * can edit it in place while the player is looking at it.
 */
public final class DduiCustomForm {

    private final RawMessage title;
    private final boolean closeButton;
    private final List<FormComponent> components;

    private DduiCustomForm(RawMessage title, boolean closeButton, List<FormComponent> components) {
        this.title = title;
        this.closeButton = closeButton;
        this.components = components;
    }

    public static Builder builder(RawMessage title) {
        return new Builder(title);
    }

    public static Builder builder(String title) {
        return new Builder(RawMessage.text(title));
    }

    public List<FormComponent> components() {
        return components;
    }

    public Map<String, Object> serialize() {
        Map<String, Object> layout = new LinkedHashMap<>();
        for (int index = 0; index < components.size(); index++) {
            layout.put(Integer.toString(index), components.get(index).serialize());
        }
        layout.put("length", components.size());

        Map<String, Object> close = new LinkedHashMap<>();
        close.put("button_visible", closeButton);
        close.put("label", RawMessage.translate("gui.close").serialize());
        close.put("onClick", 0.0d);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("title", title.serialize());
        document.put("closeButton", close);
        document.put("layout", layout);
        return document;
    }

    /**
     * Publishes the form and wires every control's writes back to it.
     */
    public void show(ScreenSession session, @Nullable Consumer<CloseReason> onClosed) {
        for (int index = 0; index < components.size(); index++) {
            components.get(index).bind(session, "layout[" + index + "].");
        }
        session.show(serialize(), onClosed);
    }

    public static final class Builder {
        private final RawMessage title;
        private final List<FormComponent> components = new ArrayList<>();
        private boolean closeButton;

        private Builder(RawMessage title) {
            this.title = title;
        }

        /**
         * Draws the screen's own close button. Without it the player can still back out, but there
         * is nothing on screen saying so.
         */
        public Builder closeButton() {
            this.closeButton = true;
            return this;
        }

        public Builder component(FormComponent component) {
            components.add(component);
            return this;
        }

        public Builder button(String label, Runnable action) {
            return component(new FormComponents.Button(RawMessage.text(label), null, false, action));
        }

        public Builder button(RawMessage label, @Nullable RawMessage tooltip, boolean disabled, Runnable action) {
            return component(new FormComponents.Button(label, tooltip, disabled, action));
        }

        public Builder label(String text) {
            return component(new FormComponents.Label(RawMessage.text(text)));
        }

        public Builder header(String text) {
            return component(new FormComponents.Header(RawMessage.text(text)));
        }

        public Builder divider() {
            return component(new FormComponents.Divider());
        }

        public Builder spacer() {
            return component(new FormComponents.Spacer());
        }

        public Builder toggle(String label, boolean toggled) {
            return component(new FormComponents.Toggle(RawMessage.text(label), null, toggled, false));
        }

        public Builder slider(String label, double value, double min, double max, double step) {
            return component(new FormComponents.Slider(RawMessage.text(label), null, value, min, max, step, false));
        }

        public Builder textField(String label, String text) {
            return component(new FormComponents.TextField(RawMessage.text(label), null, text, false));
        }

        public Builder dropdown(String label, List<FormComponents.DropdownItem> items, double value) {
            return component(new FormComponents.Dropdown(RawMessage.text(label), null, items, value, false));
        }

        public DduiCustomForm build() {
            return new DduiCustomForm(title, closeButton, List.copyOf(components));
        }
    }
}
