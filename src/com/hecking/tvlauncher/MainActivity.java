package com.hecking.tvlauncher;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * Home screen.
 * - Full-screen backdrop that crossfades every 30 seconds. No zoom: a continuous
 *   scale on the full-screen image was the main frame-time cost on the Sony.
 * - Live clock, date, and weather (Open-Meteo, no key).
 * - Voice search button (Android speech input).
 * - Hidden apps are one list for the whole house (no profiles).
 * - Every Leanback-launchable app as a tile with its own banner; rebuilds on install/remove.
 *   Banners and icons are loaded once per package on a background thread and cached.
 * - Apps can be reordered (saved order) and put into up to 8 named groups, each a row above Apps.
 * - Customize: hide or show apps, reorder, groups, and "Choose home screen" for recovery.
 * - "Install app file" opens the installer for APKs the user picks.
 */
public class MainActivity extends Activity {

    private static final int TEXT = 0xFFF5F5F7;
    private static final int DIM = 0xFF8E8E93;
    private static final int TILE = 0xFF1C1C1E;
    private static final long HOLD_MS = 30000;
    private static final long FADE_MS = 900;
    private static final long WEATHER_MS = 20 * 60 * 1000;
    private static final int ROW_INSET = 0;
    // app tiles: about five visible across the 1920px screen
    // four tiles across the 1920px screen, with gaps: (960dp - 48dp margins - 4*16dp gaps) / 4
    private static final int APP_COLUMNS = 4;
    private static final int APP_W = 184;
    private static final int APP_H = 104;
    private static final int REQ_VOICE = 77;
    private static final String PREFS = "launcher";
    private static final String KEY_HIDDEN = "hidden";
    // Plex "Continue watching" row: off by default, set in Customize. Server comes from /sdcard/tvhome/plex.json.
    private static final String KEY_PLEX_ON = "show_plex_row";
    private static final String PLEX_PKG = "com.plexapp.android";
    private static final int PLEX_MAX_ITEMS = 8;
    private static final int PLEX_TIMEOUT_MS = 5000;
    private static final String KEY_ORDER = "order";
    private static final String KEY_GROUPS = "groups";
    private static final int MAX_GROUPS = 8;
    private static final String[] PRESETS = { "Games", "Streaming", "Kids", "Music", "Favourites" };
    // sub-screens of Customize (null = the Customize list itself)
    private static final String SUB_REMOVE = "remove";
    private static final String SUB_REORDER = "reorder";
    private static final String SUB_GROUPS = "groups";
    private static final String SUB_ADD_GROUP = "addgroup";
    // Ashburn, VA (confirmed by the owner). Weather is fetched from Open-Meteo, which needs no key.
    private static final double LAT = 39.04, LON = -77.49;
    private static final String PLACE = "Ashburn";

    private static class Group {
        final String name;
        final List<String> pkgs = new ArrayList<>();
        Group(String name) { this.name = name; }
    }

    /** An ImageView waiting for its banner/icon to arrive from the art thread. */
    private static class PendingArt {
        final String pkg;
        final ImageView view;
        PendingArt(String pkg, ImageView view) { this.pkg = pkg; this.view = view; }
    }

    private final List<ResolveInfo> apps = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private final List<String> backdrops = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** One Continue watching entry. art is filled by the Plex thread and read on the UI thread only. */
    private static class PlexItem {
        final String title, show, thumb;
        Bitmap art;
        PlexItem(String title, String show, String thumb) { this.title = title; this.show = show; this.thumb = thumb; }
    }
    private final List<PlexItem> plexItems = new ArrayList<>();
    // thumb path -> decoded bitmap; touched only by the Plex thread
    private final Map<String, Bitmap> plexArtCache = Collections.synchronizedMap(new HashMap<String, Bitmap>());
    private int plexGen = 0;

    // artwork and labels are computed once per package, not on every rebuild
    private final Map<String, Drawable> artCache = new HashMap<>();
    private final Set<String> bannerPkgs = new HashSet<>();
    private final Set<String> artInFlight = new HashSet<>();
    private final List<PendingArt> artWaiting = new ArrayList<>();
    private final Map<ResolveInfo, String> labelCache = new HashMap<>();
    private int artGen = 0;

    // row to focus after the next rebuild (so a tap keeps the cursor on the same row)
    private String focusKey = null;
    // the app being moved (hold a tile to start); null when not moving
    private String movingPkg = null;
    // starting order on a fresh install: the top row
    private static final String[] DEFAULT_ORDER = {
            "com.google.android.youtube.tv", "com.netflix.ninja",
            "com.plexapp.android", "com.amazon.amazonvideo.livingroom",
    };

    private FrameLayout root;
    private ImageView backA, backB;
    private ImageView visible;
    private int backdropIndex = -1;
    private TextView clockView, dateView, weatherView;
    private FrameLayout contentHolder;
    private boolean customizing = false;
    private String subScreen = null;


    private final Runnable nextBackdrop = new Runnable() {
        @Override
        public void run() {
            showNextBackdrop();
            handler.postDelayed(this, HOLD_MS);
        }
    };


    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 15000);
        }
    };

    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            loadWeather();
            handler.postDelayed(this, WEATHER_MS);
        }
    };

    // Fires whenever an app is installed, removed, enabled, disabled, or updated.
    private final BroadcastReceiver packageChanges = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            clearArtCache();
            loadApps();
            showCurrentView();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreSettings();
        loadBackdropList();
        loadApps();
        buildShell();
        showCurrentView();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageChanges, f);
        loadApps();
        showCurrentView();
        updateClock();
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
        handler.removeCallbacks(weatherTick);
        handler.post(weatherTick);
        loadPlex();
        handler.removeCallbacks(nextBackdrop);
        if (!backdrops.isEmpty()) {
            showNextBackdrop();
        }
        // static background and one featured title: no rotation timers
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveSettings();
        try {
            unregisterReceiver(packageChanges);
        } catch (IllegalArgumentException ignored) {
            // already unregistered
        }
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(weatherTick);
        handler.removeCallbacks(nextBackdrop);
    }

    /**
     * In move mode the remote's arrows move the app, before focus navigation gets them.
     * Left/right: one place. Up/down: one row (four places). Select or Back: drop it.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (movingPkg != null && !customizing && e.getAction() == KeyEvent.ACTION_UP) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:  moveAndKeep(-1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: moveAndKeep(1); return true;
                case KeyEvent.KEYCODE_DPAD_UP:    for (int i = 0; i < APP_COLUMNS; i++) moveApp(movingPkg, -1); moveAndKeep(0); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:  for (int i = 0; i < APP_COLUMNS; i++) moveApp(movingPkg, 1); moveAndKeep(0); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:       stopMove(); return true;
                default: break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void moveAndKeep(int delta) {
        if (delta != 0) moveApp(movingPkg, delta);
        focusKey = movingPkg;
        showCurrentView();
    }

    private void startMove(String pkg) {
        movingPkg = pkg;
        focusKey = pkg;
        showCurrentView();
    }

    private void stopMove() {
        focusKey = movingPkg;
        movingPkg = null;
        showCurrentView();
    }

    /** The wobble on the tile being moved: a small rotation that swings back and forth. */
    private void startJiggle(View tile) {
        stopJiggle(); // every move step rebuilds the grid: never leave an old infinite animator running
        ObjectAnimator a = ObjectAnimator.ofFloat(tile, View.ROTATION, -2.5f, 2.5f);
        a.setDuration(140);
        a.setRepeatCount(ObjectAnimator.INFINITE);
        a.setRepeatMode(ObjectAnimator.REVERSE);
        jiggle = a;
        a.start();
    }

    private void stopJiggle() {
        if (jiggle != null) { jiggle.cancel(); jiggle = null; }
    }

    private ObjectAnimator jiggle;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Back from Customize returns to the home screen, instead of leaving it.
        if (keyCode == KeyEvent.KEYCODE_BACK && customizing) {
            // from a sub-screen, Back goes to Customize; from Customize, Back goes home
            if (subScreen != null) subScreen = null; else customizing = false;
            showCurrentView();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (heard != null && !heard.isEmpty()) {
                Toast.makeText(this, "Heard: " + heard.get(0), Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------- data ----------

    private static String pkgOf(ResolveInfo ri) {
        return ri.activityInfo.packageName;
    }

    /** App label, read from PackageManager once per ResolveInfo (reset on every app reload). */
    private String labelOf(ResolveInfo ri) {
        String s = labelCache.get(ri);
        if (s == null) {
            s = ri.loadLabel(getPackageManager()).toString();
            labelCache.put(ri, s);
        }
        return s;
    }

    private void loadApps() {
        apps.clear();
        labelCache.clear();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        PackageManager pm = getPackageManager();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (getPackageName().equals(pkgOf(ri))) continue;
            apps.add(ri);
        }
        // saved order first (position in the "order" list); anything not saved goes to the end, A-Z
        Map<String, Integer> rank = new HashMap<>();
        // one-time: put the top row first in the saved order (later moves are kept as you make them)
        if (!prefs().getBoolean("order_v2", false)) {
            List<String> merged = new ArrayList<>(Arrays.asList(DEFAULT_ORDER));
            for (String p : prefs().getString(KEY_ORDER, "").split(",")) {
                if (!p.trim().isEmpty() && !merged.contains(p.trim())) merged.add(p.trim());
            }
            prefs().edit().putString(KEY_ORDER, TextUtils.join(",", merged))
                    .putBoolean("order_v2", true).apply();
        }
        String savedRaw = prefs().getString(KEY_ORDER, "");
        if (savedRaw.isEmpty()) savedRaw = TextUtils.join(",", DEFAULT_ORDER);
        String[] saved = savedRaw.split(",");
        for (int i = 0; i < saved.length; i++) {
            String p = saved[i].trim();
            if (!p.isEmpty() && !rank.containsKey(p)) rank.put(p, i);
        }
        Collections.sort(apps, (a, b) -> {
            Integer ra = rank.get(pkgOf(a));
            Integer rb = rank.get(pkgOf(b));
            if (ra != null && rb != null) return Integer.compare(ra, rb);
            if (ra != null) return -1;
            if (rb != null) return 1;
            return labelOf(a).compareToIgnoreCase(labelOf(b));
        });
        loadGroups();
        warmArt();
    }

    /** Writes the current order of every app (apps list order) so it survives restarts. */
    private void saveOrder() {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : apps) {
            String p = pkgOf(ri);
            if (!seen.add(p)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        prefs().edit().putString(KEY_ORDER, sb.toString()).apply(); // async disk write: no fsync on the UI thread per move step
    }

    /** Moves one app one place earlier (delta -1) or later (+1) in the global order. No-op at the ends. */
    private void moveApp(String pkg, int delta) {
        int from = -1;
        for (int i = 0; i < apps.size(); i++) {
            if (pkgOf(apps.get(i)).equals(pkg)) { from = i; break; }
        }
        // step to the next app that is actually shown: hidden apps are skipped, so a move always
        // changes what you see
        Set<String> hidden = hiddenSet();
        int to = from + delta;
        while (to >= 0 && to < apps.size() && hidden.contains(pkgOf(apps.get(to)))) to += delta;
        if (from < 0 || to < 0 || to >= apps.size()) return;
        Collections.swap(apps, from, to);
        saveOrder();
    }

    private void loadGroups() {
        groups.clear();
        try {
            JSONArray arr = new JSONArray(prefs().getString(KEY_GROUPS, "[]"));
            for (int i = 0; i < arr.length() && groups.size() < MAX_GROUPS; i++) {
                JSONObject o = arr.getJSONObject(i);
                String name = o.optString("name", "");
                if (name.isEmpty()) continue;
                Group g = new Group(name);
                groups.add(g);
                JSONArray p = o.optJSONArray("pkgs");
                if (p == null) continue;
                for (int j = 0; j < p.length(); j++) {
                    String s = p.optString(j, "");
                    // an app belongs to at most one group
                    if (!s.isEmpty() && groupIndexOfPkg(s) < 0) g.pkgs.add(s);
                }
            }
        } catch (Exception e) {
            groups.clear(); // unreadable saved data: start with no groups
        }
    }

    private void saveGroups() {
        JSONArray arr = new JSONArray();
        try {
            for (Group g : groups) {
                JSONObject o = new JSONObject();
                o.put("name", g.name);
                JSONArray p = new JSONArray();
                for (String s : g.pkgs) p.put(s);
                o.put("pkgs", p);
                arr.put(o);
            }
        } catch (Exception ignored) {
            // names and package names are plain strings: this does not happen
        }
        prefs().edit().putString(KEY_GROUPS, arr.toString()).apply();
    }

    private int groupIndexOfPkg(String pkg) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).pkgs.contains(pkg)) return i;
        }
        return -1;
    }

    private int groupIndexOfName(String name) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).name.equals(name)) return i;
        }
        return -1;
    }

    /** none -> group 1 -> group 2 ... -> none. Keeps the app in at most one group. */
    private void cycleGroup(String pkg) {
        int cur = groupIndexOfPkg(pkg);
        int next = cur + 1 < groups.size() ? cur + 1 : -1;
        for (Group g : groups) g.pkgs.remove(pkg);
        if (next >= 0) groups.get(next).pkgs.add(pkg);
        saveGroups();
    }

    private void addGroup(String name) {
        if (groups.size() >= MAX_GROUPS) {
            Toast.makeText(this, "Up to " + MAX_GROUPS + " groups", Toast.LENGTH_SHORT).show();
            return;
        }
        if (groupIndexOfName(name) >= 0) return;
        groups.add(new Group(name));
        saveGroups();
    }

    private void deleteGroup(String name) {
        int i = groupIndexOfName(name);
        if (i < 0) return;
        groups.remove(i);
        saveGroups();
    }

    private boolean isUserApp(ResolveInfo ri) {
        return (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
    }

    private boolean hasUserApps() {
        for (ResolveInfo ri : apps) {
            if (isUserApp(ri)) return true;
        }
        return false;
    }

    /**
     * Settings survive uninstall: they are mirrored to /sdcard/tvhome/settings.json (not in the app's
     * private storage, which uninstall deletes). The Plex token is never mirrored; it stays in plex.json.
     */
    private static final String SETTINGS_FILE = "/sdcard/tvhome/settings.json";

    private void saveSettings() {
        try {
            JSONObject out = new JSONObject();
            for (Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
                if (e.getKey().startsWith("plex_")) continue; // secrets never leave the TV's config file
                Object v = e.getValue();
                if (v instanceof Set) {
                    JSONArray a = new JSONArray();
                    for (Object s : (Set<?>) v) a.put(String.valueOf(s));
                    out.put(e.getKey(), new JSONObject().put("set", a));
                } else {
                    out.put(e.getKey(), v);
                }
            }
            byte[] data = out.toString().getBytes("UTF-8");
            // the app's own folder always works; the shared copy only if All files access is on
            for (String path : settingsPaths()) {
                try {
                    java.io.File f = new java.io.File(path);
                    f.getParentFile().mkdirs();
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                        o.write(data);
                    }
                } catch (Exception ignored) {
                    // this location is not writable: try the next one
                }
            }
        } catch (Exception ignored) {
            // nothing to save
        }
    }

    /** Own folder first, then the shared folder. */
    private String[] settingsPaths() {
        java.io.File own = getExternalFilesDir(null);
        if (own == null) return new String[] { SETTINGS_FILE };
        return new String[] { new java.io.File(own, "settings.json").getPath(), SETTINGS_FILE };
    }

    /** On a fresh install, bring back the saved settings. Keys already set are left alone. */
    private void restoreSettings() {
        if (prefs().getAll().size() > 0) return;
        String source = SETTINGS_FILE;
        for (String path : settingsPaths()) {
            if (new java.io.File(path).exists()) { source = path; break; }
        }
        try (InputStream in = new java.io.FileInputStream(source)) {
            byte[] buf = new byte[65536];
            int n = in.read(buf);
            JSONObject saved = new JSONObject(new String(buf, 0, Math.max(0, n), "UTF-8"));
            SharedPreferences.Editor ed = prefs().edit();
            java.util.Iterator<String> keys = saved.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = saved.get(k);
                if (v instanceof JSONObject && ((JSONObject) v).has("set")) {
                    JSONArray a = ((JSONObject) v).getJSONArray("set");
                    Set<String> set = new HashSet<>();
                    for (int i = 0; i < a.length(); i++) set.add(a.getString(i));
                    ed.putStringSet(k, set);
                } else if (v instanceof Boolean) {
                    ed.putBoolean(k, (Boolean) v);
                } else if (v instanceof Integer) {
                    ed.putInt(k, (Integer) v);
                } else if (v instanceof Long) {
                    ed.putLong(k, (Long) v);
                } else if (v instanceof String) {
                    ed.putString(k, (String) v);
                }
            }
            ed.commit();
        } catch (Exception ignored) {
            // no saved settings yet
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }


    // Apps hidden from the home grid. One list for the whole house: profiles were not useful here.
    // Hidden until you change it in Customize: apps this house doesn't use. Reversible; nothing is uninstalled.
    private static final String[] DEFAULT_HIDDEN = {
            "com.android.tv",                        // Live TV (emulator)
            "com.sony.dtv.tvx",                      // Live TV (Sony BRAVIA)
            "com.sony.dtv.seeds.iot",                // Smart Speaker setup: kept installed for HomeKit, hidden here
            "com.google.android.play.games",         // Play Games
            "com.google.android.youtube.tvmusic",    // YouTube Music
    };

    private Set<String> hiddenSet() {
        Set<String> saved = prefs().getStringSet(KEY_HIDDEN, null);
        if (saved == null) return new HashSet<>(Arrays.asList(DEFAULT_HIDDEN));
        return new HashSet<>(saved);
    }

    private void setHidden(String pkg, boolean hide) {
        Set<String> set = hiddenSet();
        if (hide) set.add(pkg); else set.remove(pkg);
        prefs().edit().putStringSet(KEY_HIDDEN, set).apply();
    }

    // ---------- artwork cache ----------

    /**
     * Loads banner (or icon) for every app that is not cached yet, on a background thread.
     * Tiles that are already built wait in artWaiting and get their artwork when it arrives.
     */
    private void warmArt() {
        final List<ResolveInfo> missing = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            String p = pkgOf(ri);
            if (!artCache.containsKey(p) && artInFlight.add(p)) missing.add(ri);
        }
        if (missing.isEmpty()) return;
        final int gen = artGen;
        final PackageManager pm = getPackageManager();
        new Thread(() -> {
            final Map<String, Drawable> got = new HashMap<>();
            final Set<String> banners = new HashSet<>();
            for (ResolveInfo ri : missing) {
                String p = pkgOf(ri);
                Drawable d = null;
                try {
                    ApplicationInfo ai = ri.activityInfo.applicationInfo;
                    d = ai.loadBanner(pm);
                    if (d != null) banners.add(p); else d = ai.loadIcon(pm);
                } catch (Exception ignored) {
                    // no artwork for this app: the tile keeps its card colour
                }
                if (d != null) {
                    try {
                        d = composeTile(d, banners.contains(p));
                    } catch (Exception ignored) {
                        // keep the raw artwork if composing fails
                    }
                }
                got.put(p, d);
            }
            handler.post(() -> {
                if (gen != artGen) return; // packages changed while loading: a newer load will cover this
                artCache.putAll(got);
                bannerPkgs.addAll(banners);
                artInFlight.removeAll(got.keySet());
                applyPendingArt();
            });
        }, "art-load").start();
    }

    private void clearArtCache() {
        artCache.clear();
        bannerPkgs.clear();
        artInFlight.clear();
        artGen++;
    }

    private void applyPendingArt() {
        for (int i = artWaiting.size() - 1; i >= 0; i--) {
            PendingArt p = artWaiting.get(i);
            if (!artCache.containsKey(p.pkg)) continue;
            applyArtTo(p.view, p.pkg);
            artWaiting.remove(i);
        }
    }

    private void applyArtTo(ImageView art, String pkg) {
        // the tile bitmap is already built at exact tile size (see composeTile): show it 1:1
        art.setPadding(0, 0, 0, 0);
        art.setScaleType(ImageView.ScaleType.FIT_XY);
        art.setImageDrawable(artCache.get(pkg));
    }

    /**
     * Builds one tile's artwork at exact tile size, once, off the UI thread:
     * a soft blurred fill from the artwork itself (so every tile looks the same), with the real
     * artwork centred at its native pixels. It is only shrunk if it is larger than the tile, so it
     * is never stretched, which is what made the old thumbnails look soft.
     */
    private Drawable composeTile(Drawable src, boolean banner) {
        final int w = dp(APP_W), h = dp(APP_H);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        if (!banner) return composeIconTile(src, out, c, w, h);

        // 1. blurred fill: shrink the artwork to 1/16 and stretch it back up, then darken it
        int sw16 = Math.max(1, w / 16), sh16 = Math.max(1, h / 16);
        Bitmap tiny = Bitmap.createBitmap(sw16, sh16, Bitmap.Config.ARGB_8888);
        Canvas tc = new Canvas(tiny);
        src.setBounds(0, 0, sw16, sh16);
        src.draw(tc);
        Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(tiny, null, new Rect(0, 0, w, h), filter);
        c.drawColor(0xB3000000);
        tiny.recycle();

        // 2. the artwork: fill the tile when the crop is small (like Apple TV), otherwise centre it
        // at native size on the blurred fill. Never stretched.
        int sw = Math.max(1, src.getIntrinsicWidth()), sh = Math.max(1, src.getIntrinsicHeight());
        float cover = Math.max((float) w / sw, (float) h / sh);
        int dw, dh;
        if (cover <= 1.6f) {
            dw = Math.round(sw * cover); dh = Math.round(sh * cover); // fills the tile, crops a little
        } else {
            float fit = Math.min(1f, Math.min((float) w / sw, (float) h / sh));
            dw = Math.round(sw * fit); dh = Math.round(sh * fit);
        }
        int left = (w - dw) / 2, top = (h - dh) / 2;
        src.setBounds(left, top, left + dw, top + dh);
        src.draw(c);

        return new BitmapDrawable(getResources(), out);
    }

    /**
     * Icon-only app: a tile filled with a gradient taken from the icon's own colour, and the icon
     * centred at a size that reads clearly, like the app tiles on Apple TV. Nothing is stretched.
     */
    private Drawable composeIconTile(Drawable src, Bitmap out, Canvas c, int w, int h) {
        // average colour of the icon's visible pixels, sampled on a small grid
        int n = 8;
        Bitmap probe = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas pc = new Canvas(probe);
        src.setBounds(0, 0, n, n);
        src.draw(pc);
        long r = 0, g = 0, b = 0, count = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int px = probe.getPixel(x, y);
                if (Color.alpha(px) < 128) continue;
                r += Color.red(px); g += Color.green(px); b += Color.blue(px); count++;
            }
        }
        probe.recycle();
        int base = count == 0 ? 0xFF2A2A2E
                : Color.rgb((int) (r / count), (int) (g / count), (int) (b / count));
        // darken for the background so white artwork still stands out
        int top = darken(base, 0.55f), bottom = darken(base, 0.25f);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { top, bottom });
        bg.setBounds(0, 0, w, h);
        bg.draw(c);

        // the icon: about 60% of the tile height, centred, with its own aspect ratio
        int sw = Math.max(1, src.getIntrinsicWidth()), sh = Math.max(1, src.getIntrinsicHeight());
        int target = Math.round(h * 0.74f);
        float scale = (float) target / Math.max(sw, sh);
        int dw = Math.round(sw * scale), dh = Math.round(sh * scale);
        int left = (w - dw) / 2, top2 = (h - dh) / 2;
        src.setBounds(left, top2, left + dw, top2 + dh);
        src.draw(c);

        return new BitmapDrawable(getResources(), out);
    }

    private static int darken(int colour, float factor) {
        return Color.rgb(Math.round(Color.red(colour) * factor),
                Math.round(Color.green(colour) * factor), Math.round(Color.blue(colour) * factor));
    }

    private void loadBackdropList() {
        try (InputStream in = getAssets().open("backdrops.json")) {
            byte[] buf = new byte[in.available()];
            int read = in.read(buf);
            JSONArray list = new JSONArray(new String(buf, 0, read, "UTF-8"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject m = list.getJSONObject(i);
                String file = m.getString("file");
                backdrops.add(file);
            }
        } catch (Exception e) {
            // no artwork bundled: the background stays black
        }
    }

    // ---------- layout ----------

    private void buildShell() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        backA = backdropView();
        backB = backdropView();
        root.addView(backA);
        root.addView(backB);
        visible = backA;

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] { 0xF2000000, 0x99000000, 0xB3000000 }));
        root.addView(shade, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(48);
        column.setPadding(pad, dp(36), pad, dp(36));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.TOP);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        clockView = new TextView(this);
        clockView.setTextColor(TEXT);
        clockView.setTextSize(72);
        clockView.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        dateView = new TextView(this);
        dateView.setTextColor(TEXT);
        dateView.setTextSize(20);
        left.addView(clockView);
        left.addView(dateView);
        top.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setGravity(Gravity.END);
        pills.addView(gearButton());
        right.addView(pills, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        weatherView = new TextView(this);
        weatherView.setTextColor(TEXT);
        weatherView.setTextSize(20);
        weatherView.setGravity(Gravity.END);
        weatherView.setText("Weather");
        right.addView(weatherView);
        top.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        column.addView(top);

        contentHolder = new FrameLayout(this);
        column.addView(contentHolder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(column, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private TextView pill(String label, View.OnClickListener action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(TEXT);
        t.setTextSize(18);
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(40));
        bg.setColor(0x33FFFFFF);
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), 0, 0, dp(8));
        t.setLayoutParams(lp);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(action);
        t.setOnFocusChangeListener((v, f) -> t.setBackground(pillBg(f)));
        return t;
    }

    /** Round settings button in the top bar. Opens Customize, same as the old Customize tile. */
    private View gearButton() {
        View gear = new GearIcon(this, TEXT);
        gear.setBackground(pillBg(false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56));
        lp.setMargins(dp(8), 0, 0, dp(8));
        gear.setLayoutParams(lp);
        gear.setFocusable(true);
        gear.setClickable(true);
        gear.setOnClickListener(v -> { customizing = true; showCurrentView(); });
        gear.setOnFocusChangeListener((v, f) -> gear.setBackground(pillBg(f)));
        return gear;
    }

    /** A cog drawn in code: eight teeth around a ring, with a centre hole. Works on any TV. */
    private static class GearIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GearIcon(Context c, int color) {
            super(c);
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
        }

        private Path gear; // built once per size (Path.op is expensive), not on every draw

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(cx, cy) * 0.82f;
            float outer = r, inner = r * 0.90f, hole = r * 0.36f;
            int teeth = 8;
            double step = 2 * Math.PI / teeth;
            Path outline = new Path();
            for (int i = 0; i < teeth; i++) {
                double a = i * step;
                // one tooth: wide base on the ring, narrower tip outside it
                // one continuous outline: only the very first point starts the path
                point(outline, cx, cy, inner, a, i == 0);
                point(outline, cx, cy, outer, a + step * 0.08, false);
                point(outline, cx, cy, outer, a + step * 0.42, false);
                point(outline, cx, cy, inner, a + step * 0.50, false);
            }
            outline.close();
            // cut the centre hole out of the outline, so the background shows through it
            Path centre = new Path();
            centre.addCircle(cx, cy, hole, Path.Direction.CW);
            outline.op(centre, Path.Op.DIFFERENCE);
            gear = outline;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (gear != null) canvas.drawPath(gear, paint);
        }

        private static void point(Path p, float cx, float cy, float rad, double angle, boolean first) {
            float x = cx + (float) (rad * Math.cos(angle));
            float y = cy + (float) (rad * Math.sin(angle));
            if (first) p.moveTo(x, y); else p.lineTo(x, y);
        }
    }

    private GradientDrawable pillBg(boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(40));
        bg.setColor(focused ? 0x66FFFFFF : 0x33FFFFFF);
        return bg;
    }

    private ImageView backdropView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        v.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return v;
    }

    private void showCurrentView() {
        if (!customizing) subScreen = null;
        artWaiting.clear();
        warmArt(); // no-op unless an app has no artwork cached yet
        stopJiggle(); // the wobbling tile is about to be discarded
        contentHolder.removeAllViews();
        View view;
        if (!customizing) view = homeView();
        else if (SUB_REORDER.equals(subScreen)) view = reorderView();
        else if (SUB_GROUPS.equals(subScreen)) view = groupsView();
        else if (SUB_ADD_GROUP.equals(subScreen)) view = addGroupView();
        else if (SUB_REMOVE.equals(subScreen)) view = removeView();
        else view = customizeView();
        contentHolder.addView(view);
        // focus the row the user just acted on, else the first tile (not the scroll container)
        final String key = focusKey;
        focusKey = null;
        contentHolder.post(() -> {
            View target = key != null ? view.findViewWithTag(key) : null;
            if (target == null) target = findFirstFocusable(view);
            if (target != null) target.requestFocus();
        });
    }

    private View findFirstFocusable(View v) {
        if (v.isFocusable() && !(v instanceof ScrollView) && !(v instanceof HorizontalScrollView)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findFirstFocusable(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private View homeView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        // Continue watching: only when On, configured, and the last fetch returned items
        if (plexOn() && !plexItems.isEmpty()) {
            body.addView(sectionHead("Continue watching"));
            body.addView(plexRowView());
        }

        Set<String> hidden = hiddenSet();
        Set<String> grouped = new HashSet<>();
        for (Group g : groups) grouped.addAll(g.pkgs);

        // one titled row per group, in creation order; groups with no visible app are skipped
        for (Group g : groups) {
            Set<String> mine = new HashSet<>(g.pkgs);
            List<ResolveInfo> members = new ArrayList<>();
            for (ResolveInfo ri : apps) {
                String p = pkgOf(ri);
                if (mine.contains(p) && !hidden.contains(p)) members.add(ri);
            }
            if (members.isEmpty()) continue;
            body.addView(sectionHead(g.name));
            body.addView(appRowView(members, false));
        }

        // the Apps row: visible apps that are not in a group
        List<ResolveInfo> loose = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            String p = pkgOf(ri);
            if (hidden.contains(p) || grouped.contains(p)) continue;
            loose.add(ri);
        }
        // the Apps section sits low, so the second row is cut by the bottom edge: the peek that says
        // "there is more below"
        TextView appsHead = sectionHead("Apps");
        appsHead.setPadding(0, dp(112), 0, dp(6));
        body.addView(appsHead);
        body.addView(appRowView(loose, false)); // Customize lives behind the gear icon only
        scroll.addView(body);
        return scroll;
    }

    /** App tiles in a grid of APP_COLUMNS per row, all the same size, filling the width. */
    private View appRowView(List<ResolveInfo> list, boolean withCustomize) {
        PackageManager pm = getPackageManager();
        List<View> tiles = new ArrayList<>();
        for (ResolveInfo ri : list) tiles.add(appTile(ri, pm));
        if (withCustomize) {
            tiles.add(actionTile("Customize", () -> { customizing = true; showCurrentView(); }));
        }
        // plain rows of APP_COLUMNS equal-width cells: predictable on every TV
        LinearLayout grid = new LinearLayout(this);
        grid.setPadding(dp(16), 0, dp(16), 0); // room for the 8% focus lift so edge tiles stay inside the margin
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int start = 0; start < tiles.size(); start += APP_COLUMNS) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < APP_COLUMNS; c++) {
                int i = start + c;
                View cellView = i < tiles.size() ? tiles.get(i) : new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(dp(8), dp(8), dp(8), dp(8));
                row.addView(cellView, lp);
            }
            grid.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        grid.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        // Visible window: all of row 1 plus ~30% of row 2 (sized from the measured rows), so row 2 peeks
        // at the bottom edge. The remaining rows scroll inside this window; focus moves scroll it.
        ScrollView peek = new ScrollView(this);
        peek.setVerticalScrollBarEnabled(false);
        peek.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        peek.addView(grid);
        grid.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int rows = grid.getChildCount();
            if (rows == 0) return;
            // the window runs to the bottom of the screen, so the next row is cut by the screen edge
            // (an arbitrary height left an empty band under it)
            int[] loc = new int[2];
            peek.getLocationOnScreen(loc);
            int want = getResources().getDisplayMetrics().heightPixels - loc[1] - dp(16);
            if (want <= 0) return;
            ViewGroup.LayoutParams lp = peek.getLayoutParams();
            if (lp.height != want) {
                lp.height = want;
                peek.setLayoutParams(lp);
            }
        });
        return peek;
    }

    private TextView sectionHead(String title) {
        TextView head = new TextView(this);
        head.setText(title);
        head.setTextColor(TEXT);
        head.setTextSize(22);
        head.setPadding(0, dp(18), 0, dp(6));
        return head;
    }

    private GridLayout newGrid() {
        GridLayout grid = new GridLayout(this);
        // fit as many 260dp tiles as the screen holds; a fixed 4 pushed the last column off-screen
        int tileWidth = dp(276);
        int widthPx = getResources().getDisplayMetrics().widthPixels - dp(96);
        grid.setColumnCount(Math.max(2, widthPx / tileWidth));
        grid.setPadding(0, dp(12), 0, 0);
        return grid;
    }


    private View edgeFade(boolean leftEdge) {
        View fade = new View(this);
        int[] colours = leftEdge ? new int[] { 0xFF000000, 0x00000000 } : new int[] { 0x00000000, 0xFF000000 };
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colours));
        fade.setClickable(false);
        fade.setFocusable(false);
        fade.setLayoutParams(new FrameLayout.LayoutParams(dp(24), FrameLayout.LayoutParams.MATCH_PARENT,
                leftEdge ? Gravity.START : Gravity.END));
        return fade;
    }

    /** Art with a soft shadow view behind it, offset 6dp down. The shadow is only drawn while focused. */
    private FrameLayout shadowStack(View shadow, View art, int wDp, int hDp) {
        FrameLayout stack = new FrameLayout(this);
        // the shadow hangs 6dp below the art into the label's top padding, so the tile height does not change
        stack.setClipChildren(false);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(wDp), dp(hDp));
        sp.topMargin = dp(6);
        stack.addView(shadow, sp);
        stack.addView(art, new FrameLayout.LayoutParams(dp(wDp), dp(hDp)));
        stack.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp), dp(hDp)));
        return stack;
    }

    /** White focus ring drawn above the artwork (a background ring is hidden under the image). */
    private GradientDrawable ring(boolean on, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
        d.setColor(0x00000000);
        if (on) d.setStroke(dp(2), 0x80FFFFFF); // thin, translucent edge, not a hard ring
        return d;
    }

    private GradientDrawable shadowBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x99000000); // deeper, softer shadow under a lifted tile
        d.setCornerRadius(dp(14));
        return d;
    }






    private void loadThumb(String file, ImageView target) {
        new Thread(() -> {
            try (InputStream in = getAssets().open(file)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 4;
                final Bitmap bmp = BitmapFactory.decodeStream(in, null, o);
                handler.post(() -> target.setImageBitmap(bmp));
            } catch (Exception ignored) {
                // leave the placeholder colour
            }
        }, "thumb").start();
    }

    // ---------- plex: Continue watching ----------

    /** Off by default. Turned on in Customize. */
    private boolean plexOn() {
        return prefs().getBoolean(KEY_PLEX_ON, false);
    }

    /**
     * Server settings live in one file you control: /sdcard/tvhome/plex.json, e.g.
     * {"url": "http://192.168.1.50:32400", "token": "..."}. Nothing is read from intents, so no other app
     * can change them. The file is read each time, so editing it takes effect on the next load.
     */
    private static final String PLEX_CONFIG = "/sdcard/tvhome/plex.json";

    /** The app's own folder first (no storage permission needed), then the shared folder. */
    private JSONObject plexConfig() {
        java.io.File own = getExternalFilesDir(null);
        String[] paths = own == null ? new String[] { PLEX_CONFIG }
                : new String[] { new java.io.File(own, "plex.json").getPath(), PLEX_CONFIG };
        for (String path : paths) {
            try (InputStream in = new java.io.FileInputStream(path)) {
                byte[] buf = new byte[4096];
                int n = in.read(buf);
                return new JSONObject(new String(buf, 0, Math.max(0, n), "UTF-8"));
            } catch (Exception e) {
                // try the next location
            }
        }
        return null; // no file anywhere: Plex row stays hidden
    }

    private String plexBase() {
        JSONObject c = plexConfig();
        String u = c == null ? "" : c.optString("url", "").trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private String plexToken() {
        JSONObject c = plexConfig();
        return c == null ? "" : c.optString("token", "").trim();
    }

    private boolean plexConfigured() {
        JSONObject c = plexConfig(); // one file read, not one per field
        if (c == null) return false;
        String u = c.optString("url", "").trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1); // same rule as plexBase()
        return !u.isEmpty() && !c.optString("token", "").trim().isEmpty();
    }

    private String plexRowTitle(boolean on) {
        if (!on) return "Plex: Off";
        return plexConfigured() ? "Plex: On" : "Plex: On (no server set)";
    }

    /**
     * Fetches Continue watching on a background thread and posts the result back. Any failure (no server,
     * timeout, bad JSON) leaves the list empty, so the row is simply not shown.
     */
    private void loadPlex() {
        final int gen = ++plexGen;
        if (!plexOn() || !plexConfigured()) {
            plexItems.clear();
            return;
        }
        final String base = plexBase();
        final String token = plexToken();
        new Thread(() -> {
            final List<PlexItem> found = new ArrayList<>();
            try {
                // Continue watching first; when nothing is in progress, fall back to On Deck
                JSONArray meta = null;
                for (String ep : new String[] { "/hubs/continueWatching", "/library/onDeck" }) {
                    byte[] body = fetch(base + ep + "?X-Plex-Token=" + enc(token), true);
                    JSONObject container = new JSONObject(new String(body, "UTF-8")).getJSONObject("MediaContainer");
                    meta = container.optJSONArray("Metadata");
                    if (meta != null && meta.length() > 0) break;
                }
                if (meta != null) {
                    for (int i = 0; i < meta.length() && found.size() < PLEX_MAX_ITEMS; i++) {
                        JSONObject m = meta.getJSONObject(i);
                        String title = m.isNull("title") ? "" : m.optString("title", "");
                        if (title.isEmpty()) continue;
                        String show = m.isNull("grandparentTitle") ? "" : m.optString("grandparentTitle", "");
                        String thumb = m.isNull("thumb") ? "" : m.optString("thumb", "");
                        PlexItem item = new PlexItem(title, show, thumb);
                        if (thumb.startsWith("/library/")) item.art = plexThumb(base, token, thumb);
                        found.add(item);
                    }
                }
            } catch (Exception e) {
                found.clear(); // no server, timeout or bad JSON: show nothing (no token in messages)
                android.util.Log.w("TVHome", "plex load failed: " + e.getClass().getSimpleName());
            }
            handler.post(() -> {
                if (gen != plexGen) return; // a newer load or a Customize toggle superseded this one
                plexItems.clear();
                plexItems.addAll(found);
                if (!customizing) {
                    View f = getCurrentFocus();
                    if (f != null && f.getTag() instanceof String) focusKey = (String) f.getTag();
                    showCurrentView();
                }
            });
        }, "plex").start();
    }

    /** Thumb at inSampleSize 2, cached. Runs on the Plex thread only, never the UI thread. */
    private Bitmap plexThumb(String base, String token, String thumb) {
        Bitmap cached = plexArtCache.get(thumb);
        if (cached != null) return cached;
        try {
            byte[] bytes = fetch(base + thumb + "?X-Plex-Token=" + enc(token), false);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (bmp != null) plexArtCache.put(thumb, bmp);
            return bmp;
        } catch (Exception e) {
            return null; // the tile keeps its placeholder card
        }
    }

    /** GET with timeouts; body capped at 2 MB. Throws on HTTP or network errors (the URL is not included). */
    private byte[] fetch(String u, boolean json) throws Exception {
        URL url = new URL(u);
        if (!isPrivateHost(url.getHost())) {
            // plain HTTP is allowed only to home-network addresses, so the token never leaves the house
            throw new java.io.IOException("Plex address is not on the home network");
        }
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(PLEX_TIMEOUT_MS);
        c.setReadTimeout(PLEX_TIMEOUT_MS);
        if (json) c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    if (out.size() > 2 * 1024 * 1024) throw new java.io.IOException("response too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** True for localhost, the emulator host, and RFC 1918 private IPv4 addresses. Hostnames are refused. */
    static boolean isPrivateHost(String host) {
        if (host == null) return false;
        if (host.equals("localhost") || host.equals("10.0.2.2")) return true;
        String[] p = host.split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            for (String x : p) { int v = Integer.parseInt(x); if (v < 0 || v > 255) return false; }
            return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || a == 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    /** Continue watching: a horizontal row of APP_W x APP_H cards with the title under each, like the app tiles. */
    private View plexRowView() {
        HorizontalScrollView row = new HorizontalScrollView(this);
        row.setHorizontalScrollBarEnabled(false);
        row.setClipToPadding(false);
        row.setClipChildren(false);
        row.setPadding(dp(ROW_INSET), 0, dp(16), 0);
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.HORIZONTAL);
        for (PlexItem it : plexItems) items.addView(plexTile(it));
        row.addView(items);
        return row;
    }

    private View plexTile(PlexItem it) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.START);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setPadding(dp(8), dp(8), dp(8), dp(20));

        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(card(TILE, 0, 14));
        clipRound(art, 14);
        if (it.art != null) art.setImageBitmap(it.art);
        View shadow = new View(this);
        // a little smaller than the app tiles, so the Apps row below still shows on screen
        tile.addView(shadowStack(shadow, art, APP_W * 3 / 4, APP_H * 3 / 4));

        TextView label = new TextView(this);
        label.setText(it.title);
        label.setTextColor(DIM);
        label.setTextSize(16);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMaxWidth(dp(APP_W));
        label.setPadding(0, dp(8), 0, 0);
        tile.addView(label);
        if (!it.show.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(it.show);
            sub.setTextColor(DIM);
            sub.setTextSize(14);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setMaxWidth(dp(APP_W));
            tile.addView(sub);
        }

        tile.setTag("plex:" + it.title); // lets a rebuild keep focus on this tile
        tile.setOnClickListener(v -> openApp(PLEX_PKG));
        tile.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(220).start();
            art.setForeground(ring(focused, 14));
            shadow.setBackground(focused ? shadowBg() : null);
            label.setTextColor(focused ? TEXT : DIM);
        });
        return tile;
    }

    // ---------- customize ----------

    /** A scroll view around a vertical list of rows, the way every Customize screen is laid out. */
    /**
     * Settings panel: a dark, blurred-looking column on the right with a heading and grouped rows,
     * so it reads as a proper settings screen instead of loose text over the photo.
     */
    private ScrollView newList(LinearLayout list) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        // solid, not see-through: the home screen behind must not show through the text
        scroll.setBackground(new ColorDrawable(0xFF0C0C10));
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(96), dp(64), dp(96), dp(64));
        TextView heading = new TextView(this);
        heading.setText("Settings");
        heading.setTextColor(TEXT);
        heading.setTextSize(44);
        heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        heading.setPadding(0, 0, 0, dp(8));
        list.addView(heading);
        TextView sub = new TextView(this);
        sub.setText("Hecker TV");
        sub.setTextColor(DIM);
        sub.setTextSize(18);
        sub.setPadding(0, 0, 0, dp(32));
        list.addView(sub);
        scroll.addView(list);
        return scroll;
    }

    private View backRow() {
        return rowLabel("Back to Customize", "Return to the customize screen",
                () -> { subScreen = null; showCurrentView(); });
    }

    private View customizeView() {
        LinearLayout list = new LinearLayout(this);
        ScrollView scroll = newList(list);

        if (!hiddenSet().isEmpty()) {
            list.addView(rowLabel("Show all apps", "Unhide every app on the home screen",
                    () -> { prefs().edit().putStringSet(KEY_HIDDEN, new HashSet<String>()).apply(); showCurrentView(); }));
        }
        list.addView(rowLabel("Choose home screen", "Pick which launcher opens when you press Home",
                () -> safeStart(new Intent(Settings.ACTION_HOME_SETTINGS))));
        list.addView(rowLabel("Install an app file", "Install an APK you choose from storage",
                () -> safeStart(new Intent(this, InstallerActivity.class))));
        list.addView(rowLabel("Reorder apps", "Move apps earlier or later",
                () -> { subScreen = SUB_REORDER; showCurrentView(); }));
        list.addView(rowLabel("Add group", "Make a row of your own apps",
                () -> { subScreen = SUB_ADD_GROUP; showCurrentView(); }));
        if (!groups.isEmpty()) {
            list.addView(rowLabel("Groups", "Put apps into your groups",
                    () -> { subScreen = SUB_GROUPS; showCurrentView(); }));
            for (Group g : groups) {
                final String name = g.name;
                list.addView(rowLabel("Delete group " + name, "Its apps return to Apps",
                        () -> { deleteGroup(name); showCurrentView(); }));
            }
        }
        if (hasUserApps()) {
            list.addView(rowLabel("Remove apps", "Uninstall an app you installed",
                    () -> { subScreen = SUB_REMOVE; showCurrentView(); }));
        }
        final boolean plexIsOn = plexOn();
        final String plexNext = plexRowTitle(!plexIsOn);
        list.addView(rowLabel(plexRowTitle(plexIsOn), "Continue watching from your Plex server",
                () -> {
                    prefs().edit().putBoolean(KEY_PLEX_ON, !plexIsOn).apply();
                    loadPlex();
                    focusKey = plexNext;
                    showCurrentView();
                }));
        list.addView(rowLabel("Back to home", "Return to the home screen",
                () -> { customizing = false; showCurrentView(); }));

        Set<String> hidden = hiddenSet();
        for (ResolveInfo ri : apps) {
            final String pkg = pkgOf(ri);
            final boolean isHidden = hidden.contains(pkg);
            final String name = labelOf(ri);
            list.addView(rowLabel(name, isHidden ? "Hidden from home" : "Shown on home", () -> {
                setHidden(pkg, !isHidden);
                focusKey = name;
                showCurrentView();
            }));
        }
        return scroll;
    }

    /** Customize > Reorder apps: an "earlier" and a "later" row per visible app. */
    private View reorderView() {
        LinearLayout list = new LinearLayout(this);
        ScrollView scroll = newList(list);
        list.addView(backRow());
        Set<String> hidden = hiddenSet();
        for (ResolveInfo ri : apps) {
            final String pkg = pkgOf(ri);
            if (hidden.contains(pkg)) continue;
            final String name = labelOf(ri);
            final String earlier = name + ": move earlier";
            final String later = name + ": move later";
            list.addView(rowLabel(earlier, null, () -> { moveApp(pkg, -1); focusKey = earlier; showCurrentView(); }));
            list.addView(rowLabel(later, null, () -> { moveApp(pkg, 1); focusKey = later; showCurrentView(); }));
        }
        return scroll;
    }

    /** Customize > Groups: one tap cycles an app through the groups (none, 1, 2, ..., none). */
    private View groupsView() {
        LinearLayout list = new LinearLayout(this);
        ScrollView scroll = newList(list);
        list.addView(backRow());
        Set<String> hidden = hiddenSet();
        for (ResolveInfo ri : apps) {
            final String pkg = pkgOf(ri);
            if (hidden.contains(pkg)) continue;
            final String name = labelOf(ri);
            int gi = groupIndexOfPkg(pkg);
            String sub = gi >= 0 ? "In " + groups.get(gi).name : "Not in a group";
            list.addView(rowLabel(name, sub, () -> { cycleGroup(pkg); focusKey = name; showCurrentView(); }));
        }
        return scroll;
    }

    /** Customize > Add group: the preset names that are not used yet. */
    private View addGroupView() {
        LinearLayout list = new LinearLayout(this);
        ScrollView scroll = newList(list);
        list.addView(backRow());
        for (String preset : PRESETS) {
            if (groupIndexOfName(preset) >= 0) continue;
            list.addView(rowLabel(preset, "Add this row to the home screen", () -> {
                addGroup(preset);
                subScreen = null;
                showCurrentView();
            }));
        }
        return scroll;
    }

    /** Customize > Remove apps: user-installed apps only. Each one goes through Android's own uninstall dialog. */
    private View removeView() {
        LinearLayout list = new LinearLayout(this);
        ScrollView scroll = newList(list);
        list.addView(backRow());
        for (ResolveInfo ri : apps) {
            if (!isUserApp(ri)) continue;
            final String pkg = pkgOf(ri);
            String name = labelOf(ri);
            list.addView(rowLabel(name, "Uninstall",
                    () -> safeStart(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)))));
        }
        return scroll;
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "That screen isn't available on this TV", Toast.LENGTH_LONG).show();
        }
    }

    private View rowLabel(String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setTag(title); // lets a rebuild find this row again (see focusKey)
        row.setPadding(dp(28), dp(20), dp(28), dp(20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        row.setLayoutParams(lp);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTextSize(26);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        row.addView(t);
        if (subtitle != null) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextColor(DIM);
            s.setTextSize(17);
            s.setPadding(0, dp(6), 0, 0);
            row.addView(s);
        }
        row.setOnClickListener(v -> action.run());
        // every row is a card; the focused one gets a lighter card and an edge so the cursor is obvious
        row.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(focused ? 0xFF2A2A34 : 0xFF17171E);
            if (focused) bg.setStroke(dp(2), 0xFFFFFFFF);
            row.setBackground(bg);
        });
        GradientDrawable idle = new GradientDrawable();
        idle.setCornerRadius(dp(18));
        idle.setColor(0xFF17171E);
        row.setBackground(idle);
        return row;
    }

    /** Gives a grid cell equal width and centres the tile in it. */
    private View cell(View v) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.setGravity(Gravity.CENTER);
        lp.setMargins(dp(8), dp(8), dp(8), dp(8));
        v.setLayoutParams(lp);
        return v;
    }

    /** Rounded card: fill colour, optional white ring (stroke 0 = none). */
    private GradientDrawable card(int fill, int ring, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
        if (ring != 0) d.setStroke(dp(3), ring);
        return d;
    }

    private void clipRound(View v, float radiusDp) {
        v.setClipToOutline(true);
        v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
    }

    private View appTile(ResolveInfo ri, PackageManager pm) {
        final String pkg = pkgOf(ri);
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.START);
        tile.setFocusable(true);
        tile.setClickable(true);
        // extra room under the label: a focused tile scales up, and the focus scroll stops at its bottom edge
        tile.setPadding(dp(4), dp(4), dp(4), dp(20));

        ImageView art = new ImageView(this);
        art.setBackground(card(TILE, 0, 14));
        clipRound(art, 14);
        // cached once per package; if the art thread has not delivered yet, the view waits for it
        if (artCache.containsKey(pkg)) applyArtTo(art, pkg);
        else artWaiting.add(new PendingArt(pkg, art));
        View shadow = new View(this);
        tile.addView(shadowStack(shadow, art, APP_W, APP_H));

        TextView label = new TextView(this);
        label.setText(labelOf(ri));
        label.setTextColor(DIM);
        label.setTextSize(16);
        label.setGravity(Gravity.START);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(0, dp(8), 0, 0);
        tile.addView(label);

        tile.setTag(pkg); // lets a rebuild find this tile again (see focusKey)
        // click opens the app; while moving, click drops it where it is
        tile.setOnClickListener(v -> { if (movingPkg != null) stopMove(); else openApp(pkg); });
        tile.setOnLongClickListener(v -> { startMove(pkg); return true; });
        if (pkg.equals(movingPkg)) startJiggle(tile);
        tile.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(220).start();
            art.setForeground(ring(focused, 14));
            shadow.setBackground(focused ? shadowBg() : null);
            label.setTextColor(focused ? TEXT : DIM);
        });
        return tile;
    }

    private View actionTile(String name, Runnable action) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setPadding(dp(8), dp(8), dp(8), dp(8));
        // same card treatment as the app icons: a soft gradient, a centred glyph, and the label
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(card(TILE, 0, 14));
        clipRound(box, 14);
        GradientDrawable grad = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0xFF3A3A3F, 0xFF1C1C20 });
        grad.setCornerRadius(dp(14));
        box.setBackground(grad);
        if ("Customize".equals(name)) {
            box.addView(new GearIcon(this, TEXT), new LinearLayout.LayoutParams(dp(54), dp(54)));
        }
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(TEXT);
        label.setTextSize(20);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(8), 0, 0);
        box.addView(label);
        View shadow = new View(this);
        tile.addView(shadowStack(shadow, box, APP_W, APP_H));
        tile.setOnClickListener(v -> action.run());
        tile.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(220).start();
            GradientDrawable g2 = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { 0xFF3A3A3F, 0xFF1C1C20 });
            g2.setCornerRadius(dp(14));
            if (focused) g2.setStroke(dp(2), 0x80FFFFFF);
            box.setBackground(g2);
            shadow.setBackground(focused ? shadowBg() : null);
        });
        return tile;
    }

    // ---------- behaviour ----------

    private void openApp(String pkg) {
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (launch != null) {
            startActivity(launch);
        } else {
            Toast.makeText(this, "This app can't be opened from here", Toast.LENGTH_SHORT).show();
        }
    }

    private void startVoice() {
        Intent speech = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speech.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a title");
        try {
            startActivityForResult(speech, REQ_VOICE);
        } catch (Exception e) {
            Toast.makeText(this, "Voice search isn't available on this TV", Toast.LENGTH_LONG).show();
        }
    }

    private void updateClock() {
        Date now = new Date();
        clockView.setText(new SimpleDateFormat("h:mm a", Locale.getDefault()).format(now));
        dateView.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now));
    }

    private void loadWeather() {
        new Thread(() -> {
            String line;
            try {
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
                        + "&current=temperature_2m,weather_code&temperature_unit=fahrenheit";
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n = in.read(buf);
                    JSONObject cur = new JSONObject(new String(buf, 0, n, "UTF-8")).getJSONObject("current");
                    String deg = String.valueOf((char) 0xB0);
                    String dot = String.valueOf((char) 0xB7);
                    line = Math.round(cur.getDouble("temperature_2m")) + deg + "  " + weatherName(cur.getInt("weather_code")) + " " + dot + " " + PLACE;
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                line = "Weather unavailable";
            }
            final String shown = line;
            handler.post(() -> weatherView.setText(shown));
        }, "weather").start();
    }

    private String weatherName(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 95) return "Storms";
        return "Weather";
    }

    private static final String HOME_BACKGROUND = "home-background.jpg";

    private boolean assetExists(String name) {
        try (InputStream in = getAssets().open(name)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showNextBackdrop() {
        if (backdrops.isEmpty()) return;
        if (backdropIndex >= 0) return; // static: show the first backdrop once, never rotate
        backdropIndex = 0;
        // the fixed home background: home-background.jpg if you saved one, else the first backdrop
        final String file = assetExists(HOME_BACKGROUND) ? HOME_BACKGROUND : backdrops.get(backdropIndex);
        // decode off the main thread so focus and animation never wait on a JPEG
        new Thread(() -> {
            final Bitmap bmp = loadBackdrop(file);
            if (bmp == null) return;
            handler.post(() -> swapBackdrop(bmp));
        }, "backdrop-decode").start();
    }

    private void swapBackdrop(Bitmap bmp) {
        final ImageView incoming = (visible == backA) ? backB : backA;
        final ImageView outgoing = visible;
        incoming.setImageBitmap(bmp);
        incoming.setScaleX(1.0f);
        incoming.setScaleY(1.0f);
        incoming.animate().alpha(1f).setDuration(FADE_MS).start();
        outgoing.animate().alpha(0f).setDuration(FADE_MS).start();
        // slow zoom while the image is on screen
        visible = incoming;
    }

    private Bitmap loadBackdrop(String file) {
        try (InputStream in = getAssets().open(file)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
