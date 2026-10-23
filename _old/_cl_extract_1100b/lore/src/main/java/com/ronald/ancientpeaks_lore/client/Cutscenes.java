package com.ronald.ancientpeaks_lore.client;

import com.ronald.ancientpeaks_lore.LoreContent;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.Input;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Cinematic scenes: the camera leaves the player and glides along set shots around the character (positions are
 * relative to them: forward = the way they face, right, up), zoomed in; black bars close in top and bottom, the
 * words appear as subtitles at the bottom middle, and the scene's music plays. The player can't move meanwhile.
 * The server keeps the same clock (Merlin vanishes, Vortigern wakes) - change both together.
 */
public final class Cutscenes {
    private Cutscenes() {}

    /** A camera move from offset a to offset b (forward, right, up), looking at the character's point lookA->lookB high. */
    record Shot(int from, int to, double[] a, double[] b, double lookA, double lookB, double fovA, double fovB) {}
    record Line(int from, int to, String who, String text) {}
    record Scene(int length, List<Shot> shots, List<Line> lines, Supplier<SoundEvent> music) {}

    static double[] v(double fwd, double right, double up) { return new double[]{fwd, right, up}; }

    // ------------------------------------------------------------------ the scenes (20 ticks = 1 second)
    static List<Line> lines(List<com.ronald.ancientpeaks_lore.LoreScript.Said> said) {
        return said.stream().map(l -> new Line(l.from(), l.to(), l.who(), l.text())).toList();
    }
    static final int INTRO = com.ronald.ancientpeaks_lore.LoreScript.MERLIN_INTRO_LENGTH;
    static final int FAREWELL = com.ronald.ancientpeaks_lore.LoreScript.MERLIN_FAREWELL_LENGTH;

    static final Map<String, Scene> SCENES = Map.of(
            // first meeting, upstairs: in from the stair head to the old man slumped against the wall
            "merlin_intro", new Scene(INTRO, List.of(
                    new Shot(0, INTRO * 2 / 5, v(4.2, -2.0, 1.2), v(2.6, -0.6, 1.0), 0.9, 1.0, 50, 40),
                    new Shot(INTRO * 2 / 5, INTRO, v(1.9, 0.9, 0.9), v(1.4, 0.4, 1.0), 1.0, 1.05, 38, 32)),
                    lines(com.ronald.ancientpeaks_lore.LoreScript.MERLIN_INTRO),
                    () -> LoreContent.MUSIC_MERLIN.get()),
            "merlin", new Scene(FAREWELL, List.of(
                    // one long, slow drift: from wide and low across the floor in to his face (he is sitting)
                    new Shot(0, FAREWELL - 110, v(3.8, -2.4, 0.9), v(1.6, 0.7, 1.0), 0.95, 1.0, 46, 34),
                    // then up and away as he fades into light
                    new Shot(FAREWELL - 110, FAREWELL, v(2.0, 1.4, 1.5), v(4.6, 2.6, 3.0), 0.8, 0.6, 40, 58)),
                    lines(com.ronald.ancientpeaks_lore.LoreScript.MERLIN_FAREWELL),
                    () -> LoreContent.MUSIC_MERLIN.get()),
            "vortigern", new Scene(320, List.of(
                    // one slow orbit round the statue as the stone cracks, ending close on the stone face
                    new Shot(0, 158, v(4.4, -3.2, 0.6), v(2.0, 0.6, 2.5), 2.2, 2.6, 50, 34),
                    // he is free: a long pull back to the wide shot of the summit
                    new Shot(158, 320, v(2.4, -1.6, 2.4), v(9.5, 3.5, 3.8), 2.4, 1.9, 38, 62)),
                    lines(com.ronald.ancientpeaks_lore.LoreScript.VORTIGERN_WAKE),
                    () -> LoreContent.MUSIC_VORTIGERN.get()));

    // ------------------------------------------------------------------ playing one
    private static Scene scene;
    private static int t, shotIndex = -1;
    private static Vec3 anchor, fwd, right;
    private static Marker cam;
    private static CameraType oldCamera;
    private static SoundInstance music;

    public static boolean active() { return scene != null; }

    public static void start(String id, double x, double y, double z, float yaw) {
        Minecraft mc = Minecraft.getInstance();
        Scene s = SCENES.get(id);
        if (s == null || mc.level == null || mc.player == null) return;
        if (active()) stop();
        scene = s; t = 0; shotIndex = -1;
        anchor = new Vec3(x, y, z);
        double r = Math.toRadians(yaw);
        fwd = new Vec3(-Math.sin(r), 0, Math.cos(r));
        right = new Vec3(-fwd.z, 0, fwd.x);
        cam = EntityType.MARKER.create(mc.level);
        if (cam == null) { scene = null; return; }
        Vec3 p = posAt(0);
        cam.setPos(p.x, p.y, p.z); cam.xo = p.x; cam.yo = p.y; cam.zo = p.z;
        oldCamera = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.setCameraEntity(cam);
        mc.getMusicManager().stopPlaying();
        SoundEvent m = s.music.get();
        if (m != null) {
            music = new SimpleSoundInstance(m.getLocation(), SoundSource.RECORDS, 1.0f, 1.0f, RandomSource.create(), false, 0,
                    SoundInstance.Attenuation.NONE, 0, 0, 0, true);
            mc.getSoundManager().play(music);
        }
    }

    static void stop() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.setCameraEntity(mc.player);
        if (oldCamera != null) mc.options.setCameraType(oldCamera);
        if (music != null) mc.getSoundManager().stop(music);
        scene = null; cam = null; music = null; oldCamera = null;
    }

    private static Shot shotAt(double time) {
        Shot last = scene.shots.get(0);
        for (Shot s : scene.shots) { if (time >= s.from) last = s; }
        return last;
    }

    private static double ease(Shot s, double time) {
        double k = Mth.clamp((time - s.from) / Math.max(1, s.to - s.from), 0, 1);
        return k * k * (3 - 2 * k);
    }

    private static Vec3 place(double[] o) { return anchor.add(fwd.scale(o[0])).add(right.scale(o[1])).add(0, o[2], 0); }

    private static Vec3 posAt(double time) {
        Shot s = shotAt(time); double e = ease(s, time);
        return place(s.a).lerp(place(s.b), e);
    }

    private static Vec3 lookAt(double time) {
        Shot s = shotAt(time); double e = ease(s, time);
        return anchor.add(0, Mth.lerp(e, s.lookA, s.lookB), 0);
    }

    // ------------------------------------------------------------------ events
    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(Cutscenes::tick);
        MinecraftForge.EVENT_BUS.addListener(Cutscenes::angles);
        MinecraftForge.EVENT_BUS.addListener(Cutscenes::fov);
        MinecraftForge.EVENT_BUS.addListener(Cutscenes::freeze);
        MinecraftForge.EVENT_BUS.addListener(Cutscenes::noClicks);
        MinecraftForge.EVENT_BUS.addListener(Cutscenes::hideHud);
        MinecraftForge.EVENT_BUS.addListener(Cutscenes::drawOverlay);
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> { if (active()) stop(); });
    }

    private static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { stop(); return; }
        if (mc.isPaused()) return;
        t++;
        if (t >= scene.length) { stop(); return; }
        int idx = scene.shots.indexOf(shotAt(t));
        Vec3 p = posAt(t);
        if (idx != shotIndex) {                       // a cut: no sweep across from the last shot
            shotIndex = idx;
            cam.xo = p.x; cam.yo = p.y; cam.zo = p.z;
        } else {
            cam.xo = cam.getX(); cam.yo = cam.getY(); cam.zo = cam.getZ();
        }
        cam.setPos(p.x, p.y, p.z);
        if (mc.getCameraEntity() != cam) mc.setCameraEntity(cam);
    }

    private static void angles(ViewportEvent.ComputeCameraAngles e) {
        if (!active()) return;
        Vec3 from = e.getCamera().getPosition();
        Vec3 to = lookAt(t - 1 + e.getPartialTick());
        Vec3 d = to.subtract(from);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        e.setYaw((float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f);
        e.setPitch((float) (-(Mth.atan2(d.y, h) * Mth.RAD_TO_DEG)));
        e.setRoll(0);
    }

    private static void fov(ViewportEvent.ComputeFov e) {
        if (!active()) return;
        double time = t - 1 + e.getPartialTick();
        Shot s = shotAt(time);
        e.setFOV(Mth.lerp(ease(s, time), s.fovA, s.fovB));
    }

    private static void freeze(MovementInputUpdateEvent e) {
        if (!active()) return;
        Input in = e.getInput();
        in.forwardImpulse = 0; in.leftImpulse = 0;
        in.up = in.down = in.left = in.right = in.jumping = in.shiftKeyDown = false;
    }

    private static void noClicks(InputEvent.InteractionKeyMappingTriggered e) {
        if (!active()) return;
        e.setCanceled(true); e.setSwingHand(false);
    }

    private static void hideHud(RenderGuiOverlayEvent.Pre e) {
        if (active()) e.setCanceled(true);
    }

    /** Letterbox bars, and the words at the bottom middle. */
    private static void drawOverlay(RenderGuiEvent.Post e) {
        if (!active()) return;
        GuiGraphics g = e.getGuiGraphics();
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int W = g.guiWidth(), H = g.guiHeight();
        double time = t + e.getPartialTick();
        double in = Mth.clamp(time / 16.0, 0, 1), out = Mth.clamp((scene.length - time) / 16.0, 0, 1);
        int bar = (int) (H * 0.12 * Math.min(in, out));
        g.fill(0, 0, W, bar, 0xFF000000);
        g.fill(0, H - bar, W, H, 0xFF000000);

        for (Line l : scene.lines) {
            if (time < l.from || time > l.to) continue;
            double a = Math.min(Mth.clamp((time - l.from) / 8.0, 0, 1), Mth.clamp((l.to - time) / 8.0, 0, 1));
            int alpha = (int) (a * 255);
            if (alpha < 8) continue;
            int wrap = Math.min(W - 40, 340);
            List<FormattedCharSequence> rows = font.split(Component.literal(l.text), wrap);
            int lineH = 11, total = rows.size() * lineH + (l.who.isEmpty() ? 0 : 12);
            int y = H - Math.max(bar, (int) (H * 0.12)) - 10 - total;
            int boxW = 0;
            for (FormattedCharSequence r : rows) boxW = Math.max(boxW, font.width(r));
            g.fill(W / 2 - boxW / 2 - 8, y - 5, W / 2 + boxW / 2 + 8, y + total + 3, ((int) (a * 120)) << 24);
            if (!l.who.isEmpty()) {
                String who = l.who.toUpperCase();
                g.pose().pushPose();
                g.pose().translate(W / 2f, y, 0); g.pose().scale(0.75f, 0.75f, 1);
                g.drawString(font, who, -font.width(who) / 2, 0, (alpha << 24) | 0xE0B050, true);
                g.pose().popPose();
                y += 12;
            }
            for (FormattedCharSequence r : rows) {
                int color = l.who.isEmpty() ? 0xBFBFBF : 0xF2EEE6;
                g.drawString(font, r, W / 2 - font.width(r) / 2, y, (alpha << 24) | color, true);
                y += lineH;
            }
        }
    }
}
