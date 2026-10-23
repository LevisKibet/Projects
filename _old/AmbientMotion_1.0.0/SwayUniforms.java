package com.ronald.ambientmotion;

import org.lwjgl.opengl.GL20;

import java.util.HashMap;
import java.util.Map;

/** Sets the sway uniforms on whatever GL program is bound (Embeddium's terrain shader), caching their locations. */
public final class SwayUniforms {
    private SwayUniforms() {}
    private static final Map<Integer, int[]> LOCATIONS = new HashMap<>();

    public static void uploadToCurrentProgram() {
        int program = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (program == 0) return;
        int[] loc = LOCATIONS.computeIfAbsent(program, p -> new int[]{
                GL20.glGetUniformLocation(p, "AmbientTime"), GL20.glGetUniformLocation(p, "AmbientSway"),
                GL20.glGetUniformLocation(p, "AmbientLantern0"), GL20.glGetUniformLocation(p, "AmbientLantern1")});
        if (loc[0] >= 0) GL20.glUniform1f(loc[0], Sway.time);
        if (loc[1] >= 0) GL20.glUniform1f(loc[1], Sway.strength);
        if (loc[2] >= 0) GL20.glUniform4f(loc[2], Sway.lantern0[0], Sway.lantern0[1], Sway.lantern0[2], Sway.lantern0[3]);
        if (loc[3] >= 0) GL20.glUniform4f(loc[3], Sway.lantern1[0], Sway.lantern1[1], Sway.lantern1[2], Sway.lantern1[3]);
    }

    /** Programs are rebuilt on a resource reload; their ids may be reused. */
    static void clear() { LOCATIONS.clear(); }
}
