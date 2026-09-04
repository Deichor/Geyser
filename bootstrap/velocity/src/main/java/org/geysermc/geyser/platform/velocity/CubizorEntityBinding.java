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

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.cubizor.proxybridge.api.message.BedrockEntityBindingMessage;
import net.cubizor.proxybridge.api.message.BedrockEntityBindingRequestMessage;
import net.cubizor.proxybridge.api.model.message.ProxyMessageListener;
import net.cubizor.proxybridge.api.service.MessageService;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.definition.GeyserEntityDefinition;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.java.ServerSpawnEntityEvent;
import org.geysermc.geyser.api.util.Identifier;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Draws the entities a backend claims as its own the way that backend meant, rather than the way
 * Geyser would draw them.
 *
 * <p>It exists for the one thing a nametag cannot do. Bedrock draws no text in the world except a
 * nametag, and a nametag always turns to face its reader — so a Java text display, which Geyser
 * sends as an invisible armor stand wearing one, swivels at a passer-by however fixed its billboard
 * was set. A backend that has put real geometry in the network's pack claims the entity standing
 * where that geometry belongs, and this is what swaps the one for the other.
 *
 * <p><b>Nothing is translated on its own.</b> An entity nobody claimed is drawn exactly as it was
 * before this class existed. That is deliberate: the plugins on this network hang holograms for all
 * sorts of things — an NPC's nameplate, a countdown over a pad — and most of them are *supposed* to
 * turn to their reader. Only what a backend explicitly names is touched.
 *
 * <p><b>Why a claim is made ahead of the spawn.</b> {@link ServerSpawnEntityEvent} carries the
 * entity's id, its uuid and its Java type, and that is all — the text has not arrived and neither
 * has the position, because the definition has to be chosen before the entity is built. So the
 * backend names its own entities in advance, and this looks them up.
 *
 * <p><b>Keyed by the Java entity id, paired with the server.</b> Not by uuid: the holograms this
 * exists for are packet-level entities, so on the backend there is no Bukkit entity to take a uuid
 * from and the claim would be silently empty. An id is only unique within one server, so the pair
 * is the key and {@link #serverOf} is what supplies the other half.
 *
 * <p>Claims are replaced wholesale per backend rather than accumulated: the entities a backend
 * claims are rebuilt whenever its world is, under new ids each time, and a map that only grew would
 * keep every id any of those rebuilds ever used.
 */
public final class CubizorEntityBinding implements EventRegistrar {
    private final Logger logger;

    /**
     * An entity identifier, as {@code namespace:path} and never in the vanilla namespace.
     *
     * Checked here rather than by building an {@link Identifier}, because that resolves through
     * {@code GeyserApi.api()} — a static that throws until Geyser has registered itself. Reading a
     * message that arrived on the transport's thread must not depend on that, so identifiers are
     * carried as text and only become an {@link Identifier} on the spawn that uses one.
     */
    private static final Pattern IDENTIFIER = Pattern.compile("(?!minecraft:)[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** What each backend claims, by "namespace/server", so an announcement replaces its own. */
    private final Map<String, Map<Integer, String>> claims = new LinkedHashMap<>();

    /**
     * Every claim flattened <b>per server</b>, swapped wholesale on each announcement.
     *
     * Read on the spawn of every entity of every Bedrock session, written a handful of times a day.
     * Replacing the whole map means a reader never holds a lock and never sees a half-built one.
     *
     * <p>Keyed by server and not flat, because a Java entity id is only unique within one server:
     * the lobby's entity 1234 and a shard's entity 1234 are unrelated, and a flat map would draw
     * one server's panel over whatever the other happens to spawn under the same number.
     */
    private volatile Map<String, Map<Integer, String>> bound = Map.of();

    /** Identifiers already reported as unregistered, so one missing entity is not one log line per spawn. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** Velocity, asked at every spawn which backend the connection is on. @see #serverOf */
    private final ProxyServer proxy;

    public CubizorEntityBinding(Logger logger, ProxyServer proxy) {
        this.logger = logger;
        this.proxy = proxy;
    }

    /** Starts listening for spawns. Claims arrive later; until they do, nothing is swapped. */
    public void start() {
        GeyserApi.api().eventBus().register(this, this);
    }

    /**
     * Listens for what the backends claim, through a bridge somebody else already waited for, then
     * asks them to claim it again.
     *
     * <p>Asking is not optional. A backend announces its claims when it starts, which covers a
     * backend that restarts; it does not cover this proxy restarting, because the panels on those
     * servers have been standing since long before this process existed and nothing over there
     * knows it is talking to a new listener. Without the ask, every claim is lost on a proxy
     * deploy and every Bedrock player is quietly served nametags again.
     *
     * @param messages the bridge's message service, once there is one
     * @param proxyId  what to call this proxy when asking, so a backend can say who it answered
     */
    public void attach(MessageService messages, String proxyId) {
        messages.registerListener(new ProxyMessageListener<>(BedrockEntityBindingMessage.Companion) {
            @Override
            public void onMessage(BedrockEntityBindingMessage message) {
                accept(message.getNamespace(), message.getServer(), message.getBindings());
            }
        });

        messages.sendMessage(new BedrockEntityBindingRequestMessage(proxyId));
    }

    /**
     * Records what one backend claims, replacing whatever it claimed before.
     *
     * An id that does not parse, or an identifier in the vanilla namespace, is dropped with a line
     * naming the sender rather than thrown: this runs on the transport's thread, and one malformed
     * claim is not a reason to lose the rest of that announcement or the announcements after it.
     */
    synchronized void accept(String namespace, String server, Map<String, String> bindings) {
        String key = namespace + "/" + server;
        Map<Integer, String> parsed = new HashMap<>(bindings.size());

        bindings.forEach((id, identifier) -> {
            int entity;
            try {
                entity = Integer.parseInt(id);
            } catch (NumberFormatException malformed) {
                logger.warn("Ignoring a claim from '{}': '{}' is not an entity id", key, id);
                return;
            }
            if (!IDENTIFIER.matcher(identifier).matches()) {
                // The vanilla namespace is refused with the malformed ones, and for a louder reason
                // than tidiness: swapping an entity for a vanilla identifier would redraw that mob
                // for the player, which nobody reports as a bug against the plugin that caused it.
                logger.warn("Ignoring a claim from '{}': '{}' is not an entity identifier this may draw", key, identifier);
                return;
            }
            parsed.put(entity, identifier);
        });

        if (parsed.isEmpty()) {
            claims.remove(key);
        } else {
            claims.put(key, parsed);
        }

        Map<String, Map<Integer, String>> byServer = new HashMap<>();
        claims.forEach((owner, entities) ->
                byServer.computeIfAbsent(owner.substring(owner.indexOf('/') + 1), unused -> new HashMap<>())
                        .putAll(entities));
        bound = Map.copyOf(byServer);

        logger.info("'{}' claims {} entities; {} server(s) claim anything on this proxy",
                key, parsed.size(), bound.size());
    }

    /** Visible for tests: what would be swapped right now, by the server the entities live on. */
    Map<String, Map<Integer, String>> bound() {
        return bound;
    }

    /**
     * The identifiers to try for a claim, in order: the reader's own language, then the plain one.
     *
     * <p>Baked art carries its words, so a panel that says anything has one texture per language and
     * therefore one entity per language. Which is why the claim names a base rather than an exact
     * entity: the backend does not know who will read it, and this event is fired per connection,
     * which is the first moment anybody does.
     *
     * <p>Falling back to the base rather than to a default language is deliberate. A pack that
     * defines only {@code lobbymanager:info_panel} is a panel with no words on it, or one in the
     * network's own language, and drawing that to a Turkish reader is right. A pack that defines
     * {@code _en} and nothing else would otherwise draw English to everyone while looking like it
     * had been translated.
     *
     * @param locale a Bedrock client locale, as {@code en_US}
     */
    static List<String> candidates(String base, String locale) {
        String language = locale == null ? "" : locale.split("_", 2)[0].toLowerCase(Locale.ROOT);
        if (language.isEmpty()) {
            return List.of(base);
        }
        return List.of(base + "_" + language, base);
    }

    /**
     * Swaps the Bedrock definition of an entity a backend claimed.
     *
     * An identifier the pack has not registered is left alone rather than forced. That is the shape
     * of a claim arriving before the pack that draws it — the definitions are registered once while
     * Geyser starts, out of the pack already on disk, so an entity added to the pack today is
     * spawnable after the next restart. Drawing it as the text display it already is, in the
     * meantime, is the better of the two wrong answers.
     */
    @Subscribe
    public void onSpawnEntity(ServerSpawnEntityEvent event) {
        if (bound.isEmpty()) {
            return;
        }

        String server = serverOf(event);
        if (server == null) {
            // Rate-limited by the same set the missing-definition warning uses. Without it a claim
            // that can never match looks exactly like a backend that never claimed anything.
            if (warned.add("<unknown server>")) {
                logger.warn("Cannot tell which backend a Bedrock session is on, so no claim can be matched; "
                        + "{} server(s) have claimed entities", bound.size());
            }
            return;
        }

        Map<Integer, String> onThisServer = bound.get(server);
        if (onThisServer == null) {
            if (warned.add("server:" + server)) {
                logger.warn("Nothing is claimed on '{}'; claims are held for {}", server, bound.keySet());
            }
            return;
        }

        String claimed = onThisServer.get(event.entityId());
        if (claimed == null) {
            // One line per server, not per spawn. Without it the case where a claim is held but no
            // id ever matches it is the one branch here that says nothing at all — and it looks
            // exactly like a backend that claimed nothing.
            if (warned.add("ids:" + server + ":" + event.entityId())) {
                logger.warn("Saw entity {} ({}) on '{}' with no claim; ids claimed there are {}",
                        event.entityId(), event.entityType(), server, onThisServer.keySet());
            }
            return;
        }

        for (String identifier : candidates(claimed, event.connection().locale())) {
            GeyserEntityDefinition definition = CustomEntityDefinition.of(Identifier.of(identifier));
            if (definition.registered()) {
                event.definition(definition);
                // Once per entity. The swap is otherwise invisible from this side: what it produces
                // is a client drawing something else, and a claim that matched nothing looks the
                // same in every log as one that was never made.
                if (warned.add("drew:" + server + ":" + event.entityId())) {
                    logger.info("Drawing entity {} on '{}' as '{}' for a {} reader",
                            event.entityId(), server, identifier, event.connection().locale());
                }
                return;
            }
        }

        if (warned.add(claimed)) {
            logger.warn(
                    "A backend claims entities as '{}', which the pack this proxy serves defines in no language; "
                            + "they stay as they are until a pack that defines it has been composed",
                    claimed);
        }
    }

    /**
     * Which backend the spawning entity belongs to, or null when that cannot be said.
     *
     * <p>Asked of Velocity rather than of Geyser, because the entity id in the spawn only means
     * anything alongside the server that issued it, and the connection is the only thing that knows
     * which one that is. The backend names itself the same way — ProxyBridge registers a server
     * under the name Velocity knows it by — so the two halves of the claim meet on one string.
     */
    private String serverOf(ServerSpawnEntityEvent event) {
        return proxy.getPlayer(event.connection().javaUuid())
                .flatMap(Player::getCurrentServer)
                .map(connected -> connected.getServerInfo().getName())
                .orElse(null);
    }
}
