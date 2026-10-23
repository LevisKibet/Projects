package com.ronald.chroma.light;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;

/** The colour each light source gives off (linear RGB, 0..1), and whether it flickers like a flame. */
public final class LightColors {
    private LightColors() {}

    public record Tone(float r, float g, float b, boolean flame) {}
    static final Tone FIRE = new Tone(1.0f, 0.78f, 0.52f, true);
    static final Tone SOUL = new Tone(0.25f, 0.80f, 1.0f, true);
    static final Tone REDSTONE = new Tone(1.0f, 0.15f, 0.10f, false);
    static final Tone GLOW = new Tone(1.0f, 0.80f, 0.45f, false);
    static final Tone SEA = new Tone(0.55f, 0.90f, 1.0f, false);
    static final Tone PURPLE = new Tone(0.72f, 0.30f, 1.0f, false);
    static final Tone LAVA = new Tone(1.0f, 0.38f, 0.10f, true);

    private static final Map<Block, Tone> BLOCKS = new IdentityHashMap<>();
    static {
        for (Block b : new Block[]{Blocks.TORCH, Blocks.WALL_TORCH, Blocks.LANTERN, Blocks.CAMPFIRE, Blocks.FIRE, Blocks.JACK_O_LANTERN,
                Blocks.CANDLE, Blocks.FURNACE, Blocks.SMOKER, Blocks.BLAST_FURNACE}) BLOCKS.put(b, FIRE);
        for (Block b : new Block[]{Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH, Blocks.SOUL_LANTERN, Blocks.SOUL_CAMPFIRE, Blocks.SOUL_FIRE}) BLOCKS.put(b, SOUL);
        for (Block b : new Block[]{Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH, Blocks.REDSTONE_LAMP, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE}) BLOCKS.put(b, REDSTONE);
        for (Block b : new Block[]{Blocks.GLOWSTONE, Blocks.SHROOMLIGHT}) BLOCKS.put(b, GLOW);
        for (Block b : new Block[]{Blocks.SEA_LANTERN, Blocks.BEACON, Blocks.CONDUIT, Blocks.SEA_PICKLE}) BLOCKS.put(b, SEA);
        for (Block b : new Block[]{Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.SMALL_AMETHYST_BUD,
                Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.CRYING_OBSIDIAN, Blocks.NETHER_PORTAL,
                Blocks.END_ROD, Blocks.RESPAWN_ANCHOR, Blocks.ENCHANTING_TABLE, Blocks.ENDER_CHEST}) BLOCKS.put(b, PURPLE);
        for (Block b : new Block[]{Blocks.LAVA, Blocks.MAGMA_BLOCK, Blocks.LAVA_CAULDRON}) BLOCKS.put(b, LAVA);
        BLOCKS.put(Blocks.GLOW_LICHEN, new Tone(0.45f, 1.0f, 0.75f, false));
        BLOCKS.put(Blocks.OCHRE_FROGLIGHT, new Tone(1.0f, 0.85f, 0.35f, false));
        BLOCKS.put(Blocks.VERDANT_FROGLIGHT, new Tone(0.55f, 1.0f, 0.45f, false));
        BLOCKS.put(Blocks.PEARLESCENT_FROGLIGHT, new Tone(1.0f, 0.55f, 0.85f, false));
        BLOCKS.put(Blocks.SCULK_CATALYST, new Tone(0.25f, 0.9f, 0.95f, false));
        BLOCKS.put(Blocks.BREWING_STAND, new Tone(1.0f, 0.85f, 0.4f, false));
    }

    static final Tone WHITE = new Tone(1.0f, 0.92f, 0.8f, false);
    /** Other mods' blocks we know the colour of. */
    private static final Map<String, Tone> BY_ID = Map.of(
            "ancientpeaks_lore:sword_in_stone", new Tone(0.72f, 0.86f, 1.0f, false));

    /** Colours from the config (block id or "modid:*" -> colour), rebuilt when the config changes. */
    private static volatile Map<String, Tone> overrides = Map.of();
    private static volatile Object overridesFrom;
    /** Colours worked out from block textures, for blocks we don't know (modded ones included). */
    private static final Map<Block, Tone> AUTO = new java.util.concurrent.ConcurrentHashMap<>();
    /** Blocks waiting for their texture colour (only worked out on the render thread). */
    static final java.util.Set<Block> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The tone of a light-giving block: config first, then the built-in table, then its texture. Never null. */
    /** Development: colour Chroma's glows from their texture, the way Chronicles of Logres' copy does. */
    static final boolean TEXTURE_ONLY = Boolean.getBoolean("chroma.textureColours");

    public static Tone of(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof com.ronald.chroma.content.GlowBlock && !TEXTURE_ONLY) return com.ronald.chroma.content.GlowBlock.tone(s);   // the light along a strip
        Map<String, Tone> o = configured();
        if (!o.isEmpty()) {
            var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(b);
            if (id != null) {
                Tone t = o.get(id.toString());
                if (t == null) t = o.get(id.getNamespace() + ":*");
                if (t != null) return t;
            }
        }
        Tone t = BLOCKS.get(b);
        if (t != null) return t;
        var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(b);
        if (key != null && (t = BY_ID.get(key.toString())) != null) return t;
        if (b instanceof net.minecraft.world.level.block.CandleBlock || b instanceof net.minecraft.world.level.block.CandleCakeBlock) return candle(b);
        if (!Settings.autoColors()) return WHITE;
        t = AUTO.get(b);
        if (t != null) return t;
        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) { t = fromTexture(s); AUTO.put(b, t); return t; }
        PENDING.add(b);
        return WHITE;
    }

    private static final Map<Block, Tone> CANDLES = new java.util.concurrent.ConcurrentHashMap<>();

    /** A candle glows the colour it's dyed (a plain candle burns warm). */
    private static Tone candle(Block b) {
        return CANDLES.computeIfAbsent(b, k -> {
            var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(k);
            String path = id == null ? "" : id.getPath();
            String dye = path.replace("_candle_cake", "").replace("_candle", "");
            net.minecraft.world.item.DyeColor c = net.minecraft.world.item.DyeColor.byName(dye, null);
            if (c == null) return FIRE;
            float[] d = c.getTextureDiffuseColors();
            float mx = Math.max(d[0], Math.max(d[1], d[2]));
            // the dye's hue, a touch of flame warmth so it still reads as a candle
            return new Tone(Math.min(1f, d[0] / mx * 0.9f + 0.1f), Math.min(1f, d[1] / mx * 0.9f + 0.07f), Math.min(1f, d[2] / mx * 0.9f + 0.04f), true);
        });
    }

    // ------------------------------------------------------------------ glass and other things light shines through
    private static final Map<Block, Integer> FILTERS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * How a block colours light passing through it (r | g << 8 | b << 16, 255 = lets it all through), or 0xFFFFFF.
     * Stained glass and panes (vanilla or any mod's that declare a dye colour), and any other see-through block whose
     * texture is clearly coloured (modded coloured glass, slime, honey...).
     */
    static int filter(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof net.minecraft.world.level.block.BeaconBeamBlock bb) {
            float[] c = bb.getColor().getTextureDiffuseColors();
            return pack(c[0], c[1], c[2]);
        }
        if (b instanceof net.minecraft.world.level.block.LiquidBlock || s.isAir()) return 0xFFFFFF;
        Integer f = FILTERS.get(b);
        if (f != null) return f;
        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) { f = filterFromTexture(s); FILTERS.put(b, f); return f; }
        PENDING.add(b);
        return 0xFFFFFF;
    }

    private static int pack(float r, float g, float b) {
        float mx = Math.max(r, Math.max(g, b));
        if (mx <= 0) return 0xFFFFFF;
        // keep the glass's hue but never swallow all the light
        int ri = (int) (255 * (0.15f + 0.85f * r / mx)), gi = (int) (255 * (0.15f + 0.85f * g / mx)), bi = (int) (255 * (0.15f + 0.85f * b / mx));
        return ri | gi << 8 | bi << 16;
    }

    private static int filterFromTexture(BlockState s) {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            var model = mc.getBlockRenderer().getBlockModel(s);
            var types = model.getRenderTypes(s, net.minecraft.util.RandomSource.create(0), net.minecraftforge.client.model.data.ModelData.EMPTY);
            if (!types.contains(net.minecraft.client.renderer.RenderType.translucent())) return 0xFFFFFF;
            var sprite = model.getParticleIcon(net.minecraftforge.client.model.data.ModelData.EMPTY);
            var img = sprite.contents().getOriginalImage();
            int w = sprite.contents().width(), h = Math.min(sprite.contents().height(), img.getHeight());
            float r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int c = img.getPixelRGBA(x, y);
                if ((c >>> 24) < 8) continue;
                r += (c & 255) / 255f; g += ((c >> 8) & 255) / 255f; bl += ((c >> 16) & 255) / 255f; n++;
            }
            if (n == 0) return 0xFFFFFF;
            r /= n; g /= n; bl /= n;
            float mx = Math.max(r, Math.max(g, bl)), mn = Math.min(r, Math.min(g, bl));
            if (mx <= 0 || (mx - mn) / mx < 0.25f) return 0xFFFFFF;            // clear or grey glass: no colour
            return pack(r, g, bl);
        } catch (RuntimeException ex) {
            return 0xFFFFFF;
        }
    }

    // ------------------------------------------------------------------ blocks that glow only with Chroma Light
    /** Light level given to blocks vanilla leaves dark: amethyst, and anything in the config with "@level". */
    static int extraGlow(BlockState s) {
        Block b = s.getBlock();
        Map<String, Tone> o = configured();
        if (!GLOW_LEVELS.isEmpty()) {
            var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(b);
            if (id != null) {
                Integer l = GLOW_LEVELS.get(id.toString());
                if (l == null) l = GLOW_LEVELS.get(id.getNamespace() + ":*");
                if (l != null) return l;
            }
        }
        if (!Settings.amethystGlow()) return 0;
        if (b == Blocks.BUDDING_AMETHYST) return 6;
        if (b == Blocks.AMETHYST_BLOCK) return 4;
        if (b == Blocks.AMETHYST_CLUSTER) return 7;
        if (b == Blocks.LARGE_AMETHYST_BUD) return 6;
        if (b == Blocks.MEDIUM_AMETHYST_BUD) return 4;
        if (b == Blocks.SMALL_AMETHYST_BUD) return 3;
        return 0;
    }
    private static volatile Map<String, Integer> GLOW_LEVELS = Map.of();

    /** Work out the colours of blocks the light volume met off the render thread. Returns true if any were new. */
    static boolean resolvePending() {
        if (PENDING.isEmpty()) return false;
        for (Block b : PENDING.toArray(new Block[0])) {
            AUTO.put(b, fromTexture(b.defaultBlockState()));
            FILTERS.put(b, filterFromTexture(b.defaultBlockState()));
            PENDING.remove(b);
        }
        return true;
    }

    static void clearAuto() { AUTO.clear(); FILTERS.clear(); }

    /** The colour a block glows: the average of the brightest, most colourful pixels of its texture. */
    private static Tone fromTexture(BlockState s) {
        try {
            var sprite = net.minecraft.client.Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(s);
            var img = sprite.contents().getOriginalImage();
            int w = sprite.contents().width(), h = Math.min(sprite.contents().height(), img.getHeight());
            // pass 1: how bright is the brightest part
            float top = 0;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int c = img.getPixelRGBA(x, y);                         // ABGR
                if ((c >>> 24) < 128) continue;
                top = Math.max(top, Math.max(c & 255, Math.max((c >> 8) & 255, (c >> 16) & 255)));
            }
            if (top <= 0) return WHITE;
            // pass 2: average the glowing pixels, the colourful ones counting most
            float r = 0, g = 0, bl = 0, wsum = 0;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int c = img.getPixelRGBA(x, y);
                if ((c >>> 24) < 128) continue;
                float pr = (c & 255) / 255f, pg = ((c >> 8) & 255) / 255f, pb = ((c >> 16) & 255) / 255f;
                float mx = Math.max(pr, Math.max(pg, pb)), mn = Math.min(pr, Math.min(pg, pb));
                if (mx * 255f < top * 0.7f) continue;
                float wt = 0.25f + (mx > 0 ? (mx - mn) / mx : 0) * 2f;
                r += pr * pr * wt; g += pg * pg * wt; bl += pb * pb * wt; wsum += wt;   // squared: linear light
            }
            if (wsum <= 0) return WHITE;
            r /= wsum; g /= wsum; bl /= wsum;
            float m = Math.max(r, Math.max(g, bl));
            if (m <= 0.001f) return WHITE;
            r /= m; g /= m; bl /= m;
            float sat = 1f - Math.min(r, Math.min(g, bl));
            if (sat < 0.12f) return WHITE;                                // near-white textures: keep the light neutral
            boolean flame = s.getBlock() instanceof net.minecraft.world.level.block.TorchBlock || s.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock;
            return new Tone(r, g, bl, flame);
        } catch (RuntimeException ex) {
            return WHITE;
        }
    }

    private static Map<String, Tone> configured() {
        java.util.List<? extends String> list = Settings.colorList();
        if (list == overridesFrom) return overrides;
        Map<String, Tone> m = new java.util.HashMap<>();
        Map<String, Integer> glow = new java.util.HashMap<>();
        for (String e : list) {
            int eq = e.indexOf('=');
            if (eq < 0) continue;
            String key = e.substring(0, eq).trim(), val = e.substring(eq + 1).trim();
            int at = val.indexOf('@');
            if (at >= 0) {
                try { glow.put(key, Math.max(0, Math.min(15, Integer.parseInt(val.substring(at + 1).trim())))); } catch (NumberFormatException ignored) {}
                val = val.substring(0, at).trim();
            }
            float[] c = parseColour(val);
            if (c != null) m.put(key, new Tone(c[0], c[1], c[2], false));
        }
        GLOW_LEVELS = glow;
        overrides = m; overridesFrom = list;
        return m;
    }

    /** "#ff8800", "ff8800" or a name. */
    public static float[] parseColour(String s) {
        String h = s.startsWith("#") ? s.substring(1) : s;
        if (h.matches("[0-9a-fA-F]{6}")) {
            int v = Integer.parseInt(h, 16);
            return new float[]{((v >> 16) & 255) / 255f, ((v >> 8) & 255) / 255f, (v & 255) / 255f};
        }
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "red" -> new float[]{1f, 0.15f, 0.1f};
            case "orange" -> new float[]{1f, 0.5f, 0.15f};
            case "gold", "yellow" -> new float[]{1f, 0.82f, 0.3f};
            case "green" -> new float[]{0.3f, 1f, 0.35f};
            case "lime" -> new float[]{0.6f, 1f, 0.2f};
            case "cyan" -> new float[]{0.25f, 0.9f, 1f};
            case "blue" -> new float[]{0.2f, 0.4f, 1f};
            case "purple" -> new float[]{0.7f, 0.3f, 1f};
            case "magenta" -> new float[]{1f, 0.3f, 0.9f};
            case "pink" -> new float[]{1f, 0.5f, 0.75f};
            case "teal" -> new float[]{0.2f, 0.85f, 0.7f};
            case "white" -> new float[]{1f, 1f, 1f};
            default -> null;
        };
    }

    /** The tone and light level of something held, or null. */
    public static Object[] held(ItemStack st) {
        if (st.isEmpty()) return null;
        // any mod can make an item glow: an item tag  ChromaLight: {color: 0xRRGGBB, level: 1..15}
        if (st.hasTag() && st.getTag().contains("ChromaLight", 10)) {
            var t = st.getTag().getCompound("ChromaLight");
            int c = t.getInt("color"), lvl = Math.max(0, Math.min(15, t.getInt("level")));
            if (lvl > 0) return new Object[]{new Tone(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f, false), lvl};
        }
        if (st.is(Items.LAVA_BUCKET)) return new Object[]{LAVA, 15};
        if (st.is(Items.BLAZE_ROD) || st.is(Items.FIRE_CHARGE)) return new Object[]{FIRE, 10};
        if (st.is(Items.GLOWSTONE_DUST)) return new Object[]{GLOW, 8};
        if (st.is(Items.AMETHYST_SHARD) && Settings.amethystGlow()) return new Object[]{PURPLE, 5};
        if (st.getItem() instanceof BlockItem bi) {
            BlockState s = bi.getBlock().defaultBlockState();
            int lvl = Math.max(s.getLightEmission(), extraGlow(s));
            if (lvl > 0) return new Object[]{of(s), lvl};
        }
        return null;
    }

    /** Creatures that glow, and their light, or null. */
    public static Object[] creature(Entity e) {
        EntityType<?> t = e.getType();
        if (t == EntityType.BLAZE || t == EntityType.MAGMA_CUBE) return new Object[]{FIRE, 10};
        if (t == EntityType.GLOW_SQUID) return new Object[]{new Tone(0.3f, 1.0f, 0.85f, false), 8};
        if (t == EntityType.ALLAY) return new Object[]{new Tone(0.4f, 0.75f, 1.0f, false), 7};
        if (t == EntityType.VEX) return new Object[]{new Tone(0.75f, 0.85f, 1.0f, false), 5};
        return null;
    }
}
