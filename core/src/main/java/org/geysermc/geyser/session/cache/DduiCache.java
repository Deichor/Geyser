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

package org.geysermc.geyser.session.cache;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.RequiredArgsConstructor;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataDrivenUIReloadPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundDataDrivenScreenClosedPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundDataStorePacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.ddui.DduiScreens;
import org.geysermc.geyser.ddui.ScreenSession;
import org.geysermc.geyser.ddui.ScreenState;
import org.geysermc.geyser.ddui.form.DduiCustomForm;
import org.geysermc.geyser.session.GeyserSession;

import java.util.function.Consumer;

/**
 * The data-driven screens this session has open.
 *
 * <p>A DDUI screen is addressed by its form id in both directions, so this holds the sessions by
 * that id and routes the client's two inbound packets - a datastore write, and the screen closing -
 * to the right one.
 */
@RequiredArgsConstructor
public class DduiCache {

    private final Int2ObjectMap<ScreenSession> screens = new Int2ObjectOpenHashMap<>();
    private final GeyserSession session;

    /**
     * A session against a vanilla screen. Form ids are shared with {@link FormCache} so that an id
     * means one screen on this connection whichever system opened it.
     */
    public ScreenSession newVanillaScreen(String screenId, String propertyPrefix) {
        int formId = session.getFormCache().nextFormId();
        ScreenSession screen = ScreenSession.vanilla(session::sendUpstreamPacket, screenId, propertyPrefix, formId);
        screens.put(formId, screen);
        return track(screen);
    }

    /**
     * A session against a screen supplied by a resource pack, which names its own datastore and
     * property rather than deriving them from a vanilla prefix.
     */
    /**
     * Tells the client to re-read its data-driven screens.
     *
     * The screens a client knows come from the packs it has applied, and a server's pack arrives
     * after that set is first built — so a screen this network defines is not one the client has
     * heard of until it looks again. Nothing reports the difference: an unknown screen id opens an
     * empty screen rather than an error.
     */
    public void reloadScreens() {
        session.sendUpstreamPacket(new ClientboundDataDrivenUIReloadPacket());
        reloaded = true;
        GeyserImpl.getInstance().getLogger().info("DDUI reload sent");
    }

    private boolean reloaded;

    public ScreenSession newPackScreen(String screenId, String dataStore, String propertyPrefix) {
        // Once per session, and only for a screen that cannot be vanilla's.
        if (!reloaded) {
            reloadScreens();
        }
        int formId = session.getFormCache().nextFormId();
        ScreenSession screen = new ScreenSession(session::sendUpstreamPacket, screenId, formId, formId,
                dataStore, propertyPrefix + formId);
        screens.put(formId, screen);
        return track(screen);
    }

    /**
     * Puts a session's traffic in the log when debug mode is on.
     *
     * <p>A screen the client refuses and one it never received look identical from here — an empty
     * window, and nothing said anywhere. This is the only way to see which of the two happened.
     */
    private ScreenSession track(ScreenSession screen) {
        if (GeyserImpl.getInstance().config().debugMode()) {
            screen.debugTo(message -> GeyserImpl.getInstance().getLogger().info("DDUI " + message));
        }
        return screen;
    }

    public void showCustomForm(DduiCustomForm form, @Nullable Consumer<ServerboundDataDrivenScreenClosedPacket.CloseReason> onClosed) {
        ScreenSession screen = newVanillaScreen(DduiScreens.CUSTOM_FORM, DduiScreens.CUSTOM_FORM_PROPERTY_PREFIX);
        form.show(screen, onClosed);
    }

    public boolean hasScreenOpen() {
        return !screens.isEmpty();
    }

    /**
     * A write the client made into a screen's property. It carries no form id, so it is matched on
     * the datastore and property name the screen published under.
     */
    public void handleUpdate(ServerboundDataStorePacket packet) {
        // A probe, not permanent: whether a client keeps reporting presses after the server edits
        // the screen is the one thing no test here can answer.
        GeyserImpl.getInstance().getLogger().info("DDUI <- " + packet.getUpdate().getProperty() + " "
                + packet.getUpdate().getPath() + " = " + packet.getUpdate().getData()
                + " (count " + packet.getUpdate().getUpdateCount() + ")");
        for (ScreenSession screen : screens.values()) {
            if (screen.dataStore().equals(packet.getUpdate().getDataStoreName())
                    && screen.property().equals(packet.getUpdate().getProperty())) {
                try {
                    screen.handleUpdate(packet.getUpdate());
                } catch (Exception e) {
                    GeyserImpl.getInstance().getLogger().error("Error while handling a DDUI datastore update!", e);
                }
                return;
            }
        }
    }

    public void handleClosed(ServerboundDataDrivenScreenClosedPacket packet) {
        Integer formId = packet.getFormId();
        if (formId == null) {
            return;
        }
        ScreenSession screen = screens.remove(formId.intValue());
        if (screen == null) {
            return;
        }
        try {
            screen.handleClosed(packet.getCloseReason());
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().error("Error while closing a DDUI screen!", e);
        }
    }

    /**
     * Asks the client to close every screen this session opened. They stay tracked until the client
     * confirms each one, because only then are their callbacks owed a reason.
     */
    public void closeScreens() {
        for (ScreenSession screen : screens.values()) {
            if (screen.state() == ScreenState.SHOWING) {
                screen.close();
            }
        }
    }
}
