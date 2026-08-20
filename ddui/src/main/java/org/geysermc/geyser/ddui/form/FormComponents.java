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
import org.geysermc.geyser.ddui.RawMessage;
import org.geysermc.geyser.ddui.ScreenSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The controls the vanilla {@code minecraft:custom_form} screen knows how to draw.
 *
 * <p>The field names below are the screen's contract, not ours: a typo produces a control that
 * renders with a default value instead of an error, so they are written out literally.
 */
public final class FormComponents {

    private FormComponents() {
    }

    private static Map<String, Object> base(String kind, boolean visible) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(kind + "_visible", true);
        map.put("visible", visible);
        return map;
    }

    /** A button that fires its action and leaves the screen open. */
    public static final class Button implements FormComponent {
        private final RawMessage label;
        private final @Nullable RawMessage tooltip;
        private final boolean disabled;
        private final Runnable action;

        public Button(RawMessage label, @Nullable RawMessage tooltip, boolean disabled, Runnable action) {
            this.label = label;
            this.tooltip = tooltip;
            this.disabled = disabled;
            this.action = action;
        }

        @Override
        public Map<String, Object> serialize() {
            Map<String, Object> map = base("button", true);
            map.put("disabled", disabled);
            map.put("label", label.serialize());
            map.put("tooltip", RawMessage.serialize(tooltip));
            map.put("onClick", 0.0d);
            return map;
        }

        @Override
        public void bind(ScreenSession session, String path) {
            session.listen(path + "onClick", value -> action.run());
        }
    }

    public static final class Label implements FormComponent {
        private final RawMessage text;

        public Label(RawMessage text) {
            this.text = text;
        }

        @Override
        public Map<String, Object> serialize() {
            Map<String, Object> map = base("label", true);
            map.put("text", text.serialize());
            return map;
        }

        @Override
        public void bind(ScreenSession session, String path) {
        }
    }

    public static final class Header implements FormComponent {
        private final RawMessage text;

        public Header(RawMessage text) {
            this.text = text;
        }

        @Override
        public Map<String, Object> serialize() {
            Map<String, Object> map = base("header", true);
            map.put("text", text.serialize());
            return map;
        }

        @Override
        public void bind(ScreenSession session, String path) {
        }
    }

    public static final class Divider implements FormComponent {
        @Override
        public Map<String, Object> serialize() {
            return base("divider", true);
        }

        @Override
        public void bind(ScreenSession session, String path) {
        }
    }

    public static final class Spacer implements FormComponent {
        @Override
        public Map<String, Object> serialize() {
            return base("spacer", true);
        }

        @Override
        public void bind(ScreenSession session, String path) {
        }
    }

    public static final class Toggle implements FormComponent {
        private final RawMessage label;
        private final @Nullable RawMessage description;
        private final boolean disabled;
        private boolean toggled;

        public Toggle(RawMessage label, @Nullable RawMessage description, boolean toggled, boolean disabled) {
            this.label = label;
            this.description = description;
            this.toggled = toggled;
            this.disabled = disabled;
        }

        public boolean toggled() {
            return toggled;
        }

        @Override
        public Map<String, Object> serialize() {
            Map<String, Object> map = base("toggle", true);
            map.put("disabled", disabled);
            map.put("label", label.serialize());
            map.put("description", RawMessage.serialize(description));
            map.put("toggled", toggled);
            return map;
        }

        @Override
        public void bind(ScreenSession session, String path) {
            session.listen(path + "toggled", value -> toggled = value instanceof Boolean bool && bool);
        }
    }

    public static final class Slider implements FormComponent {
        private final RawMessage label;
        private final @Nullable RawMessage description;
        private final double min;
        private final double max;
        private final double step;
        private final boolean disabled;
        private double value;

        public Slider(RawMessage label, @Nullable RawMessage description, double value, double min, double max,
                      double step, boolean disabled) {
            this.label = label;
            this.description = description;
            this.value = value;
            this.min = min;
            this.max = max;
            this.step = step;
            this.disabled = disabled;
        }

        public double value() {
            return value;
        }

        @Override
        public Map<String, Object> serialize() {
            Map<String, Object> map = base("slider", true);
            map.put("disabled", disabled);
            map.put("label", label.serialize());
            map.put("description", RawMessage.serialize(description));
            map.put("value", value);
            map.put("minValue", min);
            map.put("maxValue", max);
            map.put("step", step);
            return map;
        }

        @Override
        public void bind(ScreenSession session, String path) {
            session.listen(path + "value", update -> {
                if (update instanceof Number number) {
                    value = number.doubleValue();
                }
            });
        }
    }

    public static final class TextField implements FormComponent {
        private final RawMessage label;
        private final @Nullable RawMessage description;
        private final boolean disabled;
        private String text;

        public TextField(RawMessage label, @Nullable RawMessage description, String text, boolean disabled) {
            this.label = label;
            this.description = description;
            this.text = text;
            this.disabled = disabled;
        }

        public String text() {
            return text;
        }

        @Override
        public Map<String, Object> serialize() {
            Map<String, Object> map = base("textfield", true);
            map.put("disabled", disabled);
            map.put("label", label.serialize());
            map.put("description", RawMessage.serialize(description));
            map.put("text", text);
            return map;
        }

        @Override
        public void bind(ScreenSession session, String path) {
            session.listen(path + "text", update -> {
                if (update instanceof String string) {
                    text = string;
                }
            });
        }
    }

    /** One entry of a {@link Dropdown}. */
    public record DropdownItem(RawMessage label, double value, @Nullable RawMessage description) {
    }

    public static final class Dropdown implements FormComponent {
        private final RawMessage label;
        private final @Nullable RawMessage description;
        private final List<DropdownItem> items;
        private final boolean disabled;
        private double value;

        public Dropdown(RawMessage label, @Nullable RawMessage description, List<DropdownItem> items, double value,
                        boolean disabled) {
            this.label = label;
            this.description = description;
            this.items = List.copyOf(items);
            this.value = value;
            this.disabled = disabled;
        }

        public double value() {
            return value;
        }

        /**
         * The items are keyed by decimal index and carry their own {@code length}, the same shape
         * the layout itself uses.
         */
        private Map<String, Object> serializeItems() {
            Map<String, Object> serialized = new LinkedHashMap<>();
            for (int index = 0; index < items.size(); index++) {
                DropdownItem item = items.get(index);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("label", item.label().serialize());
                entry.put("value", item.value());
                entry.put("description", RawMessage.serialize(item.description()));
                serialized.put(Integer.toString(index), entry);
            }
            serialized.put("length", items.size());
            return serialized;
        }

        @Override
        public Map<String, Object> serialize() {
            Map<String, Object> map = base("dropdown", true);
            map.put("disabled", disabled);
            map.put("label", label.serialize());
            map.put("description", RawMessage.serialize(description));
            map.put("value", value);
            map.put("items", serializeItems());
            return map;
        }

        @Override
        public void bind(ScreenSession session, String path) {
            session.listen(path + "value", update -> {
                if (update instanceof Number number) {
                    value = number.doubleValue();
                }
            });
        }
    }
}
