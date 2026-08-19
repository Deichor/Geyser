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

package org.geysermc.geyser.platform.velocity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.ddui.DduiScreens;
import org.geysermc.geyser.ddui.ScreenSession;
import org.geysermc.geyser.session.GeyserSession;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Lets a backend open a DDUI screen on a Bedrock player it cannot reach itself.
 *
 * <p>Geyser terminates the Bedrock session here, so a shard has no connection to drive a screen
 * with - it only knows the player's Floodgate UUID. This forwards a screen document over a plugin
 * message and sends the client's writes back the same way, keyed by a reference the backend chose
 * so it never has to learn the proxy's form ids.
 *
 * <p>This belongs in a plugin of its own rather than inside Geyser; it lives here for now because
 * one jar is one deployment.
 */
public final class DduiChannel {

    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("cubizor", "ddui");

    /** How long a client is given to rebuild its screen set before being asked for one. */
    private static final long RELOAD_SETTLE_MILLIS = 5000L;

    private final ProxyServer proxy;
    private final Logger logger;

    public DduiChannel(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    public void register(Object plugin) {
        proxy.getChannelRegistrar().register(CHANNEL);
        proxy.getEventManager().register(plugin, this);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        // A screen request is ours to act on, never something to pass through to the client.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection)) {
            return;
        }
        try {
            handle(JsonParser.parseString(new String(event.getData(), StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Exception e) {
            logger.warn("Malformed DDUI request from a backend", e);
        }
    }

    private void handle(JsonObject request) {
        String op = request.get("op").getAsString();
        UUID uuid = UUID.fromString(request.get("player").getAsString());
        String ref = request.get("ref").getAsString();

        GeyserSession session = sessionOf(uuid);
        if (session == null) {
            reply(uuid, error(ref, "no Geyser session for " + uuid));
            return;
        }
        session.executeInEventLoop(() -> {
            try {
                switch (op) {
                    case "open" -> open(session, uuid, ref, request);
                    case "set" -> set(uuid, ref, request);
                    case "refresh" -> refresh(uuid, ref);
                    case "close" -> close(uuid, ref);
                    case "form" -> form(session, uuid, ref, request);
                    default -> reply(uuid, error(ref, "unknown op " + op));
                }
            } catch (Exception e) {
                logger.warn("Failed to run DDUI op '{}' for {}", op, uuid, e);
                reply(uuid, error(ref, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        });
    }

    /**
     * The screens this proxy has open, keyed by player and the backend's own reference - a ref is
     * only unique to the backend that chose it.
     */
    private final Map<String, ScreenSession> open = new ConcurrentHashMap<>();

    private static String key(UUID uuid, String ref) {
        return uuid + "/" + ref;
    }

    private void open(GeyserSession session, UUID uuid, String ref, JsonObject request) {
        String screenId = request.get("screen").getAsString();
        boolean packScreen = request.has("dataStore");
        ScreenSession screen;
        if (packScreen) {
            // The property name ends in the instance id, because that is what the screen's root
            // context substitutes - so the two are derived together rather than passed separately.
            screen = session.getDduiCache().newPackScreen(screenId, request.get("dataStore").getAsString(),
                    request.get("propertyPrefix").getAsString());
        } else {
            screen = session.getDduiCache().newVanillaScreen(screenId, propertyPrefixFor(screenId));
        }
        open.put(key(uuid, ref), screen);

        if (request.has("listen")) {
            for (JsonElement path : request.getAsJsonArray("listen")) {
                String bound = path.getAsString();
                screen.listen(bound, value -> reply(uuid, event(ref, bound, value)));
            }
        }

        Map<String, Object> document = object(request.getAsJsonObject("document"));
        Runnable show = () -> screen.show(document, reason -> {
            open.remove(key(uuid, ref));
            reply(uuid, closed(ref, reason.name()));
        });

        if (packScreen) {
            // A reload rebuilds the screen set from every applied pack, and that is not instant.
            // Asking for the screen in the same breath races it, and losing that race looks exactly
            // like a screen that does not exist: an empty window and no error.
            logger.info("DDUI -> reloaded, showing {} shortly", screenId);
            session.scheduleInEventLoop(show, RELOAD_SETTLE_MILLIS, TimeUnit.MILLISECONDS);
        } else {
            show.run();
        }
    }

    private void set(UUID uuid, String ref, JsonObject request) {
        ScreenSession screen = open.get(key(uuid, ref));
        if (screen == null) {
            logger.info("DDUI -> no open screen for ref {}", ref);
            return;
        }
        String path = request.get("path").getAsString();
        Object value = value(request.get("value").getAsJsonPrimitive());
        Integer count = request.has("count") ? request.get("count").getAsInt() : null;
        logger.info("DDUI -> set {} = {} (count {})", path, value, count);
        screen.set(path, value, count);
    }

    /** Resends the whole property, which is the one shape the client is known to accept. */
    private void refresh(UUID uuid, String ref) {
        ScreenSession screen = open.get(key(uuid, ref));
        if (screen != null) {
            logger.info("DDUI -> refresh {}", ref);
            screen.republish();
        }
    }

    /**
     * Sends a JSON-UI form the backend serialised, or replaces the one it already has open.
     *
     * <p>A form is not a data-driven screen, but it rides this channel because a backend already
     * has a route here and one proxy handler is one deployment. Cumulus is not involved: the
     * backend owns the payload and gets the raw response back.
     */
    private void form(GeyserSession session, UUID uuid, String ref, JsonObject request) {
        boolean update = request.has("update") && request.get("update").getAsBoolean();
        String payload = request.get("form").getAsString();
        logger.info("DDUI -> form {} ({})", ref, update ? "update" : "open");
        session.getFormCache().sendRawForm(payload, update, response -> reply(uuid, formResponse(ref, response)));
    }

    private void close(UUID uuid, String ref) {
        ScreenSession screen = open.get(key(uuid, ref));
        if (screen != null) {
            screen.close();
        }
    }

    /**
     * A vanilla screen's property is named after the screen; a pack screen names its own, which is
     * why that case carries it explicitly instead of coming through here.
     */
    private static String propertyPrefixFor(String screenId) {
        return switch (screenId) {
            case DduiScreens.MESSAGE_BOX -> DduiScreens.MESSAGE_BOX_PROPERTY_PREFIX;
            default -> DduiScreens.CUSTOM_FORM_PROPERTY_PREFIX;
        };
    }

    private static GeyserSession sessionOf(UUID uuid) {
        return GeyserImpl.getInstance().getSessionManager().getSessions().get(uuid);
    }

    private void reply(UUID uuid, JsonObject message) {
        Optional<Player> player = proxy.getPlayer(uuid);
        if (player.isEmpty()) {
            return;
        }
        Optional<ServerConnection> server = player.get().getCurrentServer();
        if (server.isEmpty()) {
            return;
        }
        server.get().sendPluginMessage(CHANNEL, message.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject reply(String op, String ref) {
        JsonObject message = new JsonObject();
        message.addProperty("op", op);
        message.addProperty("ref", ref);
        return message;
    }

    private static JsonObject event(String ref, String path, Object value) {
        JsonObject message = reply("event", ref);
        message.addProperty("path", path);
        if (value instanceof Number number) {
            message.addProperty("value", number);
        } else if (value instanceof Boolean bool) {
            message.addProperty("value", bool);
        } else {
            message.addProperty("value", String.valueOf(value));
        }
        return message;
    }

    /** A cancelled form answers with no data at all, which is not the same as an empty response. */
    private static JsonObject formResponse(String ref, String data) {
        JsonObject message = reply("formResponse", ref);
        if (data != null) {
            message.addProperty("data", data);
        }
        return message;
    }

    private static JsonObject closed(String ref, String reason) {
        JsonObject message = reply("closed", ref);
        message.addProperty("reason", reason);
        return message;
    }

    private static JsonObject error(String ref, String detail) {
        JsonObject message = reply("error", ref);
        message.addProperty("message", detail);
        return message;
    }

    /**
     * JSON to the value tree the datastore encodes.
     *
     * <p>A whole number stays a long rather than becoming a double: the wire has a case for each
     * and a layout length that arrives fractional is not the same value to the client.
     */
    private static Map<String, Object> object(JsonObject json) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            map.put(entry.getKey(), element(entry.getValue()));
        }
        return map;
    }

    private static Object element(JsonElement json) {
        if (json.isJsonObject()) {
            return object(json.getAsJsonObject());
        }
        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            List<Object> items = new ArrayList<>(array.size());
            for (JsonElement item : array) {
                items.add(element(item));
            }
            return items;
        }
        if (json.isJsonNull()) {
            return null;
        }
        return value(json.getAsJsonPrimitive());
    }

    private static Object value(JsonPrimitive primitive) {
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isString()) {
            return primitive.getAsString();
        }
        BigDecimal number = primitive.getAsBigDecimal();
        return number.scale() <= 0 ? (Object) number.longValue() : (Object) number.doubleValue();
    }
}
