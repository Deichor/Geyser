package org.geysermc.geyser.platform.velocity;

import net.cubizor.carbon.bedrock.ui.pack.ScreenMarkers;
import net.kyori.adventure.text.Component;
import org.geysermc.geyser.translator.text.MessageTranslator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a screen's marker still reads as one string once this proxy has rewritten the title.
 *
 * The pack matches a form by containment, so a marker only works if it arrives uninterrupted. This
 * proxy does not pass a title through: {@link MessageTranslator#convertMessage(Component)} inserts a
 * reset before a colour code that follows other formatting, because Bedrock does not clear
 * formatting on a colour the way Java does. A marker spelled from colours therefore leaves a backend
 * whole and arrives cut into pieces, and every screen on the network falls back to Mojang's plain
 * list with nothing in any log.
 *
 * That is not hypothetical - it is what the first derived alphabet did. This is the side that does
 * the rewriting, so this is where it is pinned.
 */
class CubizorScreenMarkerWireTest {

    /** Every screen on the network, by the namespace and key its marker is derived from. */
    private static final List<String[]> SCREENS = List.of(
            new String[]{"carbon", "card-grid"},
            new String[]{"carbon", "history"},
            new String[]{"carbon", "inventory-grid"},
            new String[]{"auction", "listing-board"},
            new String[]{"auction", "bid"},
            new String[]{"auction", "item-picker"},
            new String[]{"auction", "start-price"},
            new String[]{"shop", "catalogue"},
            new String[]{"shop", "bag"},
            new String[]{"shop", "history"},
            new String[]{"bounty", "board"},
            new String[]{"profile", "panel"},
            new String[]{"profile", "settings"}
    );

    @Test
    void everyMarkerArrivesInOnePiece() {
        for (String[] screen : SCREENS) {
            String marker = ScreenMarkers.INSTANCE.of(screen[0], screen[1]);
            String title = ScreenMarkers.INSTANCE.title(screen[0], screen[1], "Menu");

            String onWire = MessageTranslator.convertMessage(Component.text(title));

            assertTrue(onWire.contains(marker),
                    screen[0] + "/" + screen[1] + " left as " + readable(title)
                            + " and arrived as " + readable(onWire));
        }
    }

    @Test
    void noMarkerClaimsAnotherScreen() {
        // Containment, not equality: the gates test by string subtraction, so a marker inside another
        // claims two screens at once and the client draws them stacked, reporting nothing.
        List<String> markers = SCREENS.stream()
                .map(screen -> ScreenMarkers.INSTANCE.of(screen[0], screen[1]))
                .toList();

        assertTrue(ScreenMarkers.INSTANCE.overlapping(markers) == null,
                "two screens on this network claim markers one of which contains the other");
    }

    /** A marker is invisible on purpose, which makes a failure unreadable without this. */
    private static String readable(String value) {
        return value.replace('§', '#');
    }
}
