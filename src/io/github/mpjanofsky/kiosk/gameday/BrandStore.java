// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Team colors and logos from ESPN, the source TeamTracker itself reads. One
 * request per league lists every team, so both sides of any match come from
 * the same fetch. Held in memory: a plugin restart costs one small request.
 * Fetching runs on its own thread and never blocks a KS callback.
 */
final class BrandStore {
    interface Fetcher { String get(String url) throws IOException; }

    private static final Pattern SAFE = Pattern.compile("[a-z0-9.]{1,24}");
    private static final long RETRY_MS = 10 * 60_000L;
    private static final int MAX_BYTES = 1024 * 1024;

    private final Fetcher fetcher;
    private final java.util.function.LongSupplier clock;
    private final Runnable onLoaded;
    private final ExecutorService worker;
    private final Map<String, Map<String, Brand>> leagues = new HashMap<>();
    /** League key to when it was last tried, so a dead network is retried every 10 minutes, not every tick. */
    private final Map<String, Long> tried = new HashMap<>();

    /** A null fetcher is a store that never fetches (tests). onLoaded runs on the worker thread. */
    BrandStore(Fetcher fetcher, java.util.function.LongSupplier clock, Runnable onLoaded) {
        this.fetcher = fetcher;
        this.clock = clock;
        this.onLoaded = onLoaded;
        this.worker = fetcher == null ? null : Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "gameday-brands");
            t.setDaemon(true);
            return t;
        });
    }

    /** The team's brand, or null while unknown. Starts the league fetch the first time it is asked. */
    synchronized Brand get(String sport, String league, String abbr) {
        if (sport == null || league == null || abbr == null || !SAFE.matcher(sport).matches() || !SAFE.matcher(league).matches()) return null;
        String key = sport + "/" + league;
        Map<String, Brand> teams = leagues.get(key);
        if (teams != null) return teams.get(abbr.toUpperCase(java.util.Locale.ROOT));
        long now = clock.getAsLong();
        Long last = tried.get(key);
        if (worker != null && (last == null || now - last >= RETRY_MS)) {
            tried.put(key, now);
            worker.execute(() -> load(key));
        }
        return null;
    }

    void stop() {
        if (worker != null) worker.shutdownNow();
    }

    private void load(String key) {
        Map<String, Brand> teams;
        try {
            teams = Brands.parse(fetcher.get("https://site.api.espn.com/apis/site/v2/sports/" + key + "/teams"));
        } catch (IOException | RuntimeException e) {
            // Colors are decoration: without them the bar draws in theme colors. The retry timer tries again.
            return;
        }
        if (teams.isEmpty()) return;
        synchronized (this) { leagues.put(key, teams); }
        onLoaded.run();
    }

    static String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) throw new IOException("ESPN HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) throw new IOException("ESPN response too large");
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
