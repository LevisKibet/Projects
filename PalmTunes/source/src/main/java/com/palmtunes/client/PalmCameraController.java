package com.palmtunes.client;

import com.palmtunes.PalmConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Works out the over-the-shoulder camera used while the palm panel is open. */
public final class PalmCameraController {

    public record Result(Vec3 position, float yRot, float xRot) {
    }

    private PalmCameraController() {
    }

    /**
     * @param camera the camera after vanilla has placed it (ordinary third-person)
     * @return where the camera should be instead, or null to leave it alone
     */
    public static Result compute(Camera camera, Entity entity, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (entity != mc.player || !PalmController.isOverriding()) {
            return null;
        }
        float p = PalmController.eased(partialTick);
        PalmConfig cfg = PalmConfig.CLIENT;

        // Use the body's direction, not the head's, because the arm is attached to the body.
        float bodyYaw = entity instanceof LivingEntity living
                ? Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot)
                : entity.getViewYRot(partialTick);
        float yawRad = bodyYaw * Mth.DEG_TO_RAD;
        Vec3 forward = new Vec3(-Mth.sin(yawRad), 0, Mth.cos(yawRad));
        Vec3 right = new Vec3(-Mth.cos(yawRad), 0, -Mth.sin(yawRad));

        Vec3 eye = entity.getEyePosition(partialTick);
        Vec3 desired = eye
                .add(forward.scale(cfg.cameraForward.get()))
                .add(right.scale(cfg.cameraSide.get()))
                .add(0, cfg.cameraHeight.get(), 0);

        // Keep the camera out of walls: stop short of the first block between the eye and the camera.
        HitResult hit = entity.level().clip(new ClipContext(eye, desired, ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, entity));
        if (hit.getType() != HitResult.Type.MISS) {
            Vec3 along = desired.subtract(eye);
            double length = along.length();
            if (length > 1.0e-4) {
                double usable = Math.max(0.35, hit.getLocation().distanceTo(eye) - 0.25);
                desired = eye.add(along.scale(Math.min(1.0, usable / length)));
            }
        }

        Vec3 aim = eye
                .add(forward.scale(cfg.lookAhead.get()))
                .add(right.scale(cfg.lookSide.get()))
                .add(0, cfg.lookHeight.get(), 0);

        return blend(camera, entity, partialTick, p, PalmController.startedFirstPerson(), desired, aim);
    }

    /**
     * Flies from where the camera was when the effect started to {@code desired}, looking at {@code aim}.
     * From first person the shot starts exactly at the eyes, looking where the player looks, and flies out
     * while the aim point swings round; from third person it blends from wherever vanilla put the camera.
     */
    public static Result blend(Camera camera, Entity entity, float partialTick, float p, boolean fromFirstPerson,
                               Vec3 desired, Vec3 aim) {
        Vec3 eye = entity.getEyePosition(partialTick);
        if (fromFirstPerson) {
            Vec3 view = entity.getViewVector(partialTick);
            Vec3 startAim = eye.add(view.scale(6.0));
            return lookAt(eye.lerp(desired, p), startAim.lerp(aim, p));
        }
        Result target = lookAt(desired, aim);
        Vec3 position = camera.getPosition().lerp(desired, p);
        return new Result(position,
                Mth.rotLerp(p, camera.getYRot(), target.yRot()),
                Mth.lerp(p, camera.getXRot(), target.xRot()));
    }

    static Result lookAt(Vec3 from, Vec3 to) {
        Vec3 dir = to.subtract(from);
        double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float yaw = (float) (Mth.atan2(-dir.x, dir.z) * (180.0 / Math.PI));
        float pitch = (float) (-(Mth.atan2(dir.y, horizontal) * (180.0 / Math.PI)));
        return new Result(from, yaw, pitch);
    }
}
