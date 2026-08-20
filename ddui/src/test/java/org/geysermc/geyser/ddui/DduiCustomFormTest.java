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

import org.cloudburstmc.protocol.bedrock.data.datastore.DataStoreUpdate;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataDrivenUICloseScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataStorePacket;
import org.geysermc.geyser.ddui.form.DduiCustomForm;
import org.geysermc.geyser.ddui.form.FormComponents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DduiCustomFormTest {

    private final RecordingTransport transport = new RecordingTransport();

    private ScreenSession session() {
        return ScreenSession.vanilla(transport, DduiScreens.CUSTOM_FORM, DduiScreens.CUSTOM_FORM_PROPERTY_PREFIX, 1);
    }

    @Test
    void everyComponentCarriesTheMarkerTheScreenSelectsOn() {
        // The vanilla screen picks a control by which <kind>_visible flag is set, not by a type
        // field. A missing marker draws nothing, and a wrong one draws the wrong control.
        DduiCustomForm form = DduiCustomForm.builder("Market")
                .header("Header")
                .label("Label")
                .divider()
                .spacer()
                .button("Sell", () -> { })
                .toggle("Toggle", false)
                .slider("Slider", 1, 0, 10, 1)
                .textField("Field", "")
                .dropdown("Pick", List.of(new FormComponents.DropdownItem(RawMessage.text("One"), 0, null)), 0)
                .build();

        Map<String, Object> layout = layout(form);
        List<String> markers = List.of("header", "label", "divider", "spacer", "button", "toggle", "slider",
                "textfield", "dropdown");
        for (int index = 0; index < markers.size(); index++) {
            Map<?, ?> component = (Map<?, ?>) layout.get(Integer.toString(index));
            assertEquals(Boolean.TRUE, component.get(markers.get(index) + "_visible"),
                    "component " + index + " should be a " + markers.get(index));
        }
    }

    @Test
    void theLayoutPublishesItsOwnLength() {
        // The screen iterates on length, not on the keys. A layout without it renders as empty even
        // though every component is present.
        DduiCustomForm form = DduiCustomForm.builder("Market").label("a").label("b").build();

        assertEquals(2, layout(form).get("length"));
    }

    @Test
    void theCloseButtonIsOffUnlessAskedFor() {
        Map<?, ?> hidden = (Map<?, ?>) DduiCustomForm.builder("Market").build().serialize().get("closeButton");
        Map<?, ?> shown = (Map<?, ?>) DduiCustomForm.builder("Market").closeButton().build().serialize()
                .get("closeButton");

        assertEquals(Boolean.FALSE, hidden.get("button_visible"));
        assertEquals(Boolean.TRUE, shown.get("button_visible"));
    }

    @Test
    void aButtonPressRunsItsActionAndLeavesTheScreenOpen() {
        // This is the whole point of DDUI over a classic form: the client writes a number into the
        // datastore instead of answering and closing.
        AtomicInteger pressed = new AtomicInteger();
        DduiCustomForm form = DduiCustomForm.builder("Market")
                .label("Item")
                .button("Sell", pressed::incrementAndGet)
                .build();
        ScreenSession session = session();
        form.show(session, null);
        transport.clear();

        session.handleUpdate(clientWrite(session, "layout[1].onClick", 1.0d));

        assertEquals(1, pressed.get());
        assertEquals(ScreenState.SHOWING, session.state());
        assertTrue(transport.of(ClientboundDataDrivenUICloseScreenPacket.class).isEmpty(),
                "a press must not close the screen");
    }

    @Test
    void inputStateFollowsTheClient() {
        FormComponents.Toggle toggle = new FormComponents.Toggle(RawMessage.text("Confirm"), null, false, false);
        FormComponents.Slider slider = new FormComponents.Slider(RawMessage.text("Amount"), null, 1, 1, 64, 1, false);
        FormComponents.TextField field = new FormComponents.TextField(RawMessage.text("Note"), null, "", false);
        FormComponents.Dropdown dropdown = new FormComponents.Dropdown(RawMessage.text("Pick"), null,
                List.of(new FormComponents.DropdownItem(RawMessage.text("One"), 0, null)), 0, false);
        DduiCustomForm form = DduiCustomForm.builder("Market")
                .component(toggle).component(slider).component(field).component(dropdown)
                .build();
        ScreenSession session = session();
        form.show(session, null);

        session.handleUpdate(clientWrite(session, "layout[0].toggled", true));
        session.handleUpdate(clientWrite(session, "layout[1].value", 32.0d));
        session.handleUpdate(clientWrite(session, "layout[2].text", "hello"));
        session.handleUpdate(clientWrite(session, "layout[3].value", 1.0d));

        assertTrue(toggle.toggled());
        assertEquals(32.0d, slider.value());
        assertEquals("hello", field.text());
        assertEquals(1.0d, dropdown.value());
    }

    @Test
    void aLiveEditReachesTheClientAsOneUpdate() {
        // Editing an open screen is the reason for this module; it must not resend the document.
        DduiCustomForm form = DduiCustomForm.builder("Market").label("Item").build();
        ScreenSession session = session();
        form.show(session, null);
        transport.clear();

        session.set("layout[0].visible", false);

        List<ClientboundDataStorePacket> packets = transport.of(ClientboundDataStorePacket.class);
        assertEquals(1, packets.size());
        DataStoreUpdate update = (DataStoreUpdate) packets.get(0).getUpdates().get(0);
        assertEquals("layout[0].visible", update.getPath());
        assertEquals(false, update.getData());
        assertFalse((Boolean) DocumentPath.get(session.document(), "layout[0].visible"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> layout(DduiCustomForm form) {
        return (Map<String, Object>) form.serialize().get("layout");
    }

    private static DataStoreUpdate clientWrite(ScreenSession session, String path, Object value) {
        DataStoreUpdate update = new DataStoreUpdate();
        update.setDataStoreName(session.dataStore());
        update.setProperty(session.property());
        update.setPath(path);
        update.setData(value);
        return update;
    }
}
