// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** ESPN team-list parsing, the contrast guard, and the store's fetch rules, all without a network. */
public final class BrandTest {
    static final int DARK_BAR = 0xFF2B2D31, LIGHT_BAR = 0xFFE9EAEE;

    public static void main(String[] args) throws Exception {
        String json = new String(Files.readAllBytes(Paths.get("fixtures/espn/teams-usl-trimmed.json")), StandardCharsets.UTF_8);
        Map<String, Brand> usl = Brands.parse(json);
        Brand tbr = usl.get("TBR");
        assert usl.size() == 3 && tbr.color == 0xFF09BC0C && tbr.alternate == 0xFFF2EE07 : usl.keySet();
        assert tbr.logo.equals("https://a.espncdn.com/i/teamlogos/soccer/500/17361.png");
        assert tbr.logoDark.equals("https://a.espncdn.com/i/teamlogos/soccer/500-dark/17361.png") : tbr.logoDark;
        assert tbr.logoFor(true).equals(tbr.logoDark) && tbr.logoFor(false).equals(tbr.logo);
        assert Brands.parse("{\"sports\":[]}").isEmpty() && Brands.parse("{}").isEmpty();
        // Colors must be six hex digits and logos ESPN-hosted https; anything else is dropped, not trusted.
        Map<String, Brand> odd = Brands.parse("{\"sports\":[{\"leagues\":[{\"teams\":[{\"team\":{\"abbreviation\":\"zz\",\"color\":\"nothex\","
            + "\"logos\":[{\"href\":\"http://evil.example/x.png\"}]}}]}]}]}");
        assert odd.get("ZZ").color == 0 && odd.get("ZZ").logo == null;

        // Contrast: a color that already reads is kept; navy on a dark bar is lightened until it does.
        assert Contrast.pick(tbr, DARK_BAR) == tbr.color;
        Brand navy = new Brand(0xFF092C5C, 0, null);
        int lifted = Contrast.pick(navy, DARK_BAR);
        assert lifted != navy.color && Contrast.ratio(lifted, DARK_BAR) >= Contrast.MIN_RATIO : Integer.toHexString(lifted);
        // The alternate is preferred over lightening when it reads (Rays: navy fails on dark, light blue passes).
        Brand rays = new Brand(0xFF092C5C, 0xFF8FBCE6, null);
        assert Contrast.pick(rays, DARK_BAR) == 0xFF8FBCE6;
        // Yellow on a light bar is darkened, not kept.
        Brand yellow = new Brand(0xFFF2EE07, 0, null);
        assert Contrast.ratio(Contrast.pick(yellow, LIGHT_BAR), LIGHT_BAR) >= Contrast.MIN_RATIO;
        assert Contrast.pick(null, DARK_BAR) == 0 && Contrast.pick(new Brand(0, 0, null), DARK_BAR) == 0;
        assert Contrast.on(0xFFF2EE07) == 0xFF000000 && Contrast.on(0xFF092C5C) == 0xFFFFFFFF;

        // Goals: only a rise in the same live match flashes, and the side that rose is reported.
        Display one = new Display(Display.Kind.LIVE, "TBR", "DET", "1", "0", true, -1, 0, "10'");
        assert GamedayPlugin.scorer(one, new Display(Display.Kind.LIVE, "TBR", "DET", "2", "0", true, -1, 0, "11'")) == 1;
        assert GamedayPlugin.scorer(one, new Display(Display.Kind.LIVE, "TBR", "DET", "1", "1", true, -1, 0, "11'")) == -1;
        assert GamedayPlugin.scorer(one, new Display(Display.Kind.LIVE, "TBR", "DET", "1", "0", true, -1, 0, "11'")) == 0;
        assert GamedayPlugin.scorer(one, new Display(Display.Kind.LIVE, "TBR", "DET", "0", "0", true, -1, 0, "11'")) == 0; // a correction down
        assert GamedayPlugin.scorer(one, new Display(Display.Kind.LIVE, "TBR", "PIT", "2", "0", true, -1, 0, "11'")) == 0;
        assert GamedayPlugin.scorer(Display.HIDDEN, one) == 0;
        assert GamedayPlugin.goalWord("soccer").equals("GOAL") && GamedayPlugin.goalWord("baseball").equals("RUN") && GamedayPlugin.goalWord(null).equals("SCORE");

        // Only the user's own team celebrates: the left score rising counts when the team is on the left, and so on.
        assert GamedayPlugin.celebrates(1, true) && !GamedayPlugin.celebrates(-1, true);
        assert GamedayPlugin.celebrates(-1, false) && !GamedayPlugin.celebrates(1, false) && !GamedayPlugin.celebrates(0, true);

        // Day label: today is blank, then Tomorrow, a weekday within a week, then the date.
        java.util.TimeZone utc = java.util.TimeZone.getTimeZone("UTC");
        long noon = Match.parseTime("2026-10-12T12:00Z"), day = 24 * 3_600_000L;
        assert Display.dayPrefix(noon + 6 * 3_600_000L, noon, utc, java.util.Locale.US).isEmpty();
        assert Display.dayPrefix(noon + day, noon, utc, java.util.Locale.US).equals("Tomorrow");
        assert Display.dayPrefix(noon + 5 * day, noon, utc, java.util.Locale.US).equals("Sat");
        assert Display.dayPrefix(noon + 9 * day, noon, utc, java.util.Locale.US).equals("Oct 21");
        assert Display.dayPrefix(noon - day, noon, utc, java.util.Locale.US).isEmpty();

        // Store: one fetch per league serves both teams, unsafe league text never reaches a URL, a failure is retried only after 10 minutes.
        final long[] now = {1_000};
        final List<String> urls = new ArrayList<>();
        final boolean[] fail = {true};
        CountDownLatch loaded = new CountDownLatch(1);
        BrandStore store = new BrandStore(url -> {
            urls.add(url);
            if (fail[0]) throw new java.io.IOException("offline");
            return json;
        }, () -> now[0], loaded::countDown);
        assert store.get("soccer", "usa.usl.1", "TBR") == null;
        waitFor(() -> urls.size() == 1);
        assert store.get("soccer", "usa.usl.1", "TBR") == null && urls.size() == 1; // inside the retry window
        now[0] += 10 * 60_000L;
        fail[0] = false;
        store.get("soccer", "usa.usl.1", "TBR");
        assert loaded.await(5, TimeUnit.SECONDS);
        assert store.get("soccer", "usa.usl.1", "tbr").equals(tbr) && store.get("soccer", "usa.usl.1", "DET") != null;
        assert urls.size() == 2 && urls.get(1).equals("https://site.api.espn.com/apis/site/v2/sports/soccer/usa.usl.1/teams");
        assert store.get("soccer", "../etc", "TBR") == null && store.get(null, "mlb", "TB") == null && urls.size() == 2;
        store.stop();

        System.out.println("Brand tests passed.");
    }

    static void waitFor(java.util.function.BooleanSupplier c) throws InterruptedException {
        for (int i = 0; i < 100 && !c.getAsBoolean(); i++) Thread.sleep(50);
        assert c.getAsBoolean() : "timed out";
    }
}
