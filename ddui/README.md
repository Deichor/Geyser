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

The field the codec calls an update count is not an ordering counter, whatever it looks like.
Measured against a live client: a path update carrying 1 is applied, and 2 and 3 are silently
dropped — whether the number rises per path or per property. `ScreenSession` therefore pins it to 1
for path updates. A whole-property change counts up normally, but only the first one lands: once a
client has taken a property, a later change to it is ignored, so **live edits only work through a
path update**.

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

| `image_visible` | `visible, image_src, image_width, image_pack, image_onClick, image_clickable, image_tooltip` |

The image row has no equivalent in any published API — it is in the screen the client ships, and
`image_pack` plus `image_src` name a texture in a resource pack, which is how a DDUI screen shows
art that is not Minecraft's own.

`minecraft:message_box`: `{ title, body, button1: {label, tooltip, onClick}, button2: {...} }`.

## Screens supplied by a resource pack

A pack defines a screen at `<pack>/ddui/root/<name>.json`:

```json
{
  "format_version": "1.21.130",
  "minecraft:ui-root": {
    "description": { "identifier": "example:my_screen" },
    "attribs": { "root_point": "minecraft:screen" },
    "layout": { "markup": [
      { "component": "Context",
        "attribs": { "data": { "$path": "$.example.my_screen_data_{instanceId}" } },
        "children": [ ... ] }
    ] }
  }
}
```

`description.identifier` is the screen id sent in `ClientboundDataDrivenUIShowScreenPacket`. The
root `Context` names the datastore and property it reads, with `{instanceId}` substituted from the
show packet — which is what ties the two packets together. Inside it, `$relativePath` addresses
fields of that property.

Components: `Context`, `Context.List`, `Panel`, `Panel.Text`, `Panel.Spacing`, `Panel.Decoration`,
`Panel.CloseButton`, `Visibility`, `Container.Layout`, `Container.Slot`, `ScrollableGridLayout`,
`Form.Button`, `Form.Divider`, `Form.Dropdown`, `Form.Image`, `Form.ScrollView`, `Form.Slider`,
`Form.Switch`, `Form.TextField`.

A binding ends in an accessor: `.getString`, `.getNumber`, `.getBoolean`, `.action` (fires and
leaves the screen open) or `.closeAction` (fires and closes it).

## Provenance

The packet layouts come from the Bedrock codec's own serializers. The screen documents come from
the client's own resource pack, at `assets/resource_packs/vanilla_1.21.130/ddui/root/` — which is
the only place they exist: the dedicated server bundle does not ship them, `Mojang/bedrock-samples`
carries only JSON UI, and Microsoft documents the scripting API rather than the screens.

None of that is a published specification, so `WireRoundTripTest` runs a whole form through the
real codec rather than trusting the shapes by eye, and the update count is pinned to what a live
client was measured to accept rather than to what the field name suggests.
