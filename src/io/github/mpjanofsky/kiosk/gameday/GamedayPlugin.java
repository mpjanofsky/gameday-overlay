// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Follows up to six TeamTracker sensors and keeps a small bar over the kiosk in
 * step with one of their matches (Pick says which). Entity events carry score and clock changes. A
 * 30-second tick covers what no event announces: the pre-game window
 * opening, the countdown, and the final score's hold running out.
 *
 * KS callbacks and the tick share state, so every entry point is
 * synchronized. None of them block, so the 3-second callback limit holds.
 */
public final class GamedayPlugin implements KioskPlugin {
    static final String KEY = "bug";
    private static final long TICK_SECONDS = 30;
    /**
     * 0.5 kept teams in three pickers; 0.6 keeps them in the "teams" list. The manifest still declares the pickers,
     * always hidden, so an upgraded kiosk hands over what they held and configure moves it into the list.
     */
    private static final String[] LEGACY_KEYS = {"teamEntity", "team2Entity", "team3Entity"};
    /** The list's maxItems. Three pickers and the old three rows fit, so the move never drops a team. */
    private static final int SLOTS = 6;

    private final LongSupplier clock;
    private final boolean ticking;
    private final BrandStore.Fetcher fetcher;
    private BrandStore brands;
    private PluginHost host;
    private ScheduledExecutorService tick;

    /** Per team slot: the sensor, its latest status and its match. An empty entity is an unused slot. */
    private final String[] entities = new String[SLOTS];
    private final String[] entityStatuses = new String[SLOTS];
    private final Match[] matches = new Match[SLOTS];
    /** Per team slot: what the bar would show for it right now. Pick chooses among them. */
    private final Display[] candidates = new Display[SLOTS];
    /** The slot whose match is on the bar, or -1. Pick holds it until it drops out or a higher rank appears. */
    private int shownSlot = -1;
    private int preGameHours = 3;
    private int finalMinutes = 30;
    private String anchor = OverlaySpec.TOP;
    private int textSp = 16;
    private String style = "Crests";
    private String confetti = Effects.SHOWER;
    private boolean onTop = true;
    private String preview = "Off";
    private boolean demoArmed;
    private long previewSince;

    private boolean unsupported;
    private BugOverlay overlay;
    /** The spec the bar is shown with, or null while it is hidden. A change of spec replaces the overlay. */
    private String shownSpec;
    /** Bumped on every show and hide, so a fade that finishes after a newer show leaves that show alone. */
    private int generation;
    private Display last = Display.HIDDEN;
    private String lastStatus;

    {
        Arrays.fill(entities, "");
        Arrays.fill(entityStatuses, "connecting");
        Arrays.fill(candidates, Display.HIDDEN);
    }

    public GamedayPlugin() { this(System::currentTimeMillis, true, BrandStore::httpGet); }

    /** Tests pass a null fetcher (no network) or a canned one. */
    GamedayPlugin(LongSupplier clock, boolean ticking, BrandStore.Fetcher fetcher) {
        this.clock = clock;
        this.ticking = ticking;
        this.fetcher = fetcher;
    }

    GamedayPlugin(LongSupplier clock, boolean ticking) { this(clock, ticking, null); }

    @Override public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        brands = new BrandStore(fetcher, clock, this::onBrandsLoaded);
        configure(settings);
        if (ticking) {
            tick = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gameday-tick");
                t.setDaemon(true);
                return t;
            });
            tick.scheduleWithFixedDelay(this::onTick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
            armDemo();
        }
    }

    @Override public synchronized void configure(Map<String, Object> settings) {
        if (host == null) return;
        preGameHours = number(settings, "preGameHours", 3);
        finalMinutes = number(settings, "finalMinutes", 30);
        anchor = anchorFor(text(settings, "position", "Top"));
        textSp = spFor(text(settings, "size", "Medium"));
        confetti = text(settings, "goalStyle", Effects.SHOWER);
        style = "Text".equals(text(settings, "style", "Crests")) ? "Text" : "Crests";
        onTop = !Boolean.FALSE.equals(settings.get("overScreensaver"));
        String nextPreview = text(settings, "preview", "Off");
        if (!nextPreview.equals(preview)) previewSince = clock.getAsLong();
        preview = nextPreview;
        List<String> teams = teams(settings);
        boolean legacy = false;
        for (String key : LEGACY_KEYS) legacy |= !text(settings, key, "").isEmpty();
        if (legacy) moveToList(settings, teams);
        String[] next = new String[SLOTS];
        for (int i = 0; i < SLOTS; i++) next[i] = i < teams.size() ? teams.get(i) : "";
        // Unsubscribe every changed slot before subscribing, so a sensor moved to another slot is subscribed afresh.
        for (int i = 0; i < SLOTS; i++) {
            if (next[i].equals(entities[i])) continue;
            if (!entities[i].isEmpty()) host.unsubscribe("ha.entity." + entities[i]);
            matches[i] = null;
            entityStatuses[i] = "connecting";
        }
        for (int i = 0; i < SLOTS; i++) {
            if (next[i].equals(entities[i])) continue;
            entities[i] = next[i];
            // The subscription delivers the current state first, so there is no separate read.
            if (!next[i].isEmpty()) host.subscribe("ha.entity." + next[i]);
        }
        refresh();
        armDemo();
    }

    @Override public void execute(String command, Map<String, Object> arguments) {}

    @Override public synchronized void onEvent(String event, Map<String, Object> payload) {
        if (host == null) return;
        int slot = slotOf(event);
        if (slot < 0) return;
        Object status = payload.get("status");
        String entityStatus = status instanceof String ? (String) status : "unknown";
        entityStatuses[slot] = entityStatus;
        // A dropped HA connection clears attributes but says nothing about the match. Keep the last
        // known one so a reconnect mid-match does not blink the bar off; the time bounds still apply.
        if (!"connecting".equals(entityStatus) && !"disconnected".equals(entityStatus)) matches[slot] = Match.from(payload);
        refresh();
    }

    private int slotOf(String event) {
        for (int i = 0; i < SLOTS; i++) {
            if (!entities[i].isEmpty() && event.equals("ks.ha.entity." + entities[i])) return i;
        }
        return -1;
    }

    /** KS revokes the host and removes overlays before stop, so only our own thread is cleaned up. */
    @Override public synchronized void stop() {
        if (tick != null) tick.shutdownNow();
        if (brands != null) brands.stop();
        brands = null;
        tick = null;
        demoArmed = false;
        host = null;
        overlay = null;
        shownSpec = null;
        for (int i = 0; i < SLOTS; i++) {
            matches[i] = null;
            entities[i] = "";
            entityStatuses[i] = "connecting";
        }
        Arrays.fill(candidates, Display.HIDDEN);
        shownSlot = -1;
        last = Display.HIDDEN;
        lastStatus = null;
    }

    /** The goal demo needs a refresh every few seconds, not every 30. The chain stops when the preview does. */
    private boolean isDemo() { return "Goal demo".equals(preview) || "Sport tour".equals(preview); }

    private void armDemo() {
        if (tick == null || demoArmed || !isDemo()) return;
        demoArmed = true;
        tick.schedule(this::onDemoTick, 5, TimeUnit.SECONDS);
    }

    private synchronized void onDemoTick() {
        demoArmed = false;
        onTick();
        armDemo();
    }

    synchronized void onTick() {
        try {
            refresh();
        } catch (RuntimeException e) {
            // An exception would silently cancel the schedule. Log it and keep ticking; the next event or tick retries.
            if (host != null) host.log("Gameday refresh failed: " + e);
        }
    }

    /** Runs on the brand worker thread once a league's colors arrive. */
    private synchronized void onBrandsLoaded() {
        try {
            refresh();
        } catch (RuntimeException e) {
            if (host != null) host.log("Gameday refresh after colors failed: " + e);
        }
    }

    private void refresh() {
        if (host == null) return;
        long now = clock.getAsLong();
        boolean live = "Off".equals(preview);
        Display d;
        if (live) {
            for (int i = 0; i < SLOTS; i++) candidates[i] = BugState.decide(matches[i], now, preGameHours, finalMinutes);
            shownSlot = Pick.choose(candidates, shownSlot);
            d = shownSlot < 0 ? Display.HIDDEN : candidates[shownSlot];
        } else {
            shownSlot = -1;
            d = sample(preview, now);
        }
        if (d.visible()) {
            // The preview's sample teams are Rowdies v Detroit City, so they use the USL list. A visible live display has a match.
            Match shown = live ? matches[shownSlot] : null;
            String sport = live ? shown.sport : previewSport(now), league = live ? shown.league : "usa.usl.1";
            d = brands == null ? d.decorated(null, null, sport) : d.decorated(brands.get(sport, league, d.left), brands.get(sport, league, d.right), sport);
        }
        String spec = anchor + "|" + textSp + "|" + onTop + "|" + style;
        if (d.visible() && !unsupported) {
            if (!spec.equals(shownSpec)) {
                show(d, spec);
            } else if (!d.equals(last)) {
                overlay.update(d);
                int side = scorer(last, d);
                if (celebrates(side, d.teamIsLeft)) overlay.celebrate(side > 0, goalWord(d.sport), d.sport, confetti);
            }
        } else if (shownSpec != null) {
            hide();
        }
        last = d;
        status(d);
    }

    /** +1 if the left score went up, -1 if the right score did, 0 if neither (or it is not the same live match). */
    static int scorer(Display before, Display after) {
        if (before.kind != Display.Kind.LIVE || after.kind != Display.Kind.LIVE || !before.left.equals(after.left) || !before.right.equals(after.right)) return 0;
        try {
            if (Integer.parseInt(after.leftScore) > Integer.parseInt(before.leftScore)) return 1;
            if (Integer.parseInt(after.rightScore) > Integer.parseInt(before.rightScore)) return -1;
        } catch (NumberFormatException notNumbers) {
            // A score that is not a number cannot be compared; no flash is the safe answer.
        }
        return 0;
    }

    /** Only the user's own team gets the animation. side is +1 for the left score rising, -1 for the right, 0 for neither. */
    static boolean celebrates(int side, boolean teamIsLeft) {
        return side != 0 && (side > 0) == teamIsLeft;
    }

    static String goalWord(String sport) {
        if ("soccer".equals(sport) || "hockey".equals(sport)) return "GOAL";
        if ("baseball".equals(sport)) return "RUN";
        return "SCORE";
    }

    private void show(Display d, String spec) {
        BugOverlay next = new BugOverlay(d, textSp, "Crests".equals(style));
        try {
            host.showOverlay(KEY, OverlaySpec.at(anchor).inset(12).closeOnBack(false).onTop(onTop).touchable(false), next);
            overlay = next;
            shownSpec = spec;
            generation++;
        } catch (UnsupportedOperationException olderHost) {
            unsupported = true;
        } catch (IllegalStateException rateLimited) {
            // Left hidden; the next tick or event tries again.
            host.log("Gameday overlay not shown yet: " + rateLimited.getMessage());
        }
    }

    private void hide() {
        int gen = ++generation;
        BugOverlay fading = overlay;
        overlay = null;
        shownSpec = null;
        fading.fadeOut(() -> hideIfCurrent(gen));
    }

    /** Runs on the main thread after the fade. The session may have ended meanwhile, and a throw here would end the app. */
    private synchronized void hideIfCurrent(int gen) {
        if (gen != generation || host == null) return;
        try {
            host.hideOverlay(KEY);
        } catch (RuntimeException sessionEnded) {
            // Nothing to hide once the session is gone: KS already removed its overlays.
        }
    }

    private void status(Display d) {
        String message;
        boolean error = false;
        if (unsupported) {
            message = "Native overlays need Kiosk Satellite 2026.10.13 or newer.";
            error = true;
        } else if (!"Off".equals(preview)) {
            message = "Preview. " + d.summary();
        } else if (configured() == 0) {
            message = "Add your team's TeamTracker sensor under Teams.";
            error = true;
        } else {
            int failing = failing();
            if (failing >= 0) {
                message = entities[failing] + ": " + entityStatuses[failing];
                error = true;
            } else {
                message = overview(d);
            }
        }
        if (message.equals(lastStatus)) return;
        lastStatus = message;
        host.status(message, error);
    }

    private int configured() {
        int n = 0;
        for (String entity : entities) if (!entity.isEmpty()) n++;
        return n;
    }

    /** The first team whose sensor reports a problem (not just a connection still opening) and has no match, or -1. */
    private int failing() {
        for (int i = 0; i < SLOTS; i++) {
            if (!entities[i].isEmpty() && matches[i] == null && !"connecting".equals(entityStatuses[i])) return i;
        }
        return -1;
    }

    /**
     * The bar's game, as in a single-team setup. With nothing on the bar, the first team in rank order that has a match
     * says what is coming; a team still connecting has nothing to say yet, so it is skipped.
     */
    private String overview(Display d) {
        if (d.visible()) return d.summary();
        for (int i = 0; i < SLOTS; i++) {
            Match m = matches[i];
            if (entities[i].isEmpty() || m == null) continue;
            if (Match.PRE.equals(m.state) && m.eventName != null) return "Upcoming game " + m.eventName + (m.kickoffIn == null ? "" : ", " + m.kickoffIn);
            return "No match showing (" + m.state + ")";
        }
        return "Waiting for " + (configured() == 1 ? "the team sensor" : "the team sensors") + ".";
    }

    private static final String[] TOUR = {"soccer", "baseball", "hockey", "football"};
    private static final String[] TOUR_CLOCK = {"67'", "Top 7th", "2nd 12:30", "Q3 5:12"};

    private String previewSport(long now) {
        return "Sport tour".equals(preview) ? TOUR[(int) ((now - previewSince) / 12_000) % TOUR.length] : "soccer";
    }

    /** Fixed sample teams so position and size can be tried on a quiet day. */
    private Display sample(String which, long now) {
        switch (which) {
            case "Upcoming game": {
                long kickoff = previewSince + 135 * 60_000L;
                return new Display(Display.Kind.NEXT, "TBR", "DET", "", "", true, kickoff, (int) Math.max(0, (kickoff - now + 59_999) / 60_000), "");
            }
            case "Sport tour": {
                // Every 6 s: a sport's first step shows 1-0, the next scores (2-0), then the next sport starts.
                int step = (int) ((now - previewSince) / 6_000);
                String score = step % 2 == 0 ? "1" : "2", clock = TOUR_CLOCK[(step / 2) % TOUR.length];
                return BugState.awayFirst(TOUR[(step / 2) % TOUR.length])
                    ? new Display(Display.Kind.LIVE, "DET", "TBR", "0", score, false, -1, 0, clock)
                    : new Display(Display.Kind.LIVE, "TBR", "DET", score, "0", true, -1, 0, clock);
            }
            case "Goal demo": {
                // The user's team scores every 10 s; the count wraps after five.
                int goals = (int) ((now - previewSince) / 10_000) % 5;
                return new Display(Display.Kind.LIVE, "TBR", "DET", String.valueOf(1 + goals), "0", true, -1, 0, "67'");
            }
            case "Live": return new Display(Display.Kind.LIVE, "TBR", "DET", "1", "0", true, -1, 0, "67'");
            case "Final": return new Display(Display.Kind.FINAL, "TBR", "DET", "2", "1", true, -1, 0, "FT");
            case "Final (loss)": return new Display(Display.Kind.FINAL, "TBR", "DET", "0", "2", true, -1, 0, "FT");
            default: return Display.HIDDEN;
        }
    }

    static String anchorFor(String position) {
        switch (position) {
            case "Bottom": return OverlaySpec.BOTTOM;
            case "Top left": return OverlaySpec.TOP_LEFT;
            case "Top right": return OverlaySpec.TOP_RIGHT;
            case "Bottom left": return OverlaySpec.BOTTOM_LEFT;
            case "Bottom right": return OverlaySpec.BOTTOM_RIGHT;
            default: return OverlaySpec.TOP;
        }
    }

    static int spFor(String size) {
        switch (size) {
            case "Small": return 13;
            case "Large": return 21;
            default: return 16;
        }
    }

    /**
     * The sensors to follow, in rank order: any legacy pickers, then the list's rows. Empty and repeated sensors are
     * dropped (a repeat would only compete with itself for the bar), and anything past SLOTS.
     */
    static List<String> teams(Map<String, Object> settings) {
        List<String> sensors = new ArrayList<>();
        for (String key : LEGACY_KEYS) sensors.add(text(settings, key, ""));
        Object rows = settings.get("teams");
        if (rows instanceof List) {
            for (Object row : (List<?>) rows) {
                Object sensor = row instanceof Map ? ((Map<?, ?>) row).get("sensor") : null;
                if (sensor instanceof String) sensors.add((String) sensor);
            }
        }
        List<String> teams = new ArrayList<>();
        for (String sensor : sensors) if (!sensor.isEmpty() && !teams.contains(sensor) && teams.size() < SLOTS) teams.add(sensor);
        return teams;
    }

    /**
     * Saves the merged teams as the list and clears the pickers, so the list is the one place to edit them. KS may
     * call configure again with the saved values; that pass finds no pickers and the same teams in the same slots.
     * saveSettings takes the whole set (a key left out goes back to its default), so every other value is passed through.
     */
    private void moveToList(Map<String, Object> settings, List<String> teams) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String sensor : teams) {
            Map<String, Object> row = new HashMap<>();
            row.put("sensor", sensor);
            rows.add(row);
        }
        Map<String, Object> values = new HashMap<>(settings);
        values.put("teams", rows);
        for (String key : LEGACY_KEYS) values.put(key, "");
        try {
            host.saveSettings(values);
            host.log("Gameday moved its team pickers into the Teams list.");
        } catch (RuntimeException e) {
            // Safe to carry on: the pickers are still read and merged in, and the next configure tries the move again.
            host.log("Gameday could not move its team pickers into the Teams list: " + e);
        }
    }

    private static String text(Map<String, Object> settings, String key, String fallback) {
        Object v = settings.get(key);
        return v instanceof String ? (String) v : fallback;
    }

    private static int number(Map<String, Object> settings, String key, int fallback) {
        Object v = settings.get(key);
        return v instanceof Number ? (int) Math.round(((Number) v).doubleValue()) : fallback;
    }
}
