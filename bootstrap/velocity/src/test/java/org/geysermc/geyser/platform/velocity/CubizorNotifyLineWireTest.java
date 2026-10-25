package org.geysermc.geyser.platform.velocity;

import net.cubizor.carbon.bedrock.ui.pack.ScreenMarkers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.geysermc.geyser.translator.text.MessageTranslator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a Bedrock notification line (TitanUI {@code NotifyWire}, sent by CommandsHelper) still
 * carries its markers once this proxy has turned it into a Bedrock chat line, so the pack's card
 * tray and its chat hiding match it.
 *
 * The pack tests a line with JSON-UI string subtraction, {@code (#chat_text - 'm') = #chat_text},
 * which is false exactly when the text contains {@code m}; {@link #matches} is that test.
 *
 * In game (2026-10-25) the first markers, runs of § codes like the screen markers, arrived as one
 * {@code §k§l§o}: somewhere on the chat path the line is parsed as legacy text before this proxy
 * serialises it, and a parse folds a run of codes into its canonical form. So the line is checked
 * both as sent and after such a parse.
 */
class CubizorNotifyLineWireTest {

    /** TitanUI NotifyWire.MARK, NotifyWire.TAP and NotifyKind.SALE.mark, pinned there too. */
    private static final String MARK = "|tn|";
    private static final String TAP = "|tntap|";
    private static final String SALE = "|tnsale|";

    private static final String LINE = MARK + SALE + TAP + "§lPAZAR§r\nElmas satıldı · +1.2k";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** JSON-UI's {@code not ((text - marker) = text)}. */
    private static boolean matches(String text, String marker) {
        return !text.replace(marker, "").equals(text);
    }

    @Test
    void theMarkersArriveAsSent() {
        String onWire = MessageTranslator.convertMessage(Component.text(LINE));
        for (String marker : List.of(MARK, SALE, TAP)) {
            assertTrue(matches(onWire, marker), marker + " lost: " + readable(onWire));
        }
    }

    @Test
    void theMarkersSurviveALegacyParseOnTheWay() {
        String onWire = MessageTranslator.convertMessage(LEGACY.deserialize(LINE));
        for (String marker : List.of(MARK, SALE, TAP)) {
            assertTrue(matches(onWire, marker), marker + " lost: " + readable(onWire));
        }
        // what the card's label shows: every marker taken out, the bold title and the body left
        String label = onWire.replace(MARK, "").replace(SALE, "").replace(TAP, "");
        assertTrue(label.contains("PAZAR") && label.contains("Elmas satıldı"), readable(label));
    }

    @Test
    void aMarkerOfFormattingCodesDoesNotSurviveThatParse() {
        // why the markers are plain text: the first ones were codes, and this is what happened to them
        String codes = ScreenMarkers.INSTANCE.of("titan", "notify");
        String onWire = MessageTranslator.convertMessage(LEGACY.deserialize(codes + "§lPAZAR"));
        assertFalse(matches(onWire, codes), readable(onWire));
    }

    private static String readable(String value) {
        return value.replace('§', '#').replace("\n", "|");
    }
}
