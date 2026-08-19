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
import org.cloudburstmc.protocol.bedrock.data.datastore.DataStoreChange;
import org.cloudburstmc.protocol.bedrock.data.datastore.DataStoreUpdate;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataDrivenUICloseScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataDrivenUIShowScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataStorePacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundDataDrivenScreenClosedPacket.CloseReason;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One data-driven screen shown to one client.
 *
 * <p>A DDUI screen is two things travelling separately: a document in a datastore property, and a
 * request to show a screen id that reads it. They are tied together by the instance id, which is
 * why the property name ends in it. The client then reports changes back into the very same
 * property, which is how a button press arrives - as an update to a number the screen bound.
 *
 * <p>Unlike a classic Bedrock form, a click does not close anything. The screen stays up until the
 * server closes it or the player backs out, which is the entire reason this exists.
 */
public final class ScreenSession {

    /**
     * What a path update carries in the field the codec calls an update count.
     *
     * <p>Measured against a live client, not derived: 1 is applied and 2 and 3 are silently
     * dropped, whether the number rises per path or per property. So it is not the ordering
     * counter it looks like, and treating it as one is what made a live edit land exactly once.
     * A whole-property change still counts up - that is the publish, and it is accepted once.
     */
    private static final int PATH_UPDATE_COUNT = 1;

    private final DduiTransport transport;
    private final String screenId;
    private final int formId;
    private final @Nullable Integer instanceId;
    private final String dataStore;
    private final String property;

    private final Map<String, Consumer<Object>> listeners = new HashMap<>();
    private int updateCount;

    private Map<String, Object> document = new LinkedHashMap<>();
    private ScreenState state = ScreenState.READY;
    private Consumer<String> debug = message -> { };
    private @Nullable Consumer<CloseReason> completion;

    /**
     * A session against a vanilla screen, whose property name is the prefix plus the instance id.
     */
    public static ScreenSession vanilla(DduiTransport transport, String screenId, String propertyPrefix, int formId) {
        return new ScreenSession(transport, screenId, formId, formId, DduiScreens.VANILLA_DATA_STORE,
                propertyPrefix + formId);
    }

    public ScreenSession(DduiTransport transport, String screenId, int formId, @Nullable Integer instanceId,
                         String dataStore, String property) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.screenId = Objects.requireNonNull(screenId, "screenId");
        this.formId = formId;
        this.instanceId = instanceId;
        this.dataStore = Objects.requireNonNull(dataStore, "dataStore");
        this.property = Objects.requireNonNull(property, "property");
    }

    public ScreenState state() {
        return state;
    }

    public int formId() {
        return formId;
    }

    public String screenId() {
        return screenId;
    }

    public String property() {
        return property;
    }

    public String dataStore() {
        return dataStore;
    }

    public Map<String, Object> document() {
        return document;
    }

    /**
     * Runs {@code listener} whenever the client writes to {@code path}. Only paths the screen made
     * client-writable will ever fire.
     */
    public void listen(String path, Consumer<Object> listener) {
        listeners.put(path, listener);
    }

    /**
     * Publishes {@code document} and asks the client to show the screen. The property has to land
     * first: a screen that renders before its data exists reads nulls.
     */
    /**
     * Where this session reports what it puts on the wire.
     *
     * <p>A screen the client refuses looks exactly like one it never received: an empty window and
     * no log line anywhere. Reading back what was actually sent is the only way to tell those apart,
     * so a caller that wants to can ask for it. Off by default; nothing is formatted unless it is.
     */
    public void debugTo(Consumer<String> debug) {
        this.debug = debug;
    }

    public void show(Map<String, Object> document, @Nullable Consumer<CloseReason> completion) {
        if (state != ScreenState.READY) {
            throw new IllegalStateException("A DDUI session can only be shown once (state: " + state + ")");
        }
        this.document = new LinkedHashMap<>(document);
        this.completion = completion;

        publish();

        ClientboundDataDrivenUIShowScreenPacket show = new ClientboundDataDrivenUIShowScreenPacket();
        show.setScreenId(screenId);
        show.setFormId(formId);
        show.setDataInstanceId(instanceId);
        transport.sendDduiPacket(show);
        debug.accept("show screen=" + screenId + " formId=" + formId + " instanceId=" + instanceId);

        state = ScreenState.SHOWING;
    }

    /**
     * Sets a single primitive at {@code path}. The wire only carries a double, a boolean or a
     * string here - anything richer has to go through {@link #republish()}.
     */
    public void set(String path, Object value) {
        set(path, value, null);
    }

    /**
     * As {@link #set(String, Object)}, but sends {@code count} verbatim. Kept for probing; the
     * default is what a live client actually accepts.
     */
    public void set(String path, Object value, @Nullable Integer count) {
        if (!(value instanceof Number || value instanceof Boolean || value instanceof String)) {
            throw new IllegalArgumentException("A datastore path update carries a number, a boolean or a string, not "
                    + (value == null ? "null" : value.getClass().getName()));
        }
        if (!DocumentPath.set(document, path, value)) {
            throw new IllegalArgumentException("No such path in the screen document: " + path);
        }
        if (state != ScreenState.SHOWING) {
            return;
        }

        DataStoreUpdate update = new DataStoreUpdate();
        update.setDataStoreName(dataStore);
        update.setProperty(property);
        update.setPath(path);
        update.setData(value instanceof Number number ? number.doubleValue() : value);
        update.setUpdateCount(count != null ? count : PATH_UPDATE_COUNT);

        ClientboundDataStorePacket packet = new ClientboundDataStorePacket();
        packet.setUpdates(List.of(update));
        transport.sendDduiPacket(packet);
    }

    /**
     * Replaces a whole subtree and resends the property. A {@code DataStoreChange} carries no path,
     * so a structural edit costs the entire document either way.
     */
    public void replace(String path, Object value) {
        if (!DocumentPath.set(document, path, value)) {
            throw new IllegalArgumentException("No such path in the screen document: " + path);
        }
        republish();
    }

    /**
     * Resends the current document as one change.
     *
     * <p>Not a way to edit an open screen: a client that has already taken the property ignores a
     * later change to it. Live edits go through {@link #set}.
     */
    public void republish() {
        if (state == ScreenState.SHOWING) {
            publish();
        }
    }

    private void publish() {
        DataStoreChange change = new DataStoreChange();
        change.setDataStoreName(dataStore);
        change.setProperty(property);
        change.setNewValue(document);
        change.setUpdateCount(nextCount());

        ClientboundDataStorePacket packet = new ClientboundDataStorePacket();
        packet.setUpdates(List.of(change));
        transport.sendDduiPacket(packet);
        debug.accept("publish " + dataStore + "." + property + " count=" + change.getUpdateCount()
                + " value=" + document);
    }

    /**
     * Asks the client to close. The session stays {@link ScreenState#SHOWING} until the client
     * confirms - it is the client that decides when a screen is gone.
     */
    public void close() {
        if (state != ScreenState.SHOWING) {
            return;
        }
        ClientboundDataDrivenUICloseScreenPacket packet = new ClientboundDataDrivenUICloseScreenPacket();
        packet.setFormId(formId);
        transport.sendDduiPacket(packet);
    }

    /**
     * A write the client made into our property.
     */
    public void handleUpdate(DataStoreUpdate update) {
        if (state != ScreenState.SHOWING) {
            return;
        }
        if (!dataStore.equals(update.getDataStoreName()) || !property.equals(update.getProperty())) {
            return;
        }
        // The client counts in the same space; staying above what it last sent keeps our own
        // updates from looking stale to it.
        updateCount = Math.max(updateCount, update.getUpdateCount());
        String path = update.getPath();
        if (!DocumentPath.set(document, path, update.getData())) {
            return;
        }
        Consumer<Object> listener = listeners.get(path);
        if (listener != null) {
            listener.accept(update.getData());
        }
    }

    /**
     * The client reporting the screen gone, whatever the cause.
     */
    public void handleClosed(CloseReason reason) {
        if (state == ScreenState.CLOSED) {
            return;
        }
        state = ScreenState.CLOSED;
        Consumer<CloseReason> callback = completion;
        completion = null;
        if (callback != null) {
            callback.accept(reason);
        }
    }

    private int nextCount() {
        return ++updateCount;
    }
}
