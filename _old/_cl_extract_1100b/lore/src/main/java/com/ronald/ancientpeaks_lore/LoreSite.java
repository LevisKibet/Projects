package com.ronald.ancientpeaks_lore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.ronald.ancientpeaks.world.HighlandsAccess;
import com.ronald.ancientpeaks.world.Island;
import com.ronald.ancientpeaks.world.IslandSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The quest's places, each built from the templates in one folder (anything whose file name starts with "_" is left out):
 *   village       island plains: lore houses near the middle, ruined houses round them, farms on the edge   village/...
 *   castle        island plains, beside the village                                                         castle/
 *   summit        the high flat shelf: three great swords round the pedestal                               swords/
 *   wizard_tower  a flattish spot high on the mountain, away from the swords                                 wizard_tower/
 *   chapels       Merlin's whole church 1000-1500 blocks from spawn (once a world), four ruins 500+ apart    churches/
 */
public class LoreSite extends Structure {
    public static final Codec<LoreSite> CODEC = RecordCodecBuilder.create(i -> i.group(
            settingsCodec(i), Codec.STRING.fieldOf("site").forGetter(s -> s.site)).apply(i, LoreSite::new));
    final String site;
    static final boolean DEBUG = Boolean.getBoolean("ancientpeaks_lore.debug");

    public LoreSite(StructureSettings settings, String site) { super(settings); this.site = site; }

    @Override public StructureType<?> type() { return AncientPeaksLore.SITE.get(); }

    record Placed(ResourceLocation id, int x, int y, int z, Rotation rot, int sx, int sz) {
        BlockPos pivot() { return new BlockPos(sx / 2, 0, sz / 2); }
        BlockPos origin() { return new BlockPos(x - sx / 2, y, z - sz / 2); }
    }

    // ------------------------------------------------------------------ helpers
    static List<ResourceLocation> templates(GenerationContext ctx, String folder) { return templates(ctx.structureTemplateManager(), folder); }

    static List<ResourceLocation> templates(net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager mgr, String folder) {
        String pre = folder.endsWith("/") ? folder : folder + "/";
        return mgr.listTemplates()
                .filter(rl -> rl.getNamespace().equals(AncientPeaksLore.MODID) && rl.getPath().startsWith(pre)
                        && rl.getPath().indexOf('/', pre.length()) < 0 && !rl.getPath().substring(pre.length()).startsWith("_"))
                .sorted(Comparator.comparing(ResourceLocation::getPath)).toList();
    }

    static StructureTemplate tpl(GenerationContext ctx, ResourceLocation id) {
        return ctx.structureTemplateManager().get(id).orElse(null);
    }

    /** y of the top solid block (water counts as "no ground": returns MIN_VALUE). */
    static int ground(GenerationContext ctx, int x, int z) {
        int land = ctx.chunkGenerator().getFirstOccupiedHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, ctx.heightAccessor(), ctx.randomState());
        int surf = ctx.chunkGenerator().getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, ctx.heightAccessor(), ctx.randomState());
        return land == surf ? land - 1 : Integer.MIN_VALUE;
    }

    /** Turn so the template's front (+Z, where the doors are) faces from (x,z) toward (tx,tz). */
    static Rotation facing(int x, int z, int tx, int tz) {
        int dx = tx - x, dz = tz - z;
        if (Math.abs(dz) >= Math.abs(dx)) return dz >= 0 ? Rotation.NONE : Rotation.CLOCKWISE_180;
        return dx >= 0 ? Rotation.COUNTERCLOCKWISE_90 : Rotation.CLOCKWISE_90;
    }

    static boolean swapped(Rotation r) { return r == Rotation.CLOCKWISE_90 || r == Rotation.COUNTERCLOCKWISE_90; }

    static IslandSampler sampler(GenerationContext ctx) {
        return ((HighlandsAccess) (Object) ctx.randomState()).ancientpeaks$sampler();
    }

    static Island island(GenerationContext ctx, IslandSampler s) {
        ChunkPos cp = ctx.chunkPos();
        return s.islandInSector(Math.floorDiv(cp.getMiddleBlockX(), IslandSampler.SECTOR), Math.floorDiv(cp.getMiddleBlockZ(), IslandSampler.SECTOR));
    }

    static boolean inChunk(ChunkPos cp, int x, int z) {
        return SectionPos.blockToSectionCoord(x) == cp.x && SectionPos.blockToSectionCoord(z) == cp.z;
    }

    private Optional<GenerationStub> stub(GenerationContext ctx, int x, int y, int z, List<Placed> parts) {
        if (parts.isEmpty()) return Optional.empty();
        return Optional.of(new GenerationStub(new BlockPos(x, y, z), (StructurePiecesBuilder b) -> {
            for (Placed p : parts) {
                LorePiece piece = new LorePiece(ctx.structureTemplateManager(), p.id(), p.origin(), p.rot(), p.pivot(),
                        !site.equals("dock") && !site.equals("summit"));
                b.addPiece(piece);
                if (DEBUG) AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] {} piece {} box {}", site, p.id(), piece.getBoundingBox());
            }
            if (DEBUG) AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] {} built {} pieces, empty={}", site, parts.size(), b.isEmpty());
        }));
    }

    private static Placed place(GenerationContext ctx, ResourceLocation id, int x, int z, Rotation rot, int sink) {
        StructureTemplate t = tpl(ctx, id);
        if (t == null) return null;
        int g = ground(ctx, x, z);
        if (g == Integer.MIN_VALUE) g = ctx.chunkGenerator().getFirstOccupiedHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, ctx.heightAccessor(), ctx.randomState()) - 1;
        // template layer 1 is the ground floor in all our buildings, so layer 1 sits on the surface block; everything
        // but Camelot stands a block higher still, so floors and doorsteps sit proud of the turf instead of sunk into it
        int lift = id.getPath().startsWith("castle/") ? 0 : 1;
        return new Placed(id, x, g - 1 - sink + lift, z, rot, t.getSize().getX(), t.getSize().getZ());
    }

    // ------------------------------------------------------------------ the sites
    /**
     * Every site works out its own spot from the island, so the usual "is the biome at the start point allowed" check
     * is skipped: it only ever threw a placed site away (the castle's start sat in a biome its tag didn't list).
     */
    @Override
    public Optional<GenerationStub> findValidGenerationPoint(GenerationContext ctx) {
        return findGenerationPoint(ctx);
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        try {
            return findInner(ctx);
        } catch (RuntimeException e) {
            AncientPeaksLore.LOGGER.error("[ancientpeaks_lore] {} failed at {}", site, ctx.chunkPos(), e);
            return Optional.empty();
        }
    }

    private Optional<GenerationStub> findInner(GenerationContext ctx) {
        if (site.equals("chapels")) return chapels(ctx);
        if (site.equals("roadside_cart") || site.equals("wild_house")) return roadside(ctx);
        IslandSampler s = sampler(ctx);
        if (s == null) return Optional.empty();
        Island is = island(ctx, s);
        if (is == null) return Optional.empty();
        ChunkPos cp = ctx.chunkPos();
        Random r = new Random(ctx.seed() ^ (is.cx * 341873128712L) ^ (is.cz * 132897987541L) ^ site.hashCode());
        switch (site) {
            case "castle": {
                int[] a = castleSpot(s, is, ctx.structureTemplateManager());
                if (a == null || !inChunk(cp, a[0], a[1])) return Optional.empty();
                var all = templates(ctx, "castle");
                if (all.isEmpty()) return Optional.empty();
                Placed p = place(ctx, all.get(r.nextInt(all.size())), a[0], a[1], Rotation.values()[r.nextInt(4)], 0);
                if (p == null) return Optional.empty();
                Placed q = new Placed(p.id(), p.x(), Math.max(p.y(), 63), p.z(), p.rot(), p.sx(), p.sz());
                AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] Camelot ({}) placed at {},{},{}", q.id(), a[0], q.y(), a[1]);
                return stub(ctx, a[0], Math.max(q.y() + 1, 64), a[1], List.of(q));
            }
            case "summit": {
                int[] a = is.anchor("meadow_plateau");
                if (!inChunk(cp, a[0], a[1])) return Optional.empty();
                List<Placed> parts = new ArrayList<>();
                var swords = new ArrayList<>(templates(ctx, "swords"));
                var pedestal = swords.stream().filter(rl -> rl.getPath().contains("pedestal")).findFirst();
                swords.removeIf(rl -> rl.getPath().contains("pedestal"));
                pedestal.ifPresent(id -> { Placed p = place(ctx, id, a[0], a[1], Rotation.NONE, 0); if (p != null) parts.add(p); });
                double a0 = r.nextDouble() * Math.PI * 2;
                for (int k = 0; k < Math.min(3, swords.size()); k++) {
                    double ang = a0 + k * Math.PI * 2 / 3;
                    int x = a[0] + (int) Math.round(Math.cos(ang) * 13), z = a[1] + (int) Math.round(Math.sin(ang) * 13);
                    // a sword's broad face (template Z) turned toward the pedestal; its tip driven 12 blocks into the rock
                    Rotation rot = Math.abs(Math.cos(ang)) > Math.abs(Math.sin(ang)) ? Rotation.CLOCKWISE_90 : Rotation.NONE;
                    Placed p = place(ctx, swords.get(k), x, z, rot, 11);
                    if (p != null) parts.add(p);
                }
                if (DEBUG) AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] summit at {},{}: {} pieces from {}", a[0], a[1], parts.size(), swords);
                return stub(ctx, a[0], parts.isEmpty() ? 100 : parts.get(0).y() + 1, a[1], parts);
            }
            case "wizard_tower": {
                int[] a = towerSpot(s, is);
                if (a == null || !inChunk(cp, a[0], a[1])) return Optional.empty();
                var all = templates(ctx, "wizard_tower");
                if (DEBUG) AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] wizard tower at {},{}: {}", a[0], a[1], all);
                if (all.isEmpty()) return Optional.empty();
                int[] m = is.anchor("meadow_plateau");
                Placed p = place(ctx, all.get(r.nextInt(all.size())), a[0], a[1], facing(a[0], a[1], m[0], m[1]), 0);
                return p == null ? Optional.empty() : stub(ctx, a[0], p.y() + 1, a[1], List.of(p));
            }
            case "dock": {
                int[] d = dockSpot(s, is, ctx.structureTemplateManager());
                if (d == null) return Optional.empty();
                int[] dir = {d[2], d[3]};
                Rotation rot = dir[1] > 0 ? Rotation.NONE : dir[1] < 0 ? Rotation.CLOCKWISE_180 : dir[0] < 0 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90;
                var all = templates(ctx, "dock");
                if (all.isEmpty()) return Optional.empty();
                ResourceLocation id = all.get(r.nextInt(all.size()));
                StructureTemplate tp = tpl(ctx, id);
                if (tp == null) return Optional.empty();
                int sx = tp.getSize().getX(), sz = tp.getSize().getZ();
                // the land end (template z=0) sits 3 blocks inland of the waterline; walkway (layer 7) at sea level + 1
                int cx = d[0] - dir[0] * 3 + dir[0] * (sz / 2), cz = d[1] - dir[1] * 3 + dir[1] * (sz / 2);
                if (!inChunk(cp, cx, cz)) return Optional.empty();
                Placed p = new Placed(id, cx, 63 - 7, cz, rot, sx, sz);
                return stub(ctx, d[0] - dir[0] * 3, 64, d[1] - dir[1] * 3, List.of(p));
            }
            case "village": {
                List<Placed> parts = new ArrayList<>();
                for (Lot lot : villageLayout(ctx.seed(), s, is, ctx.structureTemplateManager())) {
                    if (!inChunk(cp, lot.x(), lot.z())) continue;
                    Placed p = place(ctx, lot.id(), lot.x(), lot.z(), lot.rot(), 0);
                    if (p != null) parts.add(p);
                }
                if (parts.isEmpty()) return Optional.empty();
                Placed f = parts.get(0);
                return stub(ctx, f.x(), Math.max(f.y() + 1, 64), f.z(), parts);
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ out in the wide world
    /**
     * A raided supply cart (carts/) on a level bit of open land, anywhere on dry land; or a lone house of the old kingdom
     * (village/houses_town/), anywhere but on the islands (they have their own villages). Never in water, on a beach or
     * on a steep slope.
     */
    private Optional<GenerationStub> roadside(GenerationContext ctx) {
        boolean cart = site.equals("roadside_cart");
        ChunkPos cp = ctx.chunkPos();
        Random r = new Random(ctx.seed() ^ cp.toLong() * 0x9E3779B97F4A7C15L ^ site.hashCode());
        int x = cp.getMinBlockX() + 4 + r.nextInt(8), z = cp.getMinBlockZ() + 4 + r.nextInt(8);
        IslandSampler s = sampler(ctx);
        if (!cart && s != null && s.islandAt(x, z) != null) return Optional.empty();
        int g = ground(ctx, x, z);
        if (g == Integer.MIN_VALUE || g < 63) return Optional.empty();
        int reach = cart ? 6 : 11, lo = g, hi = g;
        for (int[] o : new int[][]{{reach, reach}, {-reach, reach}, {reach, -reach}, {-reach, -reach}, {reach, 0}, {-reach, 0}, {0, reach}, {0, -reach}}) {
            int h = ground(ctx, x + o[0], z + o[1]);
            if (h == Integer.MIN_VALUE) return Optional.empty();
            lo = Math.min(lo, h); hi = Math.max(hi, h);
        }
        if (hi - lo > (cart ? 3 : 4)) return Optional.empty();
        var biome = ctx.biomeSource().getNoiseBiome(x >> 2, g >> 2, z >> 2, ctx.randomState().sampler());
        if (biome.is(net.minecraft.tags.BiomeTags.IS_OCEAN) || biome.is(net.minecraft.tags.BiomeTags.IS_RIVER) || biome.is(net.minecraft.tags.BiomeTags.IS_BEACH)
                || biome.is(net.minecraft.tags.BiomeTags.IS_NETHER) || biome.is(net.minecraft.tags.BiomeTags.IS_END)) return Optional.empty();
        var all = templates(ctx, cart ? "carts" : "village/houses_town");
        if (all.isEmpty()) return Optional.empty();
        Placed p = place(ctx, all.get(r.nextInt(all.size())), x, z, Rotation.values()[r.nextInt(4)], 0);
        return p == null ? Optional.empty() : stub(ctx, x, p.y() + 1, z, List.of(p));
    }

    // ------------------------------------------------------------------ village layout (pure: the trails reuse it)
    /** kind 0 = lore house, 1 = house, 2 = farm, 3 = market stall, 4 = the great church. (x, z) is the centre; door/gate faces the village centre. */
    public record Lot(ResourceLocation id, int x, int z, Rotation rot, int sx, int sz, int kind) {
        /** Just outside the door (or the field gate). */
        public int[] door() {
            int[] d = dir(rot);
            int off = sz / 2 - (kind == 2 ? -1 : 2);
            return new int[]{x + d[0] * off, z + d[1] * off};
        }
    }
    static int[] dir(Rotation r) {
        return switch (r) { case NONE -> new int[]{0, 1}; case CLOCKWISE_90 -> new int[]{-1, 0}; case CLOCKWISE_180 -> new int[]{0, -1}; default -> new int[]{1, 0}; };
    }

    private static final Map<String, List<Lot>> LAYOUT = new ConcurrentHashMap<>();
    /**
     * The castle stands in the middle of the plains; the lore houses cluster just outside its walls, the other houses and
     * the fields are dotted over the rest of the plains and thin out up the hill slopes. Templates repeat to fill the land.
     */
    public static List<Lot> villageLayout(long seed, IslandSampler s, Island is, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager mgr) {
        return LAYOUT.computeIfAbsent(seed + "/" + is.cx + "/" + is.cz, k -> {
            Random r = new Random(seed ^ (is.cx * 341873128712L) ^ (is.cz * 132897987541L) ^ "village".hashCode());
            int[] c = castleSpot(s, is, mgr);
            if (c == null) c = is.anchor("plains_center");
            double castleHalf = castleHalf(mgr);
            List<ResourceLocation> lore = templates(mgr, "village/houses_lore"), houses = new ArrayList<>(templates(mgr, "village/houses")),
                    farms = new ArrayList<>(templates(mgr, "village/farms")), town = new ArrayList<>(templates(mgr, "village/houses_town")),
                    stalls = new ArrayList<>(templates(mgr, "village/market"));
            Collections.shuffle(houses, r); Collections.shuffle(farms, r); Collections.shuffle(town, r); Collections.shuffle(stalls, r);
            IslandSampler.Column col = new IslandSampler.Column();
            Streams.Corridors streams = Streams.near(c[0], c[1], 340);
            int[] archA = is.archLow(), archB = is.archHigh();
            // where building is possible, and how keen: 1 on the open plains, fading to 0 up the slopes
            List<double[]> pool = new ArrayList<>();
            for (int x = c[0] - 320; x <= c[0] + 320; x += 4) for (int z = c[1] - 320; z <= c[1] + 320; z += 4) {
                double d = Math.hypot(x - c[0], z - c[1]);
                if (d < castleHalf + 8 || d > 320 || wetAt(s, col, streams, x, z)) continue;
                if (segDist(x, z, archA, archB) < 44) continue;                 // never on (or under) the great arch
                s.fill(col, x, z);
                if (col.is == null || col.m < 1 || col.h < 64.5) continue;
                var zn = s.zone(col, (int) col.h);
                if (zn == IslandSampler.Zone.TARN || zn == IslandSampler.Zone.GORGE || zn == IslandSampler.Zone.SHORE || zn == IslandSampler.Zone.PEAKS) continue;
                double w = 1 - Math.max(0, (col.h - 71) / 20.0) - (zn == IslandSampler.Zone.PLAINS ? 0 : 0.35);
                if (w > 0.05) pool.add(new double[]{x, z, w, d});
            }
            List<Lot> lots = new ArrayList<>();
            List<int[]> boxes = new ArrayList<>();
            int ch = (int) castleHalf + 4;
            boxes.add(new int[]{c[0] - ch, c[1] - ch, c[0] + ch, c[1] + ch});
            if (pool.isEmpty()) return lots;
            record Job(ResourceLocation id, int kind, double maxD) {}
            List<Job> jobs = new ArrayList<>();
            // the town's great church first, while there's still room for it near the walls
            for (var id : templates(mgr, "village/cathedral")) jobs.add(new Job(id, 4, castleHalf + 170));
            for (var id : lore) jobs.add(new Job(id, 0, castleHalf + 70));
            // Camelot's market: a ring of stalls just outside the walls, then the town houses packed close round them
            for (int i = 0; i < Math.min(10, stalls.size()); i++) jobs.add(new Job(stalls.get(i), 3, castleHalf + 40));
            for (int i = 0; i < (town.isEmpty() ? 0 : 9); i++) jobs.add(new Job(town.get(i % town.size()), 1, castleHalf + 120));
            int nh = houses.isEmpty() ? 0 : Math.min(64, Math.max(houses.size(), pool.size() / 60));
            int nf = farms.isEmpty() ? 0 : Math.min(16, Math.max(farms.size(), pool.size() / 300));
            // interleave so fields and houses share the land
            for (int i = 0, hi = 0, fi = 0; hi < nh || fi < nf; i++) {
                if (fi < nf && (i % 3 == 2 || hi >= nh)) jobs.add(new Job(farms.get(fi++ % farms.size()), 2, 1e9));
                else if (hi < nh) jobs.add(new Job(houses.get(hi++ % houses.size()), 1, 1e9));
            }
            for (Job j : jobs) {
                StructureTemplate t = mgr.get(j.id).orElse(null);
                if (t == null) continue;
                int sx = t.getSize().getX(), sz = t.getSize().getZ();
                for (int attempt = 0; attempt < (j.kind == 4 ? 1500 : 260); attempt++) {
                    double[] p = pool.get(r.nextInt(pool.size()));
                    if (p[3] > j.maxD || r.nextDouble() > p[2]) continue;      // thinning out up the slopes
                    int x = (int) p[0] + r.nextInt(5) - 2, z = (int) p[1] + r.nextInt(5) - 2;
                    Rotation rot = facing(x, z, c[0], c[1]);
                    int hx = (swapped(rot) ? sz : sx) / 2 + 1, hz = (swapped(rot) ? sx : sz) / 2 + 1;
                    boolean clash = false;
                    for (int[] b : boxes) if (x - hx < b[2] && x + hx > b[0] && z - hz < b[3] && z + hz > b[1]) { clash = true; break; }
                    if (clash) continue;
                    double lo = 1e9, hi = -1e9; boolean ok = true;
                    for (int[] o : new int[][]{{0, 0}, {-hx, -hz}, {hx, -hz}, {-hx, hz}, {hx, hz}}) {
                        s.fill(col, x + o[0], z + o[1]);
                        if (col.is == null || col.m < 1 || col.h < 64.5 || s.zone(col, (int) col.h) == IslandSampler.Zone.TARN) { ok = false; break; }
                        lo = Math.min(lo, col.h); hi = Math.max(hi, col.h);
                    }
                    if (!ok || hi - lo > (j.kind == 2 ? 3 : j.kind == 3 ? 2 : j.kind == 4 ? 7 : 5)) continue;
                    if (segDist(x, z, archA, archB) < 44 + Math.max(hx, hz)) continue;
                    for (int[] o : new int[][]{{0, 0}, {-hx, -hz}, {hx, -hz}, {-hx, hz}, {hx, hz}, {0, -hz}, {0, hz}, {-hx, 0}, {hx, 0}})
                        if (wetAt(s, col, streams, x + o[0], z + o[1])) { ok = false; break; }
                    if (!ok) continue;
                    lots.add(new Lot(j.id, x, z, rot, sx, sz, j.kind));
                    int gap = j.kind == 3 ? 2 : 3;                            // room for a 3-wide lane between (the market is tighter)
                    boxes.add(new int[]{x - hx - gap, z - hz - gap, x + hx + gap, z + hz + gap});
                    break;
                }
            }
            AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] village round Camelot at {},{}: {} houses and fields", c[0], c[1], lots.size());
            return lots;
        });
    }

    static double segDist(double x, double z, int[] a, int[] b) {
        double dx = b[0] - a[0], dz = b[1] - a[1], l2 = dx * dx + dz * dz;
        double u = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * dx + (z - a[1]) * dz) / l2));
        return Math.hypot(a[0] + u * dx - x, a[1] + u * dz - z);
    }

    /** On a stream's course? Streams aren't carved on the plains (they go underground at the edge), so only off them. */
    static boolean wetAt(IslandSampler s, IslandSampler.Column col, Streams.Corridors streams, int x, int z) {
        if (streams == Streams.NONE || !streams.at(x, z)) return false;
        s.fill(col, x, z);
        return col.is == null || s.zone(col, (int) col.h) != IslandSampler.Zone.PLAINS;
    }

    static double castleHalf(net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager mgr) {
        double half = 0;
        for (var id : templates(mgr, "castle")) {
            StructureTemplate t = mgr.get(id).orElse(null);
            if (t != null) half = Math.max(half, Math.max(t.getSize().getX(), t.getSize().getZ()) / 2.0);
        }
        return half;
    }

    /** The middle of the plains, on dry level ground with room for the whole castle (the best spot found, whatever happens). */
    static final Map<Long, int[]> CASTLE = new ConcurrentHashMap<>();
    static int[] castleSpot(IslandSampler s, Island is, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager mgr) {
        int[] res = CASTLE.computeIfAbsent(((long) is.cx << 32) ^ (is.cz & 0xffffffffL), k -> {
            double half = castleHalf(mgr);
            if (half == 0) half = 80;
            int[] a = is.anchor("plains_center");
            Streams.Corridors streams = Streams.near(a[0], a[1], 240 + half + 16);
            IslandSampler.Column col = new IslandSampler.Column();
            int[] best = a; double bestScore = -1e9; int bestWet = 0;
            for (int ring = 0; ring <= 10; ring++) {
                int n = ring == 0 ? 1 : 12 + ring * 4;
                for (int i = 0; i < n; i++) {
                    double ang = i * Math.PI * 2 / n + ring * 0.37;
                    int x = a[0] + (int) Math.round(Math.cos(ang) * ring * 24), z = a[1] + (int) Math.round(Math.sin(ang) * ring * 24);
                    int land = 0, tot = 0; double lo = 1e9, hi = -1e9;
                    for (int gx = -3; gx <= 3; gx++) for (int gz = -3; gz <= 3; gz++) {
                        s.fill(col, x + (int) (gx * half / 3), z + (int) (gz * half / 3)); tot++;
                        if (col.is == null || col.m < 1 || col.h < 64.5 || s.zone(col, (int) col.h) == IslandSampler.Zone.TARN) continue;
                        land++; lo = Math.min(lo, col.h); hi = Math.max(hi, col.h);
                    }
                    // a stream through the footprint would stop dead at the walls: keep well clear of them
                    int wet = 0;
                    for (int gx = -4; gx <= 4; gx++) for (int gz = -4; gz <= 4; gz++)
                        if (wetAt(s, col, streams, x + (int) (gx * (half + 6) / 4), z + (int) (gz * (half + 6) / 4))) wet++;
                    double score = land * 100.0 / tot - (land > 0 ? (hi - lo) * 1.5 : 50) - ring * 3 - wet * 30;
                    if (score > bestScore) { bestScore = score; best = new int[]{x, z}; bestWet = wet; }
                }
            }
            AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] Camelot for island {},{} at {},{} ({} of 81 footprint samples on a stream)", is.cx, is.cz, best[0], best[1], bestWet);
            return best;
        });
        return res;
    }

    static double castleRadius(net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager mgr) {
        double castleR = 0;
        for (var id : templates(mgr, "castle")) {
            StructureTemplate t = mgr.get(id).orElse(null);
            if (t != null) castleR = Math.max(castleR, Math.hypot(t.getSize().getX(), t.getSize().getZ()) / 2 + 8);
        }
        return castleR;
    }

    // ------------------------------------------------------------------ Merlin's tower: high on the mountain, level ground
    static final Map<Long, int[]> TOWER = new ConcurrentHashMap<>();
    static int[] towerSpot(IslandSampler s, Island is) {
        return TOWER.computeIfAbsent(((long) is.cx << 32) ^ (is.cz & 0xffffffffL), k -> {
            int[] meadow = is.anchor("meadow_plateau");
            IslandSampler.Column c = new IslandSampler.Column(), d = new IslandSampler.Column();
            int[] best = null; double bestScore = 1e9;
            int R = (int) is.boundR;
            for (int x = is.cx - R; x <= is.cx + R; x += 12) {
                for (int z = is.cz - R; z <= is.cz + R; z += 12) {
                    s.fill(c, x, z);
                    if (c.is == null || c.m < 1 || c.h < 115 || c.h > 205 || s.nearRiver(c, 24)) continue;
                    var zn = s.zone(c, (int) c.h);
                    if (zn == IslandSampler.Zone.TARN || zn == IslandSampler.Zone.MEADOW || zn == IslandSampler.Zone.GORGE || zn == IslandSampler.Zone.PEAKS) continue;
                    if (Math.hypot(x - meadow[0], z - meadow[1]) < 160) continue;
                    double slope = 0;
                    for (int[] o : new int[][]{{11, 0}, {-11, 0}, {0, 11}, {0, -11}, {8, 8}, {-8, -8}}) {
                        s.fill(d, x + o[0], z + o[1]); slope = Math.max(slope, Math.abs(d.h - c.h));
                    }
                    if (slope > 9) continue;
                    double score = slope + Math.abs(c.h - 160) * 0.08;
                    if (score < bestScore) { bestScore = score; best = new int[]{x, z}; }
                }
            }
            if (best == null) { int[] p = is.anchor("main_peak"); best = new int[]{p[0] + 60, p[1] + 60}; }
            AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] Merlin's tower for island {},{} at {},{}", is.cx, is.cz, best[0], best[1]);
            return best;
        });
    }

    // ------------------------------------------------------------------ the dock: the nearest calm shore to the village
    static final Map<Long, int[]> DOCK = new ConcurrentHashMap<>();   // waterline x, z, direction out to sea dx, dz
    static int[] dockSpot(IslandSampler s, Island is, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager mgr) {
        int[] r0 = DOCK.computeIfAbsent(((long) is.cx << 32) ^ (is.cz & 0xffffffffL), k -> {
            int[] c = castleSpot(s, is, mgr), castle = null;
            IslandSampler.Column col = new IslandSampler.Column();
            int[] best = null; double bestD = 1e9;
            for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int lat = -96; lat <= 96; lat += 8) {
                    int sx = c[0] + dir[1] * lat, sz = c[1] + dir[0] * lat;
                    int shore = -1; double landH = 0;
                    for (int tt = 0; tt < 700; tt += 2) {
                        s.fill(col, sx + dir[0] * tt, sz + dir[1] * tt);
                        if (col.is == null || col.h < 62) { shore = tt; break; }
                        landH = col.h;
                    }
                    if (shore < 0 || landH > 70) continue;                    // no water, or a cliff edge
                    boolean deep = true;
                    for (int kk = 4; kk <= 30; kk += 2) {
                        s.fill(col, sx + dir[0] * (shore + kk), sz + dir[1] * (shore + kk));
                        if (col.is != null && col.h > 59) { deep = false; break; }
                    }
                    int wx = sx + dir[0] * shore, wz = sz + dir[1] * shore;
                    if (!deep || (castle != null && Math.hypot(wx - castle[0], wz - castle[1]) < 110)) continue;
                    // a harbour wants a straight stretch of coast square to its frame: count how much of the 50 blocks
                    // either side keeps the waterline within a few blocks of this one
                    int straight = 0;
                    for (int u = -24; u <= 24; u += 3) {
                        int bx = wx - dir[1] * u, bz = wz + dir[0] * u;
                        for (int t = -5; t <= 5; t++) {
                            s.fill(col, bx + dir[0] * t, bz + dir[1] * t);
                            if (col.is == null || col.h < 62) { if (t > -5) straight++; break; }
                        }
                    }
                    double d = Math.hypot(wx - c[0], wz - c[1]) - straight * 25;
                    if (d < bestD) { bestD = d; best = new int[]{wx, wz, dir[0], dir[1]}; }
                }
            }
            AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] dock for island {},{}: {}", is.cx, is.cz, best == null ? "no calm shore" : best[0] + "," + best[1]);
            return best == null ? new int[0] : best;
        });
        return r0.length == 0 ? null : r0;
    }

    // ------------------------------------------------------------------ chapels near spawn
    private static final Map<Long, List<int[]>> CHAPELS = new ConcurrentHashMap<>();   // x, z, slot (0 = Merlin's)

    /** The five church spots for a world (the same answer worldgen uses). ground(x, z) = top solid y, MIN_VALUE on water. */
    public static List<int[]> chapelSpots(long worldSeed, java.util.function.IntBinaryOperator ground) {
        return CHAPELS.computeIfAbsent(worldSeed, seed -> {
            Random r = new Random(seed * 31 + 7);
            List<int[]> out = new ArrayList<>();
            double base = r.nextDouble() * Math.PI * 2;
            // Merlin's chapel - the one whole church, the only one in the world - stands 1000-1500 blocks from spawn, so
            // the road to the island is a journey. The four ruins are scattered 400-1600 blocks out, each at least 500
            // blocks from every other church. Each takes the flattest dry spot of its tries (the plot is cut and banked
            // into the land, so a gentle slope is fine; water or a steep hillside is not). Merlin's always gets a spot.
            for (int slot = 0; slot < 5; slot++) {
                int[] best = null; int bestRough = Integer.MAX_VALUE;
                int tries = slot == 0 ? 160 : 80;
                for (int tryN = 0; tryN < tries; tryN++) {
                    double spread = slot == 0 ? (tryN < 60 ? 1.2 : Math.PI * 2) : 1.4;
                    double ang = base + slot * Math.PI * 2 / 5 + (r.nextDouble() - 0.5) * spread;
                    double rad = slot == 0 ? 1000 + r.nextDouble() * 500 : 400 + r.nextDouble() * 1200;
                    int x = (int) (Math.cos(ang) * rad), z = (int) (Math.sin(ang) * rad);
                    boolean apart = true;
                    for (int[] o : out) if (Math.hypot(o[0] - x, o[1] - z) < 500) { apart = false; break; }
                    if (!apart) continue;
                    int g = ground.applyAsInt(x, z);
                    if (g < 63) continue;
                    int rough = 0;
                    for (int[] o : new int[][]{{16, 14}, {-16, 14}, {16, -14}, {-16, -14}, {16, 0}, {-16, 0}, {0, 14}, {0, -14}}) {
                        int h = ground.applyAsInt(x + o[0], z + o[1]);
                        rough = Math.max(rough, h == Integer.MIN_VALUE ? 99 : Math.abs(h - g));
                    }
                    if (rough < bestRough) { bestRough = rough; best = new int[]{x, z, slot}; }
                    if (rough <= 3) break;
                }
                if (best == null && slot == 0) {                       // all sea round there: take the first dry land outward
                    for (int rad = 1000; rad <= 1500 && best == null; rad += 25)
                        for (int k = 0; k < 64 && best == null; k++) {
                            double ang = base + k * Math.PI * 2 / 64;
                            int x = (int) (Math.cos(ang) * rad), z = (int) (Math.sin(ang) * rad);
                            if (ground.applyAsInt(x, z) >= 63) best = new int[]{x, z, 0};
                        }
                }
                if (best != null && (bestRough <= 8 || slot == 0)) out.add(best);
            }
            AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] churches at {} (the first is Merlin's)", out.stream().map(a -> a[0] + "," + a[1]).toList());
            return out;
        });
    }

    private Optional<GenerationStub> chapels(GenerationContext ctx) {
        List<int[]> spots = chapelSpots(ctx.seed(), (x, z) -> ground(ctx, x, z));
        ChunkPos cp = ctx.chunkPos();
        for (int[] sp : spots) {
            if (!inChunk(cp, sp[0], sp[1])) continue;
            var all = templates(ctx, "churches");
            if (all.isEmpty()) return Optional.empty();
            // Merlin's chapel is one of the besieged ones (any variant); the others are any other church, each a different one
            List<ResourceLocation> merlin = all.stream().filter(rl -> rl.getPath().contains("merlin") || rl.getPath().contains("besieged")).toList();
            List<ResourceLocation> others = new ArrayList<>(all); others.removeAll(merlin);
            if (others.isEmpty()) others = all;
            Random r = new Random(ctx.seed() ^ sp[0] * 7919L ^ sp[1]);
            List<ResourceLocation> shuffled = new ArrayList<>(others);
            Collections.shuffle(shuffled, new Random(ctx.seed() * 977));
            ResourceLocation id = sp[2] == 0 && !merlin.isEmpty() ? merlin.get(r.nextInt(merlin.size())) : shuffled.get(sp[2] % shuffled.size());
            Placed p = place(ctx, id, sp[0], sp[1], Rotation.values()[r.nextInt(4)], 0);
            return p == null ? Optional.empty() : stub(ctx, sp[0], p.y() + 1, sp[1], List.of(p));
        }
        return Optional.empty();
    }
}
