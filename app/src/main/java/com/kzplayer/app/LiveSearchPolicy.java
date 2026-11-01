package com.kzplayer.app;

/** Search-only routing and identity. Does not configure any media player. */
public final class LiveSearchPolicy {
    private LiveSearchPolicy() {}
    public static String action(String kind) {
        return "live".equals(kind) ? "get_live_streams" : "series".equals(kind) ? "get_series" : "get_vod_streams";
    }
    public static String portalType(String kind) {
        return "live".equals(kind) ? "itv" : "series".equals(kind) ? "series" : "vod";
    }
    public static String portalCategory(String kind) { return "live".equals(kind) ? "genre" : "category"; }
    public static String groupKey(String kind, String owner, String identity, String foldedName) {
        // A TF1 entry from each playlist remains independently selectable.
        return "live".equals(kind) ? "live:" + owner.length() + ":" + owner + identity : kind + ":" + foldedName;
    }
    public static boolean searchKind(String kind) { return "live".equals(kind) || "movie".equals(kind) || "series".equals(kind); }
}
