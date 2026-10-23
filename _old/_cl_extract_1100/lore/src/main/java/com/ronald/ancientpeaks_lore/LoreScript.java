package com.ronald.ancientpeaks_lore;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the characters say, in one place, with the timing of each cutscene's subtitles worked out from the
 * length of its lines - so the client's cutscenes (client/Cutscenes) and the server's clock (Merlin hands over his map
 * on the last line, then vanishes) always agree. 20 ticks = 1 second.
 */
public final class LoreScript {
    private LoreScript() {}

    /** One subtitle: shown from tick `from` to `to` of its scene. An empty speaker is a stage direction. */
    public record Said(int from, int to, String who, String text) {}

    /** Lines one after another from `start`: each stays up long enough to read (2 s plus about 17 letters a second). */
    static List<Said> timed(int start, String who, String... lines) {
        List<Said> out = new ArrayList<>();
        int t = start;
        for (String l : lines) {
            int len = 40 + (int) Math.round(l.length() * 1.15);
            out.add(new Said(t, t + len, who, l));
            t += len + 8;
        }
        return out;
    }
    static int end(List<Said> s) { return s.get(s.size() - 1).to(); }

    // ------------------------------------------------------------------ Merlin
    /** 1. First encounter - when you first speak to him upstairs, the raiders below cleared out. Then the siege begins. */
    public static final List<Said> MERLIN_INTRO = timed(14, "Merlin",
            "Someone heard the bell. Good.",
            "Thank you for dealing with that lot downstairs. They followed me in here.",
            "I'm hurt, and there are more of them coming. If you have a healing potion, I need it now.");
    public static final int MERLIN_INTRO_LENGTH = end(MERLIN_INTRO) + 24;

    /** 2. Spoken to again during the siege, without the cure. */
    public static final String MERLIN_SIEGE = "A healing potion. Drink one into me or splash it, either way. Just keep them off me.";
    /** 3. The siege lost: the time ran out, or everyone left. */
    public static final String MERLIN_FAIL = "Not today, then. Come back when you're ready for them.";

    /** 4. Farewell - healed, he drives off the raiders, tells the tale, hands over his map and fades into light. */
    public static final List<Said> MERLIN_FAREWELL = timed(14, "Merlin",
            "That's better. Thank you.",
            "It won't last, so listen. West, across the sea, there's an island called Logres.",
            "Arthur fought Vortigern there, on the high meadow, a hundred winters ago. Vortigern cheated, and Arthur was dying.",
            "So he drove Excalibur into the stone I'd warded, and it took his life and turned Vortigern to stone beside him.",
            "Take my map. It shows you the way across. With my blessing you can draw the sword. Be ready when you do.");
    /** When he gives the map and his blessing (as the last line begins), and when he is gone. */
    public static final int MERLIN_MAP_AT = MERLIN_FAREWELL.get(MERLIN_FAREWELL.size() - 1).from() + 10;
    public static final int MERLIN_FAREWELL_LENGTH = end(MERLIN_FAREWELL) + 50;

    // ------------------------------------------------------------------ Vortigern
    /** 1. Awakening - plays when a blessed player draws Excalibur; the statue's clock (SCENE) is the scene's length. */
    public static final List<Said> VORTIGERN_WAKE = List.of(
            new Said(16, 96, "", "The heavy sound of stone cracking open under immense pressure..."),
            new Said(150, 206, "Vortigern", "Arthur! Where are you?! Show yourself!"),
            new Said(212, 266, "Vortigern", "...Gone? Dead by his own cowardly trick?"),
            new Said(272, 318, "Vortigern", "No matter. The sword is mine now, and nothing stands in my way!"));
    /** 2. At half health. */
    public static final String VORTIGERN_PHASE_TWO = "Enough!";
    /** 3. Defeated. */
    public static final String VORTIGERN_DEFEAT = "My... purpose... excal...";
}
