package com.ronald.chroma.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One placed line: its points and their curve handles, its colours, pattern and speed, and the glow blocks lighting it.
 * Lives in the anchor block at the line's first point, so it saves and loads with that chunk.
 */
public class LineEntity extends BlockEntity {
    public static final int MAX_POINTS = 32;
    public static final double MAX_SPAN = 24;
    /** String lights: the first bulb this far along, then one every BULB_GAP. */
    public static final double BULB_START = 0.25, BULB_GAP = 0.5;

    private int kind = Curve.STRIP;
    private final List<Curve.Pt> pts = new ArrayList<>();
    private int[] colours = {Curve.VIVID[0]};
    private int mode = Curve.SOLID, speed = 1;
    private final List<BlockPos> glows = new ArrayList<>();
    /** Bumped whenever the shape changes, so the client knows to rebuild its cached samples. */
    public int revision;

    public LineEntity(BlockPos pos, BlockState state) { super(ChromaContent.LINE.get(), pos, state); }

    public int kind() { return kind; }
    public List<Curve.Pt> points() { return Collections.unmodifiableList(pts); }
    public int[] colours() { return colours; }
    public int mode() { return mode; }
    public int speed() { return speed; }

    public void start(int kind, Curve.Pt first) {
        this.kind = kind;
        pts.clear();
        pts.add(first);
        colours = kind == Curve.BULBS ? Curve.CLASSIC.clone() : new int[]{Curve.VIVID[0]};
        mode = Curve.SOLID;
        changed();
    }

    // ------------------------------------------------------------------ edits (server side)

    public boolean addPoint(Curve.Pt p) {
        if (pts.size() >= MAX_POINTS) return false;
        if (!pts.isEmpty() && pts.get(pts.size() - 1).pos().distanceTo(p.pos()) > MAX_SPAN) return false;
        if (!pts.isEmpty() && pts.get(pts.size() - 1).pos().distanceTo(p.pos()) < 0.2) return false;
        pts.add(p);
        changed();
        return true;
    }

    /** Pull a point's curve handle out (dir 1) or in (dir -1), a quarter block at a time. */
    public void growHandle(int i, int dir) {
        if (i < 0 || i >= pts.size()) return;
        Curve.Pt p = pts.get(i);
        Vec3 h = p.handle();
        double len = h.length();
        if (len < 1e-3) {
            if (dir < 0) return;
            h = tangentAt(i);
            len = 0;
        } else h = h.normalize();
        double nl = Math.max(0, Math.min(4, len + 0.25 * dir));
        pts.set(i, p.withHandle(nl < 1e-3 ? Vec3.ZERO : h.scale(nl)));
        changed();
    }

    /** Turn a point's handle 15 degrees round the surface it sits on. */
    public void turnHandle(int i) {
        if (i < 0 || i >= pts.size()) return;
        Curve.Pt p = pts.get(i);
        Vec3 h = p.handle();
        if (h.length() < 1e-3) h = tangentAt(i).scale(0.5);
        pts.set(i, p.withHandle(Curve.rotate(h, p.normal(), Math.toRadians(15))));
        changed();
    }

    public boolean removePoint(int i) {
        if (pts.size() <= 2 || i < 0 || i >= pts.size()) return false;
        pts.remove(i);
        changed();
        return true;
    }

    public void cycleMode() { mode = (mode + 1) % 4; changed(); }
    public void cycleSpeed() { speed = (speed + 1) % Curve.SPEEDS.length; changed(); }

    /** Dye the line one colour, or (adding) put another colour into its mix. */
    public void dye(DyeColor dye, boolean add) {
        int c = Curve.VIVID[dye.getId()];
        if (add) {
            if (colours.length >= 6) return;
            int[] n = java.util.Arrays.copyOf(colours, colours.length + 1);
            n[colours.length] = c;
            colours = n;
            if (kind == Curve.STRIP && mode == Curve.SOLID) mode = Curve.FLOW;
        } else colours = new int[]{c};
        changed();
    }

    /** Every colour of the rainbow, flowing along it (set with an amethyst shard). */
    public void rainbow() {
        DyeColor[] order = {DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.LIME, DyeColor.LIGHT_BLUE, DyeColor.BLUE, DyeColor.PURPLE, DyeColor.MAGENTA};
        colours = new int[order.length];
        for (int i = 0; i < order.length; i++) colours[i] = Curve.VIVID[order[i].getId()];
        if (kind == Curve.STRIP) mode = Curve.FLOW;
        changed();
    }

    /** Set a whole line up at once (for the demo scene). */
    public void setUp(int kind, java.util.List<Curve.Pt> points, int[] cols, int mode) {
        this.kind = kind;
        pts.clear();
        pts.addAll(points);
        colours = cols.clone();
        this.mode = mode;
        changed();
    }

    /** The way the line runs through point i, flat along its surface. */
    private Vec3 tangentAt(int i) {
        Vec3 a = pts.get(Math.max(0, i - 1)).pos(), b = pts.get(Math.min(pts.size() - 1, i + 1)).pos();
        Vec3 d = b.subtract(a);
        Vec3 n = pts.get(i).normal();
        d = d.subtract(n.scale(d.dot(n)));
        if (d.lengthSqr() < 1e-6) d = n.cross(new Vec3(0, 1, 0.001));
        return d.normalize();
    }

    private void changed() {
        revision++;
        setChanged();
        if (level != null && !level.isClientSide) {
            placeGlows();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    // ------------------------------------------------------------------ the light it gives off

    /** Fill the air along the line with glow blocks every couple of blocks, coloured to match. */
    void placeGlows() {
        if (level == null || level.isClientSide) return;
        Set<BlockPos> want = new LinkedHashSet<>();
        List<Curve.Sample> samples = Curve.sample(pts, 0.25);
        double next = 0.4;
        java.util.Map<BlockPos, Integer> colourAt = new java.util.HashMap<>();
        for (Curve.Sample s : samples) {
            if (s.s() < next) continue;
            next = s.s() + 1.5;
            BlockPos at = BlockPos.containing(s.pos().add(s.normal().scale(0.3)));
            if (at.equals(worldPosition)) continue;
            if (want.add(at)) colourAt.put(at, Curve.castColour(colours));   // the whole line casts one blended colour
        }
        for (BlockPos old : glows)
            if (!want.contains(old) && level.getBlockState(old).getBlock() instanceof GlowBlock) level.setBlock(old, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        glows.clear();
        for (BlockPos p : want) {
            BlockState here = level.getBlockState(p);
            if (!here.isAir() && !(here.getBlock() instanceof GlowBlock)) continue;
            BlockState glow = ChromaContent.GLOWS.get(Curve.nearestGlow(colourAt.get(p))).get().defaultBlockState()
                    .setValue(GlowBlock.BRIGHT, kind == Curve.STRIP);
            if (here != glow) level.setBlock(p, glow, Block.UPDATE_ALL);
            glows.add(p);
        }
    }

    public void clearGlows() {
        if (level == null || level.isClientSide) return;
        for (BlockPos p : glows)
            if (level.getBlockState(p).getBlock() instanceof GlowBlock) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        glows.clear();
    }

    // ------------------------------------------------------------------ saving and syncing

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        writeLine(tag);
        long[] g = new long[glows.size()];
        for (int i = 0; i < g.length; i++) g[i] = glows.get(i).asLong();
        tag.putLongArray("Glows", g);
    }

    private void writeLine(CompoundTag tag) {
        tag.putInt("Kind", kind);
        tag.putInt("Mode", mode);
        tag.putInt("Speed", speed);
        tag.putIntArray("Colours", colours);
        ListTag list = new ListTag();
        for (Curve.Pt p : pts) {
            CompoundTag c = new CompoundTag();
            c.putDouble("X", p.pos().x); c.putDouble("Y", p.pos().y); c.putDouble("Z", p.pos().z);
            c.putByte("F", (byte) p.face().get3DDataValue());
            c.putFloat("HX", (float) p.handle().x); c.putFloat("HY", (float) p.handle().y); c.putFloat("HZ", (float) p.handle().z);
            list.add(c);
        }
        tag.put("Points", list);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        kind = tag.getInt("Kind");
        mode = tag.getInt("Mode");
        speed = tag.contains("Speed") ? tag.getInt("Speed") : 1;
        int[] c = tag.getIntArray("Colours");
        colours = c.length == 0 ? new int[]{Curve.VIVID[0]} : c;
        pts.clear();
        for (Tag t : tag.getList("Points", Tag.TAG_COMPOUND)) {
            CompoundTag p = (CompoundTag) t;
            pts.add(new Curve.Pt(new Vec3(p.getDouble("X"), p.getDouble("Y"), p.getDouble("Z")), Direction.from3DDataValue(p.getByte("F")),
                    new Vec3(p.getFloat("HX"), p.getFloat("HY"), p.getFloat("HZ"))));
        }
        if (tag.contains("Glows")) {
            glows.clear();
            for (long l : tag.getLongArray("Glows")) glows.add(BlockPos.of(l));
        }
        revision++;
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        writeLine(tag);
        return tag;
    }

    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override
    public AABB getRenderBoundingBox() {
        if (pts.isEmpty()) return new AABB(worldPosition).inflate(1);
        double x0 = Double.MAX_VALUE, y0 = x0, z0 = x0, x1 = -x0, y1 = -x0, z1 = -x0;
        for (Curve.Pt p : pts) {
            Vec3 h = p.handle();
            double r = h.length() + 0.5;
            x0 = Math.min(x0, p.pos().x - r); y0 = Math.min(y0, p.pos().y - r); z0 = Math.min(z0, p.pos().z - r);
            x1 = Math.max(x1, p.pos().x + r); y1 = Math.max(y1, p.pos().y + r); z1 = Math.max(z1, p.pos().z + r);
        }
        return new AABB(x0, y0, z0, x1, y1, z1).inflate(5);   // the light it throws reaches a few blocks out
    }

    // ------------------------------------------------------------------ client bookkeeping (for picking lines with the tool)

    public static final Set<LineEntity> CLIENT_LINES = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) CLIENT_LINES.add(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        CLIENT_LINES.remove(this);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        CLIENT_LINES.remove(this);
    }
}
