// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

import java.util.*;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/** What the plugin asks KS for across a match day. Drawing the view needs a device. */
public final class GamedayPluginTest {
    static final long MIN = 60_000L;

    static final class Host implements PluginHost {
        final Map<String, OverlaySpec> shown = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        final Set<String> subscribed = new LinkedHashSet<>();
        boolean older;
        String status;
        boolean statusError;
        public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) {
            if (older) throw new UnsupportedOperationException("Native overlays are unavailable");
            assert factory instanceof BugOverlay;
            shown.put(key, spec); calls.add("show");
        }
        public void hideOverlay(String key) { shown.remove(key); calls.add("hide"); }
        public void subscribe(String event) { subscribed.add(event); }
        public void unsubscribe(String event) { subscribed.remove(event); }
        public void status(String message, boolean error) { status = message; statusError = error; }
        public void showWindow(String title, String message, String button) {}
        public void hideWindow() {}
        public void log(String message) {}
    }

    static Map<String, Object> settings(String entity) {
        Map<String, Object> s = new HashMap<>();
        s.put("teamEntity", entity);
        s.put("preGameHours", 3.0);
        s.put("finalMinutes", 30.0);
        s.put("position", "Top");
        s.put("size", "Medium");
        s.put("overScreensaver", true);
        s.put("preview", "Off");
        return s;
    }

    public static void main(String[] args) throws Exception {
        long kickoff = Match.parseTime("2026-10-17T23:30Z");
        long[] now = {kickoff - 6 * 24 * 60 * MIN};
        Host host = new Host();
        GamedayPlugin plugin = new GamedayPlugin(() -> now[0], false);
        String event = "ks.ha.entity.sensor.tampa_bay_rowdies";

        // No entity yet: nothing shown, and the subpage says what to do.
        plugin.start(host, settings(""));
        assert host.calls.isEmpty() && host.statusError && host.status.contains("TeamTracker");

        plugin.configure(settings("sensor.tampa_bay_rowdies"));
        assert host.subscribed.contains("ha.entity.sensor.tampa_bay_rowdies");

        // Six days out: hidden, with the next match in the status.
        plugin.onEvent(event, Json.file("fixtures/teamtracker/pre.json"));
        assert host.calls.isEmpty() && host.status.equals("Upcoming game DET @ TBR, in 6 days") : host.status;

        // The window opens on a tick, not an event.
        now[0] = kickoff - 2 * 60 * MIN;
        plugin.onTick();
        OverlaySpec spec = host.shown.get(GamedayPlugin.KEY);
        assert spec != null && OverlaySpec.TOP.equals(spec.anchor()) && spec.inset() == 12;
        assert spec.onTop() && !spec.touchable() && !spec.closeOnBack();
        assert host.status.startsWith("Next: TBR v DET");

        // Countdown and score changes update the view in place rather than replacing the overlay.
        now[0] += MIN;
        plugin.onTick();
        now[0] = kickoff + 70 * MIN;
        plugin.onEvent(event, Json.file("fixtures/teamtracker/in.json"));
        assert host.calls.equals(Collections.singletonList("show")) : host.calls;
        assert host.status.equals("Live: TBR 1–0 DET 67'");

        // An HA reconnect mid-match keeps the bar up.
        Map<String, Object> dropped = new HashMap<>();
        dropped.put("status", "disconnected");
        plugin.onEvent(event, dropped);
        assert host.shown.containsKey(GamedayPlugin.KEY) && host.status.startsWith("Live:");

        // Moving the bar replaces the overlay with the new placement.
        Map<String, Object> bottom = settings("sensor.tampa_bay_rowdies");
        bottom.put("position", "Bottom right");
        bottom.put("overScreensaver", false);
        plugin.configure(bottom);
        assert OverlaySpec.BOTTOM_RIGHT.equals(host.shown.get(GamedayPlugin.KEY).anchor());
        assert !host.shown.get(GamedayPlugin.KEY).onTop() && host.calls.size() == 2;

        // Final for 30 minutes, then hidden. With no view off-device, the fade completes at once.
        long fullTime = Match.parseTime("2026-10-18T01:24:40+00:00");
        now[0] = fullTime + MIN;
        plugin.onEvent(event, Json.file("fixtures/teamtracker/post.json"));
        assert host.status.equals("Final: TBR 2–1 DET FT") && host.shown.containsKey(GamedayPlugin.KEY);
        now[0] = fullTime + 31 * MIN;
        plugin.onTick();
        assert !host.shown.containsKey(GamedayPlugin.KEY) && host.calls.get(host.calls.size() - 1).equals("hide");

        // Preview shows sample data whatever the match is doing, and turning it off goes back to the rules.
        Map<String, Object> preview = settings("sensor.tampa_bay_rowdies");
        preview.put("preview", "Live");
        plugin.configure(preview);
        assert host.shown.containsKey(GamedayPlugin.KEY) && host.status.startsWith("Preview. Live:");
        plugin.configure(settings("sensor.tampa_bay_rowdies"));
        assert !host.shown.containsKey(GamedayPlugin.KEY);

        // Switching teams drops the old subscription and forgets the old match.
        plugin.configure(settings("sensor.other_team"));
        assert host.subscribed.equals(Collections.singleton("ha.entity.sensor.other_team"));
        plugin.onEvent(event, Json.file("fixtures/teamtracker/in.json"));
        assert !host.shown.containsKey(GamedayPlugin.KEY) : "events for the old entity are ignored";

        // After stop, nothing reaches the host.
        int calls = host.calls.size();
        plugin.stop();
        plugin.onTick();
        plugin.onEvent(event, Json.file("fixtures/teamtracker/in.json"));
        assert host.calls.size() == calls;

        // Older KS without overlays: the plugin says so instead of failing.
        Host older = new Host();
        older.older = true;
        GamedayPlugin fallback = new GamedayPlugin(() -> kickoff + 70 * MIN, false);
        fallback.start(older, settings("sensor.tampa_bay_rowdies"));
        fallback.onEvent(event, Json.file("fixtures/teamtracker/in.json"));
        assert older.statusError && older.status.contains("2026.10.13");

        System.out.println("Plugin tests passed.");
    }
}
