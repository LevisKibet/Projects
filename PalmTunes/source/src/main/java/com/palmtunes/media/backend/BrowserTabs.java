package com.palmtunes.media.backend;

import com.palmtunes.media.MediaCommand;
import com.palmtunes.media.MediaInfo;
import com.palmtunes.media.PlaybackState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * macOS only: finds music playing in a web browser tab (YouTube, YouTube Music, Spotify Web, SoundCloud, Deezer...)
 * by running a little JavaScript in the tab through AppleScript. The page's own media-session metadata supplies
 * title, artist and cover; the media element supplies play state and position.
 * <p>
 * Chromium browsers need View > Developer > Allow JavaScript from Apple Events; Safari needs Develop > Allow
 * JavaScript from Apple Events. Only tabs on known music sites are touched.
 */
final class BrowserTabs {
    static final String SEP = "\u001f";

    /** Browser process name and whether it speaks the Safari dialect. */
    record Browser(String app, boolean safari) {
    }

    static final List<Browser> BROWSERS = List.of(
            new Browser("Google Chrome", false), new Browser("Brave Browser", false),
            new Browser("Microsoft Edge", false), new Browser("Arc", false),
            new Browser("Vivaldi", false), new Browser("Safari", true));

    /** URL fragment to friendly site name. */
    private static final String[][] SITES = {
            {"music.youtube.com", "YouTube Music"}, {"youtube.com/watch", "YouTube"}, {"youtube.com/shorts", "YouTube"},
            {"open.spotify.com", "Spotify Web"}, {"soundcloud.com", "SoundCloud"}, {"deezer.com", "Deezer"},
            {"music.apple.com", "Apple Music"}, {"tidal.com", "TIDAL"}, {"bandcamp.com", "Bandcamp"},
            {"music.amazon", "Amazon Music"}, {"pandora.com", "Pandora"}};

    record Tab(Browser browser, int window, int tab, String site, MediaInfo info, boolean jsDenied) {
    }

    private BrowserTabs() {
    }

    static String siteName(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        for (String[] s : SITES) {
            if (u.contains(s[0])) {
                return s[1];
            }
        }
        return "Browser";
    }

    private static final String READ_JS = "(function(){var m=navigator.mediaSession&&navigator.mediaSession.metadata;"
            + "var v=document.querySelector('video,audio');if(!v&&!m)return '';var s=String.fromCharCode(31);var rs=String.fromCharCode(30);var a='';"
            + "if(m&&m.artwork&&m.artwork.length)a=m.artwork[m.artwork.length-1].src;"
            + "var sh=-1,rp=-1,q=[];try{"
            + "var bar=document.querySelector('ytmusic-player-bar');"
            + "if(bar){var rm=bar.getAttribute('repeat-mode_')||bar.repeatMode||'';rp=rm==='ALL'?1:rm==='ONE'?2:rm?0:-1;"
            + "var sb=bar.querySelector('.shuffle');if(sb&&sb.getAttribute('aria-pressed')!==null)sh=sb.getAttribute('aria-pressed')==='true'?1:0;"
            + "var it=document.querySelectorAll('ytmusic-player-queue-item'),f=-1;"
            + "for(var i=0;i<it.length;i++){if(it[i].hasAttribute('selected')){f=i;break;}}"
            + "if(f>=0){for(var j=f+1;j<it.length&&q.length<4;j++){var t=it[j].querySelector('.song-title'),ar=it[j].querySelector('.byline');"
            + "if(t)q.push(t.textContent.trim()+(ar&&ar.textContent.trim()?' - '+ar.textContent.trim():''));}}}"
            + "else{var ss=document.querySelector('[data-testid=control-button-shuffle]');"
            + "if(ss){sh=ss.getAttribute('aria-checked')==='true'?1:0;var rr=document.querySelector('[data-testid=control-button-repeat]');"
            + "if(rr){var c=rr.getAttribute('aria-checked');rp=c==='mixed'?2:c==='true'?1:0;}}"
            + "else{if(v)rp=v.loop?2:0;var cv=document.querySelectorAll('ytd-compact-video-renderer #video-title');"
            + "for(var k=0;k<cv.length&&q.length<4;k++){q.push(cv[k].textContent.trim());}}}"
            + "}catch(e){}"
            + "return [v&&!v.paused&&!v.ended?'playing':'paused',m&&m.title?m.title:document.title,m?m.artist:'',m?m.album:'',"
            + "v&&isFinite(v.duration)?v.duration:-1,v?v.currentTime:-1,a,sh,rp,q.join(rs)].join(s);})()";

    /** AppleScript that lists every music tab of the browser, one line per tab. */
    static List<String> listScript(Browser b) {
        StringBuilder cond = new StringBuilder();
        for (String[] s : SITES) {
            if (cond.length() > 0) {
                cond.append(" or ");
            }
            cond.append("(u contains \"").append(s[0]).append("\")");
        }
        String run = b.safari() ? "do JavaScript \"" + READ_JS + "\" in t" : "execute t javascript \"" + READ_JS + "\"";
        String title = b.safari() ? "name of t" : "title of t";
        List<String> l = new ArrayList<>();
        l.add("tell application \"" + b.app() + "\"");
        l.add("set out to \"\"");
        l.add("set d to character id 31");
        l.add("set wi to 0");
        l.add("repeat with w in windows");
        l.add("set wi to wi + 1");
        l.add("set ti to 0");
        l.add("repeat with t in tabs of w");
        l.add("set ti to ti + 1");
        l.add("try");
        l.add("set u to URL of t");
        l.add("if " + cond + " then");
        l.add("try");
        l.add("set r to " + run);
        l.add("on error errMsg");
        l.add("set r to \"ERR:\" & errMsg");
        l.add("end try");
        l.add("set out to out & wi & d & ti & d & (" + title + ") & d & u & d & r & linefeed");
        l.add("end if");
        l.add("end try");
        l.add("end repeat");
        l.add("end repeat");
        l.add("return out");
        l.add("end tell");
        return l;
    }

    /** Parses {@link #listScript} output. */
    static List<Tab> parse(Browser b, String output) {
        List<Tab> tabs = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split(SEP, -1);
            if (f.length < 5) {
                continue;
            }
            int w;
            int t;
            try {
                w = Integer.parseInt(f[0].trim());
                t = Integer.parseInt(f[1].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            String site = siteName(f[3]);
            if (f[4].startsWith("ERR:")) {
                boolean denied = f[4].toLowerCase(Locale.ROOT).contains("apple events");
                tabs.add(new Tab(b, w, t, site, null, denied));
                continue;
            }
            if (f.length < 11 || f[4].isBlank()) {
                continue;
            }
            PlaybackState state = PlaybackState.parse(f[4]);
            double dur = num(f[8]);
            double pos = num(f[9]);
            String art = f[10].trim();
            if (!art.startsWith("http") && !art.startsWith("data:")) {
                art = null;
            }
            String title = f[5].replaceAll("\\s+-\\s+YouTube( Music)?$", "").trim();
            if (title.isBlank()) {
                continue;
            }
            int shuffle = f.length > 11 ? (int) num(f[11]) : -1;
            int repeat = f.length > 12 ? (int) num(f[12]) : -1;
            List<String> queue = new ArrayList<>();
            if (f.length > 13) {
                for (String q : f[13].split("\u001e")) {
                    if (!q.isBlank()) {
                        queue.add(q.trim());
                    }
                }
            }
            tabs.add(new Tab(b, w, t, site, new MediaInfo(title, f[6], f[7], site, state,
                    pos < 0 ? -1 : Math.round(pos * 1000), dur <= 0 ? -1 : Math.round(dur * 1000), art, null,
                    shuffle, repeat, queue), false));
        }
        return tabs;
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** AppleScript that sends a transport command to one tab. */
    static List<String> commandScript(Tab tab, MediaCommand command, double seconds) {
        String js = switch (command) {
            case PLAY_PAUSE -> "var b=document.querySelector('#play-pause-button,.ytp-play-button,[data-testid=control-button-playpause]');"
                    + "if(b){b.click();}else{var v=document.querySelector('video,audio');if(v){if(v.paused){v.play();}else{v.pause();}}}";
            case NEXT -> "var b=document.querySelector('ytmusic-player-bar .next-button,.ytp-next-button,[data-testid=control-button-skip-forward],button[aria-label*=Next i]');"
                    + "if(b){b.click();}";
            case PREVIOUS -> "var b=document.querySelector('ytmusic-player-bar .previous-button,[data-testid=control-button-skip-back],button[aria-label*=Previous i]');"
                    + "if(b){b.click();}else{var v=document.querySelector('video,audio');if(v){v.currentTime=0;}}";
            case SEEK -> "var v=document.querySelector('video,audio');if(v){v.currentTime=" + String.format(Locale.ROOT, "%.3f", seconds) + ";}";
            case TOGGLE_SHUFFLE -> "var b=document.querySelector('ytmusic-player-bar .shuffle,[data-testid=control-button-shuffle]');if(b){b.click();}";
            case CYCLE_REPEAT -> "var b=document.querySelector('ytmusic-player-bar .repeat,[data-testid=control-button-repeat]');"
                    + "if(b){b.click();}else{var v=document.querySelector('video,audio');if(v){v.loop=!v.loop;}}";
        };
        String body = "(function(){" + js + "})()";
        String app = tab.browser().app();
        if (tab.browser().safari()) {
            return List.of("tell application \"Safari\" to do JavaScript \"" + body + "\" in tab " + tab.tab() + " of window " + tab.window());
        }
        return List.of("tell application \"" + app + "\" to execute tab " + tab.tab() + " of window " + tab.window()
                + " javascript \"" + body + "\"");
    }
}
