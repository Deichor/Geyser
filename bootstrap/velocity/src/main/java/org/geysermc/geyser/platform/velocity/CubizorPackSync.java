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

import net.cubizor.carbon.bedrock.ui.cubizor.CubizorBedrockPack;
import net.cubizor.carbon.bedrock.ui.pack.PackContribution;
import net.cubizor.carbon.bedrock.ui.pack.PackContributionCodec;
import net.cubizor.carbon.bedrock.ui.pack.PackSyncStore;
import net.cubizor.proxybridge.api.ProxyBridgeAPI;
import net.cubizor.proxybridge.api.message.ResourcePackContributionMessage;
import net.cubizor.proxybridge.api.message.ResourcePackContributionRequestMessage;
import net.cubizor.proxybridge.api.model.message.ProxyMessageListener;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.option.ResourcePackOption;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.GeyserApi;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Composes the network's Bedrock pack here, out of what the backends announce, instead of taking a
 * file somebody uploaded.
 *
 * A Bedrock client takes one pack per file, so the network serves a single one; but the servers that
 * have something to put in it are elsewhere, and the pack has to be assembled somewhere both. Here
 * is the only place that is also where the pack is handed to a client.
 *
 * <p>Everything difficult about that lives in {@link PackSyncStore}, which is where it can be
 * tested: composition is deterministic, and the version moves only when the contents do — otherwise
 * a proxy restart would cost every Bedrock player a download of a pack nobody changed. This class is
 * the part that cannot be tested without a proxy: when to ask, when to settle, and what to hand a
 * connecting client.
 *
 * <p>Announcements are debounced rather than settled on arrival. At boot the backends answer within
 * a moment of each other, and composing per answer would publish a version per backend for a pack
 * that ends up identical to yesterday's.
 */
public final class CubizorPackSync implements EventRegistrar {
    /**
     * How long to wait after the last announcement before composing.
     *
     * Long enough that a boot's worth of answers lands in one settle, short enough that a backend
     * enabling on its own is serving within seconds. Every announcement pushes it out again.
     */
    private static final long SETTLE_DELAY_MILLIS = 3_000L;

    private final Logger logger;
    private final PackSyncStore store;
    private final ScheduledExecutorService scheduler;

    private ScheduledFuture<?> pending;
    private ResourcePack served;

    public CubizorPackSync(Path configFolder, Logger logger) {
        this(CubizorBedrockPack.syncStore(configFolder.resolve("pack")), logger,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "cubizor-pack-sync");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    /** Visible for tests: composing and timing are what a test wants to drive itself. */
    CubizorPackSync(PackSyncStore store, Logger logger, ScheduledExecutorService scheduler) {
        this.store = store;
        this.logger = logger;
        this.scheduler = scheduler;
    }

    /** Stops the settle timer. Whatever was composed stays on disk and is served again next boot. */
    public void stop() {
        scheduler.shutdownNow();
    }

    /** Visible for tests: which namespaces have been accepted so far. */
    java.util.List<String> contributors() {
        return store.contributors();
    }

    /**
     * Serves whatever was composed last time this proxy ran, then asks the network to speak up.
     *
     * The order matters. A proxy that waited for answers before serving anything would hand the
     * first players to connect no pack at all; serving the last known one first means a restart is
     * invisible unless something actually changed.
     *
     * @param proxyId what to call this proxy when asking, so a backend can say who it answered
     */
    public void start(String proxyId) {
        reload();
        GeyserApi.api().eventBus().register(this, this);

        // Everything past here needs ProxyBridge, and a proxy without it simply keeps serving what
        // it composed before — which on a first run is nothing at all. Not fatal: a pack is not
        // what makes Bedrock work, only what makes it ours.
        ProxyBridgeAPI bridge;
        try {
            bridge = ProxyBridgeAPI.Companion.instance();
        } catch (IllegalStateException notReady) {
            logger.warn("ProxyBridge is not up, so backends cannot announce their pack contributions");
            return;
        }

        bridge.messageService().registerListener(new ProxyMessageListener<>(ResourcePackContributionMessage.Companion) {
            @Override
            public void onMessage(ResourcePackContributionMessage message) {
                accept(message.getNamespace(), message.getPayload());
            }
        });

        // Asked once, and asked at all because announcing on enable only covers a backend
        // restarting: the servers already running have no reason to tell a proxy they never met
        // what they told the one it replaced.
        bridge.messageService().sendMessage(new ResourcePackContributionRequestMessage(proxyId));
    }

    /** Records one backend's contribution and puts the settle off a little longer. */
    public void accept(String namespace, byte[] payload) {
        PackContribution contribution;
        try {
            contribution = PackContributionCodec.decode(payload);
        } catch (Exception failure) {
            // Exception, not RuntimeException: the codec reads a stream, so a truncated payload
            // arrives as an IOException — and Kotlin declares no checked exceptions, so nothing
            // made that visible here. Caught narrowly it went straight through and into the
            // transport's listener thread.
            // A contribution this build cannot read is that server's problem, not the pack's: the
            // rest still compose, and saying whose it was is the only way anyone finds out.
            logger.warn("Ignoring the pack contribution from '{}': {}", namespace, failure.getMessage());
            return;
        }

        if (!contribution.getNamespace().equals(namespace)) {
            logger.warn(
                    "A pack contribution announced as '{}' calls itself '{}'; ignoring it",
                    namespace, contribution.getNamespace());
            return;
        }

        store.submit(contribution);
        scheduleSettle();
    }

    private synchronized void scheduleSettle() {
        if (pending != null) {
            pending.cancel(false);
        }
        try {
            pending = scheduler.schedule(this::settle, SETTLE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            // The proxy is going down and something announced on the way out. Dropping it is right:
            // what is on disk is still correct, and this would otherwise be thrown at whichever
            // thread the transport delivers on.
            logger.debug("A pack contribution arrived after shutdown; it will be asked for again next boot");
        }
    }

    private synchronized void settle() {
        PackSyncStore.Outcome outcome;
        try {
            outcome = store.settle();
        } catch (RuntimeException failure) {
            logger.error("Composing the Bedrock pack failed; the pack already being served stays up", failure);
            return;
        }

        if (outcome instanceof PackSyncStore.Outcome.Unchanged) {
            logger.info("Bedrock pack unchanged at version {}; nobody is asked to download it again",
                    ((PackSyncStore.Outcome.Unchanged) outcome).getServed().getVersion());
            return;
        }
        if (outcome instanceof PackSyncStore.Outcome.Rejected rejected) {
            // Deliberately not fatal. A contribution that cannot be composed with the others must
            // not take down a pack that was fine a moment ago.
            logger.error("Refused to serve a Bedrock pack composed from {}: {}",
                    store.contributors(), rejected.getFailure().getMessage());
            return;
        }

        PackSyncStore.Outcome.Updated updated = (PackSyncStore.Outcome.Updated) outcome;
        reload();
        logger.info("Serving Bedrock pack {} composed from {} (sha256 {})",
                updated.getServed().getVersion(), store.contributors(), updated.getSha256());
    }

    /** Reads whatever is on disk into the pack handed to connecting clients. */
    private void reload() {
        PackSyncStore.Served current = store.served();
        if (current == null) {
            return;
        }
        try {
            served = ResourcePack.create(PackCodec.path(current.getFile().toPath()));
        } catch (RuntimeException failure) {
            logger.error("Geyser refused the composed Bedrock pack", failure);
        }
    }

    /**
     * Offers the composed pack to a connecting client.
     *
     * Per session rather than once at startup, and that is what makes a late backend reachable at
     * all: a contribution that arrives after the proxy is up is served to whoever connects next,
     * without a restart.
     */
    @Subscribe
    public void onLoadResourcePacks(SessionLoadResourcePacksEvent event) {
        ResourcePack pack = served;
        if (pack != null) {
            event.register(pack, new ResourcePackOption<?>[0]);
        }
    }
}
