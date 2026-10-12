// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Team logos, fetched once each and kept in memory (a bar shows two at a
 * time and a season has a few dozen, at about 128 px). Only URLs BrandStore
 * already vetted as ESPN-hosted https images are passed in. A failed fetch is
 * not retried until the plugin restarts: the crest falls back to its label.
 */
final class Logos {
    private static final int TARGET_PX = 128, MAX_BYTES = 512 * 1024;
    private static final Map<String, Bitmap> cache = new HashMap<>();
    private static final Set<String> pending = new HashSet<>();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gameday-logos");
        t.setDaemon(true);
        return t;
    });

    private Logos() {}

    /** The bitmap if cached; otherwise null, and onReady runs on the worker thread after it arrives. */
    static synchronized Bitmap get(String url, Runnable onReady) {
        if (url == null) return null;
        Bitmap b = cache.get(url);
        if (b != null || !pending.add(url)) return b;
        worker.execute(() -> {
            Bitmap loaded = null;
            try {
                loaded = download(url);
            } catch (IOException | RuntimeException e) {
                // The crest keeps its abbreviation. Staying in `pending` stops a retry loop on a bad URL.
            }
            if (loaded != null) {
                synchronized (Logos.class) { cache.put(url, loaded); }
                onReady.run();
            }
        });
        return null;
    }

    private static Bitmap download(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) throw new IOException("logo HTTP " + c.getResponseCode());
            byte[] bytes;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) throw new IOException("logo too large");
                }
                bytes = out.toByteArray();
            }
            // ESPN serves 500 px logos; decoding at a power-of-two fraction keeps memory small.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= TARGET_PX && opts.inSampleSize < 16) opts.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } finally {
            c.disconnect();
        }
    }
}
