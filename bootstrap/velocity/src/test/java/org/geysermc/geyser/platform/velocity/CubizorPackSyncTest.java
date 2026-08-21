package org.geysermc.geyser.platform.velocity;

import net.cubizor.carbon.bedrock.ui.JsonObject;
import net.cubizor.carbon.bedrock.ui.cubizor.CubizorBedrockPack;
import net.cubizor.carbon.bedrock.ui.pack.FormScreen;
import net.cubizor.carbon.bedrock.ui.pack.PackContribution;
import net.cubizor.carbon.bedrock.ui.pack.PackContributionCodec;
import net.cubizor.carbon.bedrock.ui.pack.PackSyncStore;
import net.cubizor.carbon.bedrock.ui.pack.Texture;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the proxy-side adapter has to refuse.
 *
 * Composing, versioning and validation are the store's, and are tested where they live. What is
 * this class's own is the edge it sits on: bytes arriving from another server, announced by a name
 * this proxy has no way to check against the payload. A contribution that cannot be read, or that
 * calls itself something other than what it was announced as, must not reach the pack — and must not
 * take the other servers' contributions down with it either.
 */
class CubizorPackSyncTest {

    /**
     * A scheduler that accepts a settle and runs nothing in its place.
     *
     * When it composes is not what these are about, and a real delay would make each of them wait
     * three seconds to find that out. Accepting the schedule matters though: a terminated executor
     * rejects instead, which is a different thing to be testing by accident.
     */
    private static ScheduledExecutorService idle() {
        return new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                return super.schedule(() -> { }, delay, unit);
            }
        };
    }

    private static PackContribution contribution(String namespace) {
        return new PackContribution() {
            @Override
            public String getNamespace() {
                return namespace;
            }

            @Override
            public Map<String, JsonObject> controls() {
                return Collections.emptyMap();
            }

            @Override
            public Map<String, JsonObject> screens() {
                return Collections.emptyMap();
            }

            @Override
            public List<Texture> textures() {
                return Collections.emptyList();
            }
        };
    }

    private static CubizorPackSync sync() throws Exception {
        // The real store over the real pack: these are about what reaches it, so what it composes
        // should be what a proxy would actually compose.
        Path directory = Files.createTempDirectory("pack-sync-adapter");
        PackSyncStore store = CubizorBedrockPack.syncStore(directory);
        return new CubizorPackSync(store, NOPLogger.NOP_LOGGER, idle());
    }

    @Test
    void anAnnouncementIsAccepted() throws Exception {
        CubizorPackSync sync = sync();

        sync.accept("shop", PackContributionCodec.encode(contribution("shop")));

        assertEquals(List.of("shop"), sync.contributors());
    }

    @Test
    void bytesThatDoNotDecodeAreDroppedRatherThanThrown() throws Exception {
        // A backend on a build this proxy does not understand must not stop the others being served.
        CubizorPackSync sync = sync();

        sync.accept("shop", new byte[] {1, 2, 3});

        assertTrue(sync.contributors().isEmpty());
    }

    @Test
    void aContributionThatDisagreesWithItsAnnouncementIsRefused() throws Exception {
        // The namespace is what a contribution is keyed by, so a payload naming a different one
        // would let a server replace somebody else's share of the pack.
        CubizorPackSync sync = sync();

        sync.accept("shop", PackContributionCodec.encode(contribution("seller")));

        assertTrue(sync.contributors().isEmpty());
    }

    @Test
    void oneServerAnnouncingTwiceIsOneContributor() throws Exception {
        CubizorPackSync sync = sync();

        sync.accept("shop", PackContributionCodec.encode(contribution("shop")));
        sync.accept("shop", PackContributionCodec.encode(contribution("shop")));

        assertEquals(List.of("shop"), sync.contributors());
    }

    @Test
    void aBadAnnouncementLeavesTheGoodOnesAlone() throws Exception {
        CubizorPackSync sync = sync();

        sync.accept("shop", PackContributionCodec.encode(contribution("shop")));
        sync.accept("lobby", new byte[] {9});
        sync.accept("hub", PackContributionCodec.encode(contribution("hub")));

        assertEquals(List.of("hub", "shop"), sync.contributors());
    }

    /**
     * A backend's own screen reaches the pack this proxy serves.
     *
     * The route this covers had never run: until the contribution surface existed, a plugin with its
     * own Bedrock layout had nowhere to put it, and until a backend answered the request nothing was
     * announced at all. Both ends now exist and every failure between them is a blank screen on a
     * phone with nothing in any log — so the whole trip is made here, in bytes, exactly as it is
     * made in production.
     */
    @Test
    void weavesAContributedScreenIntoTheFormItServes() throws Exception {
        var screen = new FormScreen("\u00a7r\u00a7c", 520, 312, "shop.board_body", null, null, null, null);
        var contribution = new PackContribution() {
            @Override
            public String getNamespace() {
                return "shop";
            }

            @Override
            public Map<String, JsonObject> controls() {
                return Map.of("board_body", new JsonObject(Map.of()));
            }

            @Override
            public List<FormScreen> formScreens() {
                return List.of(screen);
            }
        };

        // Encoded and decoded, because that is what happens between the shard and here — and the
        // format moved to carry exactly this.
        var arrived = PackContributionCodec.decode(PackContributionCodec.encode(contribution));
        assertEquals(1, arrived.formScreens().size());
        assertEquals(screen.getMarker(), arrived.formScreens().get(0).getMarker());

        var form = CubizorBedrockPack.serverForm(arrived.formScreens());
        var variants = ((net.cubizor.carbon.bedrock.ui.JsonArray) ((JsonObject) form.get("long_form")).get("controls"));

        // One more variant than the pack's own screens, and Mojang's gate widened to name the new
        // marker — a gate that misses one draws Mojang's dialog underneath ours.
        assertEquals(5, variants.getItems().size());
        assertTrue(form.toString().contains(screen.getMarker()));
    }

    /**
     * The bridge being late is not the bridge being absent.
     *
     * Geyser and ProxyBridge are two Velocity plugins with no load-order relationship, and on the
     * network Geyser wins by about a second. A single lookup at boot therefore found nothing,
     * warned, and returned — and the contribution route was dead for the life of that proxy. It
     * went unnoticed for as long as no backend answered the request either.
     */
    @Test
    void waitsForABridgeThatIsMerelyLate() throws Exception {
        var scheduled = new java.util.concurrent.atomic.AtomicInteger();
        var scheduler = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                scheduled.incrementAndGet();
                return super.schedule(() -> { }, 0, TimeUnit.MILLISECONDS);
            }
        };
        var sync = new CubizorPackSync(
                CubizorBedrockPack.syncStore(Files.createTempDirectory("pack-sync-late")),
                NOPLogger.NOP_LOGGER,
                scheduler,
                () -> { throw new IllegalStateException("ProxyBridge is not up"); });

        sync.attach("proxy:25565", 0);

        assertEquals(1, scheduled.get(), "a bridge that is not up yet must be waited for, not given up on");
    }

    /**
     * And a bridge that is genuinely absent is given up on rather than retried forever.
     *
     * A proxy with no ProxyBridge at all is a supported deployment — it keeps serving whatever it
     * composed last time — so the wait is bounded and says so once.
     */
    @Test
    void stopsWaitingForABridgeThatIsNotComing() throws Exception {
        var scheduled = new java.util.concurrent.atomic.AtomicInteger();
        var scheduler = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                scheduled.incrementAndGet();
                return super.schedule(() -> { }, 0, TimeUnit.MILLISECONDS);
            }
        };
        var sync = new CubizorPackSync(
                CubizorBedrockPack.syncStore(Files.createTempDirectory("pack-sync-absent")),
                NOPLogger.NOP_LOGGER,
                scheduler,
                () -> { throw new IllegalStateException("ProxyBridge is not up"); });

        sync.attach("proxy:25565", 30);

        assertEquals(0, scheduled.get(), "the wait is bounded; a proxy with no bridge must not retry forever");
    }
}
