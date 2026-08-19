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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.datastore.DataStoreChange;
import org.cloudburstmc.protocol.bedrock.data.datastore.DataStoreUpdate;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataDrivenUIShowScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataStorePacket;
import org.geysermc.geyser.ddui.form.DduiCustomForm;
import org.geysermc.geyser.ddui.form.FormComponents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The document has to survive the codec, not just look right in a map.
 *
 * <p>A datastore change is written by walking the value tree and picking a type per node, so a
 * value the tree cannot express - a float in a map, an enum, a null - throws at encode time on a
 * live connection. Running a whole form through the real serializer is the only way to find that
 * out here rather than there.
 */
class WireRoundTripTest {

    private static final BedrockCodec CODEC = Bedrock_v2168.CODEC;

    @Test
    void awholeCustomFormSurvivesTheCodec() {
        DduiCustomForm form = DduiCustomForm.builder(RawMessage.translate("cbz.market.title", List.of("Deichor")))
                .closeButton()
                .header("Inventory")
                .divider()
                .button("Sell 1", () -> { })
                .toggle("Confirm", true)
                .slider("Amount", 8, 1, 64, 1)
                .textField("Note", "")
                .dropdown("Currency", List.of(
                        new FormComponents.DropdownItem(RawMessage.text("Coins"), 0, null),
                        new FormComponents.DropdownItem(RawMessage.text("Shards"), 1, RawMessage.text("rare"))), 0)
                .build();

        DataStoreChange change = new DataStoreChange();
        change.setDataStoreName(DduiScreens.VANILLA_DATA_STORE);
        change.setProperty(DduiScreens.CUSTOM_FORM_PROPERTY_PREFIX + 1);
        change.setUpdateCount(1);
        change.setNewValue(form.serialize());

        ClientboundDataStorePacket packet = new ClientboundDataStorePacket();
        packet.setUpdates(List.of(change));

        ClientboundDataStorePacket decoded = roundTrip(packet);
        DataStoreChange result = assertInstanceOf(DataStoreChange.class, decoded.getUpdates().get(0));
        assertEquals(change.getProperty(), result.getProperty());

        Map<?, ?> document = (Map<?, ?>) result.getNewValue();
        Map<?, ?> layout = (Map<?, ?>) document.get("layout");
        // The wire has one integer case, so the layout length comes back as a long.
        assertEquals(7L, layout.get("length"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) layout.get("0")).get("header_visible"));
        assertEquals(Map.of("text", "Note"), ((Map<?, ?>) ((Map<?, ?>) layout.get("5")).get("label")));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) document.get("closeButton")).get("button_visible"));
    }

    @Test
    void aTranslatedTitleKeepsItsSubstitutions() {
        // A rawtext with 'with' is a list nested in an object - the one shape most likely to be
        // flattened by a hand-written encoder.
        DataStoreChange change = new DataStoreChange();
        change.setDataStoreName(DduiScreens.VANILLA_DATA_STORE);
        change.setProperty("p");
        change.setUpdateCount(1);
        change.setNewValue(Map.of("title", RawMessage.translate("k", List.of("a", "b")).serialize()));

        ClientboundDataStorePacket packet = new ClientboundDataStorePacket();
        packet.setUpdates(List.of(change));

        Map<?, ?> document = (Map<?, ?>) ((DataStoreChange) roundTrip(packet).getUpdates().get(0)).getNewValue();
        assertEquals(Map.of("translate", "k", "with", List.of("a", "b")), document.get("title"));
    }

    @Test
    void aPathUpdateKeepsItsTypeAndCount() {
        ScreenSession session = ScreenSession.vanilla(packet -> { }, DduiScreens.CUSTOM_FORM,
                DduiScreens.CUSTOM_FORM_PROPERTY_PREFIX, 3);

        DataStoreUpdate update = new DataStoreUpdate();
        update.setDataStoreName(session.dataStore());
        update.setProperty(session.property());
        update.setPath("layout[2].toggled");
        update.setData(true);
        update.setUpdateCount(9);

        ClientboundDataStorePacket packet = new ClientboundDataStorePacket();
        packet.setUpdates(List.of(update));

        DataStoreUpdate result = assertInstanceOf(DataStoreUpdate.class, roundTrip(packet).getUpdates().get(0));
        assertEquals("layout[2].toggled", result.getPath());
        assertEquals(true, result.getData());
        assertEquals(9, result.getUpdateCount());
    }

    @Test
    void theShowRequestKeepsItsOptionalInstanceId() {
        // The instance id is optional on the wire, and a screen shown without one reads no property
        // at all - so an accidental null has to be visible here.
        ClientboundDataDrivenUIShowScreenPacket packet = new ClientboundDataDrivenUIShowScreenPacket();
        packet.setScreenId(DduiScreens.CUSTOM_FORM);
        packet.setFormId(12);
        packet.setDataInstanceId(12);

        ClientboundDataDrivenUIShowScreenPacket decoded = roundTrip(packet);
        assertEquals(DduiScreens.CUSTOM_FORM, decoded.getScreenId());
        assertEquals(12, decoded.getFormId());
        assertEquals(12, decoded.getDataInstanceId());
    }

    @SuppressWarnings("unchecked")
    private static <T extends BedrockPacket> T roundTrip(T packet) {
        BedrockCodecHelper helper = CODEC.createHelper();
        ByteBuf buffer = Unpooled.buffer();
        try {
            CODEC.tryEncode(helper, buffer, packet);
            int id = CODEC.getPacketDefinition((Class<T>) packet.getClass()).getId();
            return (T) CODEC.tryDecode(helper, buffer, id);
        } finally {
            buffer.release();
        }
    }
}
