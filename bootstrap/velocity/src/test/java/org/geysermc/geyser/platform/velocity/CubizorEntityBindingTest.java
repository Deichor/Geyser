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

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a backend claims, and what this refuses to be told.
 *
 * Every case below arrives on the transport's thread from a build that is not this one, so none of
 * them may throw: an announcement that took the listener down would cost every later announcement
 * too, and the symptom of that is entities quietly drawn as they always were.
 */
class CubizorEntityBindingTest {

    private static final String PANEL = "lobbymanager:info_panel";
    private static final String BOARD = "lobbymanager:stats_board";

    private CubizorEntityBinding binding() {
        return new CubizorEntityBinding(NOPLogger.NOP_LOGGER, null);
    }

    @Test
    void recordsWhatABackendClaims() {
        var binding = binding();

        binding.accept("lobbymanager", "lobby-1", Map.of("1234", PANEL));

        assertEquals(PANEL, binding.bound().get("lobby-1").get(1234));
    }

    @Test
    void anAnnouncementReplacesOnlyTheSameBackendsOwn() {
        // The whole reason the key carries the server: two pods of one plugin contribute the same
        // pack parts but claim different entities, and keying on the namespace alone would have
        // each pod erase the other's.
        var binding = binding();

        binding.accept("lobbymanager", "lobby-1", Map.of("1234", PANEL));
        binding.accept("lobbymanager", "lobby-2", Map.of("5678", BOARD));

        assertEquals(PANEL, binding.bound().get("lobby-1").get(1234));
        assertEquals(BOARD, binding.bound().get("lobby-2").get(5678));
    }

    @Test
    void twoServersMayUseTheSameEntityIdForDifferentThings() {
        // Why the map is keyed by server rather than flattened. A Java entity id is only unique
        // within one server, so a flat map would draw the lobby's panel over whatever a shard
        // happens to spawn under the same number — for every Bedrock player on that shard.
        var binding = binding();

        binding.accept("lobbymanager", "lobby-1", Map.of("1234", PANEL));
        binding.accept("statsboard", "shard-1", Map.of("1234", BOARD));

        assertEquals(PANEL, binding.bound().get("lobby-1").get(1234));
        assertEquals(BOARD, binding.bound().get("shard-1").get(1234));
    }

    @Test
    void aBackendAnnouncingAgainReplacesItsOwnRatherThanAddingToIt() {
        // The entities a backend claims are rebuilt whenever its world is, under new ids; a map
        // that only grew would keep every id any of those rebuilds ever used.
        var binding = binding();

        binding.accept("lobbymanager", "lobby-1", Map.of("1234", PANEL));
        binding.accept("lobbymanager", "lobby-1", Map.of("5678", PANEL));

        assertEquals(1, binding.bound().get("lobby-1").size());
        assertEquals(PANEL, binding.bound().get("lobby-1").get(5678));
    }

    @Test
    void claimingNothingWithdrawsWhatWasClaimed() {
        var binding = binding();

        binding.accept("lobbymanager", "lobby-1", Map.of("1234", PANEL));
        binding.accept("lobbymanager", "lobby-1", Map.of());

        assertTrue(binding.bound().isEmpty());
    }

    @Test
    void aClaimThatIsNotAnEntityIdIsDroppedRatherThanThrown() {
        var binding = binding();

        binding.accept("lobbymanager", "lobby-1", Map.of("not-an-id", PANEL, "1234", BOARD));

        assertEquals(1, binding.bound().get("lobby-1").size());
        assertEquals(BOARD, binding.bound().get("lobby-1").get(1234));
    }

    @Test
    void aClaimOnAVanillaIdentifierIsRefused() {
        // Swapping an entity for a vanilla identifier redraws that mob for the player, which is not
        // the sort of thing anyone reports as a bug against the plugin that caused it.
        var binding = binding();

        binding.accept("lobbymanager", "lobby-1", Map.of("1234", "minecraft:armor_stand"));

        assertTrue(binding.bound().isEmpty());
    }

    @Test
    void aReadersOwnLanguageIsTriedBeforeThePlainEntity() {
        // Baked art carries its words, so a panel that says anything is one entity per language.
        assertEquals(
                java.util.List.of("lobbymanager:info_panel_tr", "lobbymanager:info_panel"),
                CubizorEntityBinding.candidates("lobbymanager:info_panel", "tr_TR"));
    }

    @Test
    void aClientWithNoLocaleGetsThePlainEntity() {
        assertEquals(
                java.util.List.of("lobbymanager:info_panel"),
                CubizorEntityBinding.candidates("lobbymanager:info_panel", null));
    }

    @Test
    void theFallbackIsThePlainEntityRatherThanADefaultLanguage() {
        // A pack defining only '_en' would otherwise draw English to everyone while looking
        // translated; falling back to the base means a pack with no translations draws whatever
        // the network itself put on the panel.
        assertEquals("lobbymanager:info_panel", CubizorEntityBinding.candidates("lobbymanager:info_panel", "de_DE").getLast());
    }

    @Test
    void nothingIsClaimedUntilSomethingClaimsIt() {
        // An entity nobody named is drawn exactly as it was before this class existed.
        assertTrue(binding().bound().isEmpty());
    }
}
