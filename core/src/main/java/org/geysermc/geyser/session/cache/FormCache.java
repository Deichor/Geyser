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
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundCloseFormPacket;
import org.cloudburstmc.protocol.bedrock.packet.ModalFormRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ModalFormResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerSettingsResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.form.impl.FormDefinitions;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.entity.attribute.GeyserAttributeType;
import org.geysermc.geyser.session.GeyserSession;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@RequiredArgsConstructor
public class FormCache {
    /**
     * The magnitude of this doesn't actually matter, but it must be negative so that
     * BedrockNetworkStackLatencyTranslator can detect the hack.
     */
    private static final long MAGIC_FORM_IMAGE_HACK_TIMESTAMP = -1234567890L;

    private final FormDefinitions formDefinitions = FormDefinitions.instance();
    private final AtomicInteger formIdCounter = new AtomicInteger(0);
    private final Int2ObjectMap<Form> forms = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectMap<Consumer<String>> rawForms = new Int2ObjectOpenHashMap<>();
    private final GeyserSession session;

    public boolean hasFormOpen() {
        // If forms is empty it implies that there are no forms to show
        // so technically this returns "has forms to show" or "has open"
        // Forms are only queued in specific circumstances, such as waiting on
        // previous inventories to close
        return !forms.isEmpty() || !rawForms.isEmpty();
    }

    public int addForm(Form form) {
        int formId = nextFormId();
        forms.put(formId, form);
        return formId;
    }

    /**
     * Hands out the next form id on this connection. Shared with {@link DduiCache} so that a form
     * id identifies one screen regardless of which system opened it.
     */
    public int nextFormId() {
        return formIdCounter.getAndIncrement();
    }

    public void showForm(Form form) {
        int formId = addForm(form);

        if (session.getUpstream().isInitialized()) {
            sendForm(formId, form);
        }
    }

    private void sendForm(int formId, Form form) {
        String jsonData = formDefinitions.codecFor(form).jsonData(form);

        ModalFormRequestPacket formRequestPacket = new ModalFormRequestPacket();
        formRequestPacket.setFormId(formId);
        formRequestPacket.setFormData(jsonData);
        session.sendUpstreamPacket(formRequestPacket);

        // Hack to fix the (url) image loading bug
        if (form instanceof SimpleForm) {
            // Two delays:
            // First, 500ms, before we send the network stack latency packet
            session.scheduleInEventLoop(() -> session.sendNetworkLatencyStackPacket(MAGIC_FORM_IMAGE_HACK_TIMESTAMP, false, () -> {
                    // Then, wait 500ms after we receive the response, then update attributes to get the image to show
                    session.scheduleInEventLoop(() -> {
                        // Hack to fix the url image loading bug
                        UpdateAttributesPacket attributesPacket = new UpdateAttributesPacket();
                        attributesPacket.setRuntimeEntityId(session.getPlayerEntity().geyserId());

                        AttributeData attribute = session.getPlayerEntity().getAttributes().get(GeyserAttributeType.EXPERIENCE_LEVEL);
                        if (attribute != null) {
                            attributesPacket.setAttributes(Collections.singletonList(attribute));
                        } else {
                            attributesPacket.setAttributes(Collections.singletonList(GeyserAttributeType.EXPERIENCE_LEVEL.getAttribute(0)));
                        }

                        session.sendUpstreamPacket(attributesPacket);
                    }, 500, TimeUnit.MILLISECONDS);
                }), 500, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Sends a form a backend serialised itself, and answers it there rather than through Cumulus.
     *
     * <p>An update travels as {@link ServerSettingsResponsePacket} instead of
     * {@link ModalFormRequestPacket}: that is the packet the client applies to a form it already
     * has, so the content is replaced without the screen closing. Each send carries a fresh id and
     * the client answers with whichever one it last received, which is why a response clears every
     * pending raw form rather than just its own.
     */
    public int sendRawForm(String json, boolean update, Consumer<String> onResponse) {
        int formId = nextFormId();
        rawForms.put(formId, onResponse);
        if (update) {
            ServerSettingsResponsePacket packet = new ServerSettingsResponsePacket();
            packet.setFormId(formId);
            packet.setFormData(json);
            session.sendUpstreamPacket(packet);
        } else {
            ModalFormRequestPacket packet = new ModalFormRequestPacket();
            packet.setFormId(formId);
            packet.setFormData(json);
            session.sendUpstreamPacket(packet);
        }
        return formId;
    }

    /**
     * Replaces the content of the form the client already has open.
     *
     * <p>A {@link ServerSettingsResponsePacket} is applied to a form that is already on screen,
     * where a {@link ModalFormRequestPacket} would close it and open a new one. There is no
     * separate update packet, so this is what a caller has to send to change a form in place.
     *
     * <p>The new content is given its own form id and the client answers with that one, so the
     * form it replaces is dropped here rather than left to be resent or answered.
     */
    public void updateForm(Form form) {
        forms.clear();
        int formId = addForm(form);

        ServerSettingsResponsePacket packet = new ServerSettingsResponsePacket();
        packet.setFormId(formId);
        packet.setFormData(formDefinitions.codecFor(form).jsonData(form));
        session.sendUpstreamPacket(packet);
    }

    public void resendAllForms() {
        for (Int2ObjectMap.Entry<Form> entry : forms.int2ObjectEntrySet()) {
            sendForm(entry.getIntKey(), entry.getValue());
        }
    }

    public void handleResponse(ModalFormResponsePacket response) {
        Consumer<String> raw = rawForms.remove(response.getFormId());
        if (raw != null) {
            rawForms.clear();
            raw.accept(response.getFormData());
            return;
        }

        int formId = response.getFormId();
        Form form = forms.get(formId);
        if (form == null) {
            return;
        }

        // Left in the map while its handler runs. A handler that answers a press by replacing the
        // screen calls updateForm, which refuses when nothing is open - and removing the form up
        // front made that true of every press, so an in-place answer to a button was impossible and
        // the only way back was to close the screen and open another.
        //
        // updateForm clears the map and re-adds under a fresh id, so the removal below finds
        // nothing and leaves the replacement alone.
        try {
            formDefinitions.definitionFor(form)
                    .handleFormResponse(form, response.getFormData());
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().error("Error while processing form response!", e);
        } finally {
            forms.remove(formId, form);
        }
    }

    public void closeForms() {
        this.rawForms.clear();
        if (!this.forms.isEmpty()) {
            // Copy them to ensure any response handler's sent form isn't instantly cleared
            Int2ObjectMap<Form> copy = new Int2ObjectOpenHashMap<>(this.forms);
            this.forms.clear();
            // Now close it
            session.sendUpstreamPacket(new ClientboundCloseFormPacket());

            for (Form form : copy.values()) {
                try {
                    formDefinitions.definitionFor(form).handleFormResponse(form, "");
                } catch (Exception e) {
                    GeyserImpl.getInstance().getLogger().error("Error while closing form!", e);
                }
            }
        }
    }
}
