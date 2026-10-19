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
        final List<Map<String, Object>> saved = new ArrayList<>();
        final List<String> logged = new ArrayList<>();
        boolean older, refusesSave;
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
        public void log(String message) { logged.add(message); }
        public void saveSettings(Map<String, Object> values) {
            if (refusesSave) throw new IllegalArgumentException("rejected");
            saved.add(values);
        }
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

    /** A copy of the soccer fixture as a different game: state, teams and start time. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> game(String state, String team, String opponent, long start) throws Exception {
        Map<String, Object> payload = new HashMap<>(Json.file("fixtures/teamtracker/" + ("PRE".equals(state) ? "pre" : "IN".equals(state) ? "in" : "post") + ".json"));
        Map<String, Object> attributes = new HashMap<>((Map<String, Object>) payload.get("attributes"));
        attributes.put("team_abbr", team);
        attributes.put("opponent_abbr", opponent);
        attributes.put("event_name", opponent + " @ " + team);
        attributes.put("friendly_name", team + " FC");
        attributes.put("date", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'") {{ setTimeZone(java.util.TimeZone.getTimeZone("UTC")); }}.format(new Date(start)));
        payload.put("attributes", attributes);
        return payload;
    }

    /** A row of the "teams" list as KS hands it over: a map with every field. */
    static Map<String, Object> row(String sensor) {
        Map<String, Object> r = new HashMap<>();
        r.put("sensor", sensor);
        return r;
    }

    static boolean host2Shows(Host h, String prefix) { return h.status.startsWith(prefix); }

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

        // Six days out: hidden, and the status names its next match.
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

        // Several teams share one bar. Ranking and holding are Pick's rules; this checks they are wired to the sensors.
        {
            Host h2 = new Host();
            long k = kickoff;
            long[] n2 = {k - 2 * 60 * MIN};
            GamedayPlugin multi = new GamedayPlugin(() -> n2[0], false);
            Map<String, Object> two = settings("sensor.a");
            two.put("team2Entity", "sensor.b");
            two.put("team3Entity", "sensor.a");
            multi.start(h2, two);
            assert h2.subscribed.equals(new LinkedHashSet<>(Arrays.asList("ha.entity.sensor.a", "ha.entity.sensor.b"))) : "a repeated sensor is ignored: " + h2.subscribed;
            String a = "ks.ha.entity.sensor.a", b = "ks.ha.entity.sensor.b";

            // Nothing in the window yet: sensors still connecting have nothing to say.
            n2[0] = k - 6 * 24 * 60 * MIN;
            assert h2.status.equals("Waiting for the team sensors.") && !h2.statusError : h2.status;
            multi.onEvent(a, game("PRE", "AAA", "DDD", k));
            multi.onEvent(b, game("PRE", "BBB", "CCC", k - 2 * 24 * 60 * MIN));
            assert h2.status.startsWith("Upcoming game DDD @ AAA") : "the first team in rank order speaks: " + h2.status;
            n2[0] = k - 2 * 60 * MIN;

            // Both pre-game: the first team wins the tie, and keeps the bar once B is in the window too.
            multi.onEvent(b, game("PRE", "BBB", "CCC", k + 30 * MIN));
            assert host2Shows(h2, "Next: AAA v DDD") : h2.status;
            multi.onEvent(a, game("PRE", "AAA", "DDD", k));
            assert host2Shows(h2, "Next: AAA v DDD") : h2.status;

            // A goes live and outranks a held pre-game.
            n2[0] = k + 10 * MIN;
            multi.onEvent(a, game("IN", "AAA", "DDD", k));
            assert host2Shows(h2, "Live: AAA 1–0 DDD") : h2.status;
            assert !h2.status.contains("\n") : "one line, the bar's game: " + h2.status;

            // B going live later does not take the bar from A; A's final then yields to B's live game.
            multi.onEvent(b, game("IN", "BBB", "CCC", k + 30 * MIN));
            assert host2Shows(h2, "Live: AAA") : "A holds the bar against another live game: " + h2.status;
            multi.onEvent(a, game("POST", "AAA", "DDD", k));
            assert host2Shows(h2, "Live: BBB") : "a live game outranks a final: " + h2.status;

            // Only one overlay is ever shown, and the team swap did not replace it.
            assert h2.shown.size() == 1 && h2.calls.equals(Collections.singletonList("show")) : h2.calls;

            // Everything over: the bar hides. A live game never times out, so B must finish first.
            multi.onEvent(b, game("POST", "BBB", "CCC", k + 30 * MIN));
            n2[0] = k + 12 * 60 * MIN;
            multi.onTick();
            assert h2.shown.isEmpty();

            // Dropping a picker unsubscribes only that sensor.
            multi.configure(settings("sensor.a"));
            assert h2.subscribed.equals(Collections.singleton("ha.entity.sensor.a")) : h2.subscribed;
            multi.stop();
        }

        // The "teams" list adds sensors after the pickers: a row repeating a picker's sensor is ignored, and
        // a list on its own is enough. Rows with no sensor (the editor allows none, but be safe) are skipped.
        {
            Host h3 = new Host();
            long[] n3 = {kickoff + 10 * MIN};
            GamedayPlugin listed = new GamedayPlugin(() -> n3[0], false);
            Map<String, Object> s3 = settings("");
            s3.put("teams", Arrays.asList(row("sensor.x"), row(""), row("sensor.y")));
            listed.start(h3, s3);
            assert h3.subscribed.equals(new LinkedHashSet<>(Arrays.asList("ha.entity.sensor.x", "ha.entity.sensor.y"))) : h3.subscribed;
            assert !h3.statusError || !h3.status.startsWith("Choose") : "a list-only setup is configured: " + h3.status;
            listed.onEvent("ks.ha.entity.sensor.y", game("IN", "YYY", "ZZZ", kickoff));
            assert host2Shows(h3, "Live: YYY") : h3.status;

            s3.put("teamEntity", "sensor.y");
            listed.configure(s3);
            assert h3.subscribed.equals(new LinkedHashSet<>(Arrays.asList("ha.entity.sensor.y", "ha.entity.sensor.x"))) : "the picker owns a repeated sensor: " + h3.subscribed;
            listed.stop();
        }

        // 0.5's pickers move into the list once: pickers first, then the rows, repeats dropped, pickers cleared.
        // Configuring again with what was saved changes nothing.
        {
            Host h4 = new Host();
            GamedayPlugin upgraded = new GamedayPlugin(() -> kickoff, false);
            Map<String, Object> old = settings("sensor.a");
            old.put("team2Entity", "sensor.b");
            old.put("team3Entity", "");
            old.put("teams", Arrays.asList(row("sensor.b"), row("sensor.c")));
            upgraded.start(h4, old);
            assert h4.saved.size() == 1 : h4.saved;
            Map<String, Object> saved = h4.saved.get(0);
            assert saved.get("teams").equals(Arrays.asList(row("sensor.a"), row("sensor.b"), row("sensor.c"))) : saved;
            assert "".equals(saved.get("teamEntity")) && "".equals(saved.get("team2Entity")) && "".equals(saved.get("team3Entity"));
            assert Double.valueOf(3.0).equals(saved.get("preGameHours")) : "KS resets a key left out of saveSettings: " + saved;
            Set<String> subscribed = new LinkedHashSet<>(h4.subscribed);
            assert subscribed.equals(new LinkedHashSet<>(Arrays.asList("ha.entity.sensor.a", "ha.entity.sensor.b", "ha.entity.sensor.c"))) : subscribed;

            Map<String, Object> after = settings("");
            after.putAll(saved);
            upgraded.configure(after);
            assert h4.saved.size() == 1 && h4.subscribed.equals(subscribed) : "no second move: " + h4.saved;
            upgraded.stop();

            // A host that will not save: the pickers keep working, and the log says why nothing moved.
            Host h5 = new Host();
            h5.refusesSave = true;
            GamedayPlugin stuck = new GamedayPlugin(() -> kickoff, false);
            stuck.start(h5, settings("sensor.a"));
            assert h5.subscribed.equals(Collections.singleton("ha.entity.sensor.a")) : h5.subscribed;
            assert h5.logged.stream().anyMatch(m -> m.contains("could not move")) : h5.logged;
            stuck.stop();
        }

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
