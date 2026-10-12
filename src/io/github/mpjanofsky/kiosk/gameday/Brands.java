// SPDX-License-Identifier: Apache-2.0
package io.github.mpjanofsky.kiosk.gameday;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Reads ESPN's league team list (sports[0].leagues[0].teams[].team) into brands keyed by upper-case abbreviation. */
final class Brands {
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{6}");

    private Brands() {}

    /** Empty when the document is not the expected shape; never throws on odd content, only on unparseable JSON. */
    static Map<String, Brand> parse(String json) {
        Map<String, Brand> out = new HashMap<>();
        Object teams = path(Json.parse(json), "sports", "leagues", "teams");
        if (!(teams instanceof List)) return out;
        for (Object entry : (List<?>) teams) {
            if (!(entry instanceof Map) || !(((Map<?, ?>) entry).get("team") instanceof Map)) continue;
            Map<?, ?> t = (Map<?, ?>) ((Map<?, ?>) entry).get("team");
            if (!(t.get("abbreviation") instanceof String)) continue;
            out.put(((String) t.get("abbreviation")).toUpperCase(java.util.Locale.ROOT),
                new Brand(color(t.get("color")), color(t.get("alternateColor")), logo(t.get("logos"), false), logo(t.get("logos"), true)));
        }
        return out;
    }

    /** Follows the keys, stepping into the first element of each list on the way, and returns the last key's value. */
    private static Object path(Object root, String... keys) {
        Object cur = root;
        for (int i = 0; i < keys.length; i++) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(keys[i]);
            if (i < keys.length - 1) {
                if (!(cur instanceof List) || ((List<?>) cur).isEmpty()) return null;
                cur = ((List<?>) cur).get(0);
            }
        }
        return cur;
    }

    /** ESPN sends "09bc0c" without a hash, sometimes empty. */
    private static int color(Object v) {
        if (!(v instanceof String) || !HEX.matcher((String) v).matches()) return 0;
        return 0xFF000000 | Integer.parseInt((String) v, 16);
    }

    /** Only https images from ESPN's own CDN: the list is fetched data, and the bar must not be pointed elsewhere. */
    private static String logo(Object logos, boolean dark) {
        if (!(logos instanceof List)) return null;
        for (Object o : (List<?>) logos) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> l = (Map<?, ?>) o;
            Object rel = l.get("rel");
            boolean isDark = rel instanceof List && ((List<?>) rel).contains("dark");
            boolean isDefault = rel instanceof List && ((List<?>) rel).contains("default");
            if (!(l.get("href") instanceof String) || (dark ? !isDark : (!isDefault && isDark))) continue;
            String url = (String) l.get("href");
            if (url.startsWith("https://a.espncdn.com/") && url.length() < 200) return url;
        }
        return null;
    }
}
