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

import org.cloudburstmc.protocol.bedrock.data.datastore.DataStoreAction;
import org.cloudburstmc.protocol.bedrock.data.datastore.DataStoreChange;
import org.cloudburstmc.protocol.bedrock.data.datastore.DataStoreUpdate;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataDrivenUICloseScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataDrivenUIShowScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataStorePacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundDataDrivenScreenClosedPacket.CloseReason;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenSessionTest {

    private final RecordingTransport transport = new RecordingTransport();

    private ScreenSession session() {
        return ScreenSession.vanilla(transport, DduiScreens.CUSTOM_FORM, DduiScreens.CUSTOM_FORM_PROPERTY_PREFIX, 7);
    }

    private static Map<String, Object> document() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("title", "Market");
        document.put("count", 0.0d);
        return document;
    }

    @Test
    void theDocumentIsPublishedBeforeTheScreenIsAskedFor() {
        // A screen that renders before its property exists reads nothing, and the failure looks
        // like an empty menu rather than an error.
        session().show(document(), null);

        List<BedrockPacket> sent = transport.sent();
        assertEquals(2, sent.size());
        assertTrue(sent.get(0) instanceof ClientboundDataStorePacket, "the datastore goes first");
        assertTrue(sent.get(1) instanceof ClientboundDataDrivenUIShowScreenPacket, "then the screen");
    }

    @Test
    void theScreenAndItsPropertyAgreeOnTheInstanceId() {
        // The two packets are only tied together by this number: the screen reads the property whose
        // name ends in the instance id it was shown with.
        ScreenSession session = session();
        session.show(document(), null);

        ClientboundDataDrivenUIShowScreenPacket show = transport.of(ClientboundDataDrivenUIShowScreenPacket.class).get(0);
        assertEquals(DduiScreens.CUSTOM_FORM, show.getScreenId());
        assertEquals(7, show.getFormId());
        assertEquals(7, show.getDataInstanceId());
        assertEquals(DduiScreens.CUSTOM_FORM_PROPERTY_PREFIX + 7, session.property());
        assertEquals(DduiScreens.VANILLA_DATA_STORE, session.dataStore());
    }

    @Test
    void theUpdateCountRisesAcrossEveryPathAtOnce() {
        // Counting per path made an edit land once and never again: two paths each sent a 1, and a
        // client comparing per property reads the second as stale. One rising number is correct
        // under either reading.
        ScreenSession session = session();
        session.show(document(), null);
        transport.clear();

        session.set("count", 1.0d);
        session.set("title", "Ledger");
        session.set("count", 2.0d);

        List<Integer> counts = transport.of(ClientboundDataStorePacket.class).stream()
                .map(packet -> ((DataStoreUpdate) packet.getUpdates().get(0)).getUpdateCount())
                .toList();
        assertEquals(List.of(2, 3, 4), counts, "the publish took 1");
    }

    @Test
    void theCountStaysAheadOfWhatTheClientReported() {
        // The client counts in the same space. An update that repeats a number it has already used
        // is the failure this guards: it is dropped, and the screen silently stops updating.
        ScreenSession session = session();
        session.show(document(), null);

        DataStoreUpdate fromClient = update(session, "count", 9.0d);
        fromClient.setUpdateCount(41);
        session.handleUpdate(fromClient);
        transport.clear();

        session.set("title", "Ledger");

        DataStoreUpdate sent = (DataStoreUpdate) transport.of(ClientboundDataStorePacket.class)
                .get(0).getUpdates().get(0);
        assertEquals(42, sent.getUpdateCount());
    }

    @Test
    void aPathUpdateOnlyCarriesAPrimitive() {
        // The wire has three cases here - a double, a boolean and a string. Anything else has to go
        // back through the whole property, and finding that out at runtime costs a disconnect.
        ScreenSession session = session();
        session.show(document(), null);

        assertThrows(IllegalArgumentException.class, () -> session.set("title", Map.of("text", "Market")));
    }

    @Test
    void aPathTheDocumentNeverPublishedIsRefused() {
        ScreenSession session = session();
        session.show(document(), null);

        assertThrows(IllegalArgumentException.class, () -> session.set("titel", "typo"));
    }

    @Test
    void aStructuralEditResendsTheWholeProperty() {
        // A change carries no path, so there is no such thing as a partial structural update.
        ScreenSession session = session();
        session.show(document(), null);
        transport.clear();

        session.replace("title", Map.of("text", "Ledger"));

        List<ClientboundDataStorePacket> packets = transport.of(ClientboundDataStorePacket.class);
        assertEquals(1, packets.size());
        DataStoreAction action = packets.get(0).getUpdates().get(0);
        assertTrue(action instanceof DataStoreChange);
        Map<?, ?> published = (Map<?, ?>) ((DataStoreChange) action).getNewValue();
        assertEquals(Map.of("text", "Ledger"), published.get("title"));
    }

    @Test
    void aWriteFromTheClientReachesOnlyItsOwnPathListener() {
        ScreenSession session = session();
        AtomicReference<Object> seen = new AtomicReference<>();
        AtomicInteger other = new AtomicInteger();
        session.listen("count", seen::set);
        session.listen("title", value -> other.incrementAndGet());
        session.show(document(), null);

        session.handleUpdate(update(session, "count", 4.0d));

        assertEquals(4.0d, seen.get());
        assertEquals(0, other.get());
        assertEquals(4.0d, DocumentPath.get(session.document(), "count"));
    }

    @Test
    void aWriteMeantForAnotherScreenIsIgnored() {
        // Two screens can be open at once and neither packet carries a form id, so the property name
        // is the only thing separating them.
        ScreenSession session = session();
        AtomicReference<Object> seen = new AtomicReference<>();
        session.listen("count", seen::set);
        session.show(document(), null);

        DataStoreUpdate update = update(session, "count", 4.0d);
        update.setProperty(DduiScreens.CUSTOM_FORM_PROPERTY_PREFIX + 9);
        session.handleUpdate(update);

        assertNull(seen.get());
    }

    @Test
    void askingToCloseDoesNotCloseTheSession() {
        // The client owes us a confirmation either way; treating the request as the answer leaks a
        // callback that never fires and a screen id that is reused too early.
        ScreenSession session = session();
        AtomicReference<CloseReason> closed = new AtomicReference<>();
        session.show(document(), closed::set);

        session.close();

        assertEquals(ScreenState.SHOWING, session.state());
        assertNull(closed.get());
        assertEquals(1, transport.of(ClientboundDataDrivenUICloseScreenPacket.class).size());
        assertEquals(7, transport.of(ClientboundDataDrivenUICloseScreenPacket.class).get(0).getFormId());

        session.handleClosed(CloseReason.PROGRAMMATIC_CLOSE);

        assertEquals(ScreenState.CLOSED, session.state());
        assertSame(CloseReason.PROGRAMMATIC_CLOSE, closed.get());
    }

    @Test
    void theCompletionRunsOnce() {
        ScreenSession session = session();
        AtomicInteger closed = new AtomicInteger();
        session.show(document(), reason -> closed.incrementAndGet());

        session.handleClosed(CloseReason.CLIENT_CANCELED);
        session.handleClosed(CloseReason.CLIENT_CANCELED);

        assertEquals(1, closed.get());
    }

    @Test
    void aScreenIsOnlyShownOnce() {
        ScreenSession session = session();
        session.show(document(), null);

        assertThrows(IllegalStateException.class, () -> session.show(document(), null));
    }

    private static DataStoreUpdate update(ScreenSession session, String path, Object value) {
        DataStoreUpdate update = new DataStoreUpdate();
        update.setDataStoreName(session.dataStore());
        update.setProperty(session.property());
        update.setPath(path);
        update.setData(value);
        return update;
    }
}
