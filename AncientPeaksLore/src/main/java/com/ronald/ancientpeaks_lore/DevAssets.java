package com.ronald.ancientpeaks_lore;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Client resources from the dev folder, re-read on F3+T:
 *   characters/NAME/*.geo.json        ->  geo/entity/NAME.geo.json            (Blockbench GeckoLib model)
 *   characters/NAME/*.animation.json  ->  animations/entity/NAME.animation.json
 *   characters/NAME/*.png             ->  textures/entity/NAME.png            (NAME.png if there are several)
 *   items/ITEM.png                    ->  the item's texture (its model is pointed at it)
 *   items/crown_layer_1.png           ->  the crown as worn
 */
public final class DevAssets implements PackResources {
    private final String id;
    private final Map<String, IoSupplier<InputStream>> files = new HashMap<>();

    DevAssets(String id, Path root, Path music) {
        this.id = id;
        if (root != null) {
            scanCharacters(root.resolve("characters"));
            scanItems(root.resolve("items"));
            scanMusic(root.resolve("music"));
        }
        if (music != null) scanMusic(music);
    }

    /** music/merlin.ogg, music/vortigern.ogg -> the cutscene music (Ogg Vorbis only). */
    private void scanMusic(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> fs = Files.list(dir)) {
            for (Path f : (Iterable<Path>) fs.filter(Files::isRegularFile)::iterator) {
                String fn = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (fn.endsWith(".ogg") && ResourceLocation.isValidPath(fn)) files.put("sounds/music/" + fn, IoSupplier.create(f));
            }
        } catch (IOException e) {
            AncientPeaksLore.LOGGER.warn("Could not read {}", dir, e);
        }
    }

    private void scanCharacters(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> chars = Files.list(dir)) {
            for (Path c : (Iterable<Path>) chars.filter(Files::isDirectory)::iterator) {
                String name = c.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!ResourceLocation.isValidPath(name)) continue;
                try (Stream<Path> fs = Files.list(c)) {
                    for (Path f : (Iterable<Path>) fs.filter(Files::isRegularFile).sorted()::iterator) {
                        String fn = f.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (fn.startsWith("_") || fn.startsWith(".")) continue;
                        String key = fn.endsWith(".geo.json") ? "geo/entity/" + name + ".geo.json"
                                : fn.endsWith(".animation.json") ? "animations/entity/" + name + ".animation.json"
                                : fn.endsWith(".png") ? "textures/entity/" + name + ".png" : null;
                        if (key == null) continue;
                        // a file named after the character wins over any other
                        boolean named = fn.startsWith(name + ".");
                        if (named || !files.containsKey(key)) files.put(key, IoSupplier.create(f));
                    }
                }
            }
        } catch (IOException e) {
            AncientPeaksLore.LOGGER.warn("Could not read {}", dir, e);
        }
    }

    private void scanItems(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> fs = Files.list(dir)) {
            for (Path f : (Iterable<Path>) fs.filter(Files::isRegularFile)::iterator) {
                String fn = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!fn.endsWith(".png")) continue;
                String item = fn.substring(0, fn.length() - 4);
                if (!ResourceLocation.isValidPath(item)) continue;
                if (item.startsWith("crown_layer_")) { files.put("textures/models/armor/" + fn, IoSupplier.create(f)); continue; }
                files.put("textures/item/" + fn, IoSupplier.create(f));
                String parent = item.equals("excalibur") ? "minecraft:item/handheld" : "minecraft:item/generated";
                String model = "{\"parent\":\"" + parent + "\",\"textures\":{\"layer0\":\"" + AncientPeaksLore.MODID + ":item/" + item + "\"}}";
                byte[] bytes = model.getBytes(StandardCharsets.UTF_8);
                files.put("models/item/" + item + ".json", () -> new ByteArrayInputStream(bytes));
            }
        } catch (IOException e) {
            AncientPeaksLore.LOGGER.warn("Could not read {}", dir, e);
        }
    }

    @Override public IoSupplier<InputStream> getRootResource(String... path) { return null; }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation rl) {
        if (type != PackType.CLIENT_RESOURCES || !rl.getNamespace().equals(AncientPeaksLore.MODID)) return null;
        return files.get(rl.getPath());
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput out) {
        if (type != PackType.CLIENT_RESOURCES || !namespace.equals(AncientPeaksLore.MODID)) return;
        String pre = path.endsWith("/") ? path : path + "/";
        files.forEach((k, v) -> { if (k.startsWith(pre)) out.accept(new ResourceLocation(namespace, k), v); });
    }

    @Override public Set<String> getNamespaces(PackType type) { return type == PackType.CLIENT_RESOURCES ? Set.of(AncientPeaksLore.MODID) : Set.of(); }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> s) {
        if (s.getMetadataSectionName().equals("pack")) return (T) new PackMetadataSection(Component.literal("Ancient Peaks dev characters"), 15);
        return null;
    }

    @Override public String packId() { return id; }
    @Override public boolean isBuiltin() { return true; }
    @Override public void close() { }
}
