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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.kyori.adventure.key.Key;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.level.block.type.Block;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.registry.JavaRegistries;
import org.geysermc.geyser.session.cache.registry.JavaRegistryKey;
import org.geysermc.geyser.session.cache.tags.GeyserHolderSet;
import org.geysermc.geyser.session.cache.tags.Tag;
import org.geysermc.geyser.util.MinecraftKey;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.HolderSet;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundUpdateTagsPacket;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages information sent from the {@link ClientboundUpdateTagsPacket}. If that packet is not sent, all lists here
 * will remain empty, matching Java Edition behavior. Looking up a tag that wasn't listed in that packet will return an empty array.
 * Only tags from registries in {@link JavaRegistries} are stored. Read {@link JavaRegistryKey} for more information.
 *
 * <p>To simply check if an element is in a tag, it's preferred to use the element's "{@code is}" method, if available. For example:</p>
 *
 * <ul>
 *     <li>{@link org.geysermc.geyser.level.block.type.Block#is(GeyserSession, Tag)}</li>
 *     <li>{@link org.geysermc.geyser.level.block.type.Block#is(GeyserSession, HolderSet)}</li>
 *     <li>{@link org.geysermc.geyser.item.type.Item#is(GeyserSession, Tag)}</li>
 *     <li>{@link org.geysermc.geyser.item.type.Item#is(GeyserSession, HolderSet)}</li>
 *     <li>{@link org.geysermc.geyser.inventory.GeyserItemStack#is(GeyserSession, Tag)}</li>
 *     <li>{@link org.geysermc.geyser.inventory.GeyserItemStack#is(GeyserSession, HolderSet)}</li>
 *     <li>{@link GeyserHolderSet#contains(GeyserSession, Object)}</li>
 * </ul>
 */
public final class TagCache {
    private final GeyserSession session;
    private final Map<Tag<?>, IntList> tags = new Object2ObjectOpenHashMap<>();
    private static Map<Key, IntList> fallbackBlockTags;

    public TagCache(GeyserSession session) {
        this.session = session;
    }

    public void loadPacket(ClientboundUpdateTagsPacket packet) {
        Map<Key, Map<Key, int[]>> allTags = packet.getTags();
        GeyserLogger logger = session.getGeyser().getLogger();

        this.tags.clear();

        for (Key registryKey : allTags.keySet()) {
            JavaRegistryKey<?> registry = JavaRegistries.fromKey(registryKey);
            if (registry == null) {
                logger.debug("Not loading tags for registry " + registryKey + " (registry not listed in JavaRegistries)");
                continue;
            }

            Map<Key, int[]> registryTags = allTags.get(registryKey);

            if (registry == JavaRegistries.BLOCK) {
                // Hack btw
                int[] convertableToMud = registryTags.get(MinecraftKey.key("convertable_to_mud"));
                boolean emulatePost1_18Logic = convertableToMud != null && convertableToMud.length != 0;
                session.setEmulatePost1_18Logic(emulatePost1_18Logic);
                if (logger.isDebug()) {
                    logger.debug("Emulating post 1.18 block predication logic for " + session.bedrockUsername() + "? " + emulatePost1_18Logic);
                }
            } else if (registry == JavaRegistries.ITEM) {
                // Hack btw
                int[] signs = registryTags.get(MinecraftKey.key("signs"));
                boolean emulatePost1_13Logic = signs != null && signs.length > 1;
                session.setEmulatePost1_13Logic(emulatePost1_13Logic);
                if (logger.isDebug()) {
                    logger.debug("Emulating post 1.13 villager logic for " + session.bedrockUsername() + "? " + emulatePost1_13Logic);
                }
            }

            loadTags(registryTags, registry, registry == JavaRegistries.ITEM);
        }
    }

    private void loadTags(Map<Key, int[]> packetTags, JavaRegistryKey<?> registry, boolean sort) {
        for (Map.Entry<Key, int[]> tag : packetTags.entrySet()) {
            int[] value = tag.getValue();
            if (sort) {
                // Used in RecipeBookAddTranslator
                Arrays.sort(value);
            }
            this.tags.put(new Tag<>(registry, tag.getKey()), IntList.of(value));
        }
    }

    /**
     * Should only be used when the network ID of an element is already known. If not, prefer using the {@link TagCache#is(Tag, Object)} shorthand method.
     */
    public boolean is(@NonNull Tag<?> tag, int id) {
        return getRaw(tag).contains(id);
    }

    public <T> boolean is(@NonNull Tag<T> tag, @NonNull T object) {
        return getRaw(tag).contains(tag.registry().networkId(session, object));
    }

    /**
     * Prefer using {@link GeyserHolderSet#contains(GeyserSession, Object)}.
     *
     * @return true if the specified network ID is in the given {@link GeyserHolderSet}.
     */
    public <T> boolean is(@NonNull GeyserHolderSet<T> holderSet, @Nullable T object) {
        if (object == null) {
            return false;
        }
        return holderSet.resolveRaw(this).contains(holderSet.getRegistry().networkId(session, object));
    }

    /**
     * @return true if the specified network ID is in the given {@link HolderSet} set.
     */
    public <T> boolean is(@Nullable HolderSet holderSet, @NonNull JavaRegistryKey<T> registry, int id) {
        if (holderSet == null) {
            return false;
        }

        IntList entries = holderSet.resolve(key -> {
            // This should never happen, since a key in a HolderSet is always a tag
            // We check for it anyway
            if (key.value().startsWith("#")) {
                key = Key.key(key.namespace(), key.value().substring(1));
            }
            return getRaw(new Tag<>(registry, key));
        });

        return entries.contains(id);
    }

    public <T> List<T> get(@NonNull Tag<T> tag) {
        return mapRawArray(session, getRaw(tag), tag.registry());
    }

    /**
     * @return the network IDs in the given tag. This can be an empty array.
     */
    public @NonNull IntList getRaw(@NonNull Tag<?> tag) {
        IntList value = this.tags.get(tag);
        if (value != null) {
            return value;
        }
        // A 26.2 server does not sync mineable/* or incorrect_for_*_tool; a Java client reads them from
        // its own jar, Geyser has no copy. Without them every tool rule misses and Bedrock players mine
        // at bare-hand speed. A tag the server does send still wins.
        if (tag.registry() == JavaRegistries.BLOCK) {
            IntList fallback = fallbackBlockTags().get(tag.tag());
            if (fallback != null) {
                return fallback;
            }
        }
        return IntLists.emptyList();
    }

    private static Map<Key, IntList> fallbackBlockTags() {
        if (fallbackBlockTags == null) {
            fallbackBlockTags = loadFallbackBlockTags();
        }
        return fallbackBlockTags;
    }

    private static Map<Key, IntList> loadFallbackBlockTags() {
        Map<Key, IntList> loaded = new HashMap<>();
        try (InputStream stream = GeyserImpl.getInstance().getBootstrap().getResourceOrThrow("titan/fallback_block_tags.json")) {
            Object2IntMap<Key> ids = new Object2IntOpenHashMap<>();
            ids.defaultReturnValue(-1);
            for (Block block : BlockRegistries.JAVA_BLOCKS.get()) {
                ids.put(block.javaIdentifier(), block.javaId());
            }

            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonArray blocks = entry.getValue().getAsJsonArray();
                IntArrayList list = new IntArrayList(blocks.size());
                for (JsonElement element : blocks) {
                    int id = ids.getInt(MinecraftKey.key(element.getAsString()));
                    if (id != -1) {
                        list.add(id);
                    }
                }
                loaded.put(MinecraftKey.key(entry.getKey()), IntLists.unmodifiable(list));
            }
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().error("Unable to load the bundled block tag fallback", e);
        }
        return loaded;
    }

    /**
     * Maps a raw array of network IDs to their respective objects.
     */
    public static <T> List<T> mapRawArray(GeyserSession session, IntList array, JavaRegistryKey<T> registry) {
        return array.intStream().mapToObj(i -> registry.value(session, i)).toList();
    }
}
