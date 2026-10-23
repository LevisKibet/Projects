package com.ronald.ancientpeaks_lore;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A data pack backed by a plain folder of structure files:
 *   FOLDER/village/houses/farmer_cottage.nbt  ->  ancientpeaks_lore:village/houses/farmer_cottage
 * Only when a folder is named in config/ancientpeaks_lore-devfolder.txt; otherwise the structures inside the jar are used.
 */
public final class DevStructures implements PackResources {
    private static final String PREFIX = "structures/";
    private final String id;
    private final Path root;

    DevStructures(String id, Path root) { this.id = id; this.root = root; }

    /**
     * The dev folder, only when one is named in config/ancientpeaks_lore-devfolder.txt (off by default: the structures,
     * characters and textures inside the jar are used).
     */
    public static Path folder() {
        Path cfg = FMLPaths.CONFIGDIR.get().resolve("ancientpeaks_lore-devfolder.txt");
        try {
            if (!Files.exists(cfg)) {
                Files.writeString(cfg, "# For building and testing only. Put a folder path on its own line (no #) to load structures (.nbt),\n"
                        + "# characters and item textures from it instead of the ones inside the mod. Sub-folders become part of the name:\n"
                        + "#   <folder>/village/houses/x.nbt -> ancientpeaks_lore:village/houses/x\n"
                        + "# Example:\n# ~/Desktop/Schematics/ancient_peaks\n");
            }
            for (String line : Files.readAllLines(cfg)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("~")) line = System.getProperty("user.home") + line.substring(1);
                Path p = Paths.get(line);
                return Files.isDirectory(p) ? p : null;
            }
        } catch (IOException e) {
            AncientPeaksLore.LOGGER.warn("Could not read {}", cfg, e);
        }
        return null;
    }

    /** config/ancientpeaks_lore/music: merlin.ogg and vortigern.ogg dropped here play in the cutscenes. */
    public static Path musicFolder() {
        Path p = FMLPaths.CONFIGDIR.get().resolve("ancientpeaks_lore").resolve("music");
        try {
            Files.createDirectories(p);
            Path readme = p.resolve("README.txt");
            if (!Files.exists(readme)) Files.writeString(readme, "Put merlin.ogg and/or vortigern.ogg here (Ogg Vorbis) to play them in the cutscenes. Press F3+T to reload.\n");
        } catch (IOException e) {
            AncientPeaksLore.LOGGER.warn("Could not create {}", p, e);
        }
        return p;
    }

    private Path fileFor(ResourceLocation rl) {
        if (!rl.getNamespace().equals(AncientPeaksLore.MODID) || !rl.getPath().startsWith(PREFIX)) return null;
        Path p = root.resolve(rl.getPath().substring(PREFIX.length())).normalize();
        return p.startsWith(root) && Files.isRegularFile(p) ? p : null;
    }

    @Override public IoSupplier<InputStream> getRootResource(String... path) { return null; }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation rl) {
        if (type != PackType.SERVER_DATA) return null;
        Path p = fileFor(rl);
        return p == null ? null : IoSupplier.create(p);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput out) {
        if (type != PackType.SERVER_DATA || !namespace.equals(AncientPeaksLore.MODID)) return;
        if (!(path + "/").startsWith(PREFIX) && !PREFIX.startsWith(path + "/")) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (!rel.endsWith(".nbt")) continue;
                String rp = PREFIX + rel;
                if (!rp.startsWith(path)) continue;
                if (!rp.equals(rp.toLowerCase(Locale.ROOT)) || !ResourceLocation.isValidPath(rp)) {
                    AncientPeaksLore.LOGGER.warn("Skipping {}: names must be lowercase with no spaces", f);
                    continue;
                }
                out.accept(new ResourceLocation(AncientPeaksLore.MODID, rp), IoSupplier.create(f));
            }
        } catch (IOException e) {
            AncientPeaksLore.LOGGER.warn("Could not list {}", root, e);
        }
    }

    @Override public Set<String> getNamespaces(PackType type) { return type == PackType.SERVER_DATA ? Set.of(AncientPeaksLore.MODID) : Set.of(); }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> s) {
        if (s.getMetadataSectionName().equals("pack")) return (T) new PackMetadataSection(Component.literal("Ancient Peaks dev structures"), 15);
        return null;
    }

    @Override public String packId() { return id; }
    @Override public boolean isBuiltin() { return true; }
    @Override public void close() { }

    /** All template ids currently in the folder (for the gallery). */
    public static List<ResourceLocation> listIds(net.minecraft.server.MinecraftServer server, String under) {
        String base = PREFIX + (under == null ? "" : under);
        return server.getResourceManager().listResources("structures", rl -> rl.getPath().endsWith(".nbt")).keySet().stream()
                .filter(rl -> rl.getNamespace().equals(AncientPeaksLore.MODID) && rl.getPath().startsWith(base))
                .map(rl -> new ResourceLocation(rl.getNamespace(), rl.getPath().substring(PREFIX.length(), rl.getPath().length() - 4)))
                .sorted((a, b) -> a.getPath().compareTo(b.getPath())).toList();
    }
}
