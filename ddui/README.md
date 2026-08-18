# geyser-ddui

Data-driven UI (DDUI) screens, driven from the server.

DDUI is the Ore UI-rendered screen system the Bedrock client ships with. Unlike a classic Bedrock
form, a click on a DDUI screen does not answer and close it — the client writes a value into a
datastore property and the screen stays up. That is the whole reason this module exists: a menu can
be edited while the player is looking at it.

## Wire contract

The packets and their serializers already live in the Bedrock codec Geyser depends on; nothing here
touches bytes.

| Packet | Direction | Carries |
|---|---|---|
| `ClientboundDataStorePacket` | S→C | a list of update / change / removal actions |
| `ClientboundDataDrivenUIShowScreenPacket` | S→C | `screenId`, `formId`, optional `dataInstanceId` |
| `ClientboundDataDrivenUICloseScreenPacket` | S→C | optional `formId` (absent closes all) |
| `ClientboundDataDrivenUIReloadPacket` | S→C | nothing |
| `ServerboundDataStorePacket` | C→S | one update — the client writing into a property |
| `ServerboundDataDrivenScreenClosedPacket` | C→S | `formId`, close reason |

A `DataStoreChange` replaces a whole property and can carry any tree (map, list, string, long,
double, bool, null). A `DataStoreUpdate` targets one path but only carries a double, a boolean or a
string — so a structural edit costs the whole property either way.

Both directions are ordered by an update count. It is tracked per path, because a property keeps a
publisher slot per path and a stale count is dropped rather than applied out of order.

## Screen and property

A screen and its data travel in separate packets, tied together only by the instance id: a screen
shown with `dataInstanceId = 7` reads the property whose name ends in `7`. The vanilla screens are:

| Screen id | Datastore | Property |
|---|---|---|
| `minecraft:custom_form` | `minecraft` | `custom_form_data_<instanceId>` |
| `minecraft:message_box` | `minecraft` | `message_box_data_<instanceId>` |

A screen supplied by a resource pack works the same way with its own id, datastore and property,
which is why `ScreenSession` takes all three rather than deriving them.

## Document shape

`minecraft:custom_form`:

```
{
  title:       <rawtext>,
  closeButton: { button_visible, label, onClick: 0.0 },
  layout:      { "0": <component>, "1": <component>, ..., length: <n> }
}
```

A component is selected by a `<kind>_visible` marker, not by a type field:

| Marker | Fields |
|---|---|
| `button_visible` | `visible, disabled, label, tooltip, onClick` |
| `label_visible` / `header_visible` | `visible, text` |
| `divider_visible` / `spacer_visible` | `visible` |
| `toggle_visible` | `visible, disabled, label, description, toggled` |
| `slider_visible` | `visible, disabled, label, description, value, minValue, maxValue, step` |
| `textfield_visible` | `visible, disabled, label, description, text` |
| `dropdown_visible` | `visible, disabled, label, description, value, items` |

Paths are addressed as `layout[3].label`, even though the layout is an object keyed by the decimal
index. `onClick` is a number the client increments; a press arrives as a write to that path.

`minecraft:message_box`: `{ title, body, button1: {label, tooltip, onClick}, button2: {...} }`.

## Provenance

The screen ids, property prefixes, document shapes and binding paths above were read off
[LeviLamina](https://github.com/LiteLDev/LeviLamina)'s DDUI implementation, which drives the same
screens from inside the vanilla server. The packet layouts come from the Bedrock codec's own
serializers. Neither is a published specification, so `WireRoundTripTest` runs a whole form through
the real codec rather than trusting the shapes by eye.
