package com.gathin.tvhome;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * show_system_pill: the RAM and storage pill in the top bar, and the apps its menu offers to close.
 * Nothing runs in the background: the pill is read while Home is resumed, the app list when the menu opens.
 */
final class SystemPill {
    private SystemPill() {}

    /** Memory in use as a percentage of MemTotal (total minus MemAvailable), or -1 when unreadable. */
    static int ramPercent() {
        long[] kb = memKb();
        return kb == null ? -1 : (int) Math.round(100.0 * (kb[1] - kb[0]) / kb[1]);
    }

    /** MemAvailable in MB, or -1 when unreadable. */
    static long availableMb() {
        long[] kb = memKb();
        return kb == null ? -1 : kb[0] / 1024;
    }

    /** {available, total} in kB from /proc/meminfo. */
    private static long[] memKb() {
        long available = -1, total = -1;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null && (available < 0 || total < 0)) {
                String[] t = line.split("\\s+");
                if (t.length < 2) continue;
                if (line.startsWith("MemAvailable:")) available = Long.parseLong(t[1]);
                else if (line.startsWith("MemTotal:")) total = Long.parseLong(t[1]);
            }
        } catch (IOException | NumberFormatException e) {
            return null;
        }
        return available >= 0 && total > 0 ? new long[]{available, total} : null;
    }

    /** The data partition in use as a percentage, or -1 when unreadable. */
    static int storagePercent() {
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            long total = fs.getTotalBytes();
            return total <= 0 ? -1 : (int) Math.round(100.0 * (total - fs.getAvailableBytes()) / total);
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /**
     * Apps with a Home or launcher entry that were in front since the TV started, most recent first, Home
     * itself left out. Android 9 shows apps no other app's running processes, so usage since boot stands in
     * for "running": it needs the usage-access app-op (granted over ADB), and is empty without it.
     */
    static List<String> recentApps(Context context) {
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return Collections.emptyList();
        long now = System.currentTimeMillis();
        long boot = now - SystemClock.elapsedRealtime();
        Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(boot, now);
        if (stats == null || stats.isEmpty()) return Collections.emptyList();
        PackageManager pm = context.getPackageManager();
        List<UsageStats> used = new ArrayList<>();
        for (UsageStats s : stats.values()) {
            String pkg = s.getPackageName();
            if (pkg.equals(context.getPackageName()) || s.getLastTimeUsed() < boot
                    || s.getTotalTimeInForeground() <= 0) continue;
            if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) continue;
            used.add(s);
        }
        used.sort((a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
        List<String> packages = new ArrayList<>();
        for (UsageStats s : used) packages.add(s.getPackageName());
        return packages;
    }

    /**
     * What Clean storage deletes, and nothing else: Downloads, apps' external cache folders (Android/data/<app>/cache,
     * which Android lets any app's cache be emptied from), thumbnail and lost-file folders, and Home's own caches.
     * Never Android/data/<app>/files, never anything under /data/data: app data and sign-ins stay where they are.
     */
    static java.util.List<java.io.File> junkRoots(Context context) {
        java.io.File sd = android.os.Environment.getExternalStorageDirectory();
        java.util.List<java.io.File> roots = new ArrayList<>();
        roots.add(new java.io.File(sd, android.os.Environment.DIRECTORY_DOWNLOADS));
        roots.add(new java.io.File(sd, "LOST.DIR"));
        roots.add(new java.io.File(sd, "DCIM/.thumbnails"));
        roots.add(new java.io.File(sd, "Pictures/.thumbnails"));
        roots.add(new java.io.File(sd, "Movies/.thumbnails"));
        java.io.File[] apps = new java.io.File(sd, "Android/data").listFiles();
        if (apps != null) for (java.io.File app : apps) roots.add(new java.io.File(app, "cache"));
        roots.add(context.getCacheDir());
        return roots;
    }

    /** Bytes Clean storage would free; the roots themselves are kept, only what is inside them counts. */
    static long junkBytes(Context context) {
        long total = 0;
        for (java.io.File root : junkRoots(context)) total += sizeInside(root);
        return total;
    }

    /** Deletes everything inside the junk roots and returns the bytes freed. Runs off the main thread. */
    static long cleanJunk(Context context) {
        long freed = 0;
        for (java.io.File root : junkRoots(context)) {
            java.io.File[] children = root.listFiles();
            if (children == null) continue;
            for (java.io.File child : children) freed += delete(child);
        }
        return freed;
    }

    private static long sizeInside(java.io.File dir) {
        java.io.File[] children = dir.listFiles();
        if (children == null) return 0;
        long total = 0;
        for (java.io.File child : children) {
            if (isLink(child)) continue;
            total += child.isDirectory() ? sizeInside(child) : child.length();
        }
        return total;
    }

    /** Deletes a file or a folder's contents and then the folder; a link is removed without following it. */
    private static long delete(java.io.File file) {
        if (file.isDirectory() && !isLink(file)) {
            long freed = 0;
            java.io.File[] children = file.listFiles();
            if (children != null) for (java.io.File child : children) freed += delete(child);
            file.delete();   // a folder something could not be deleted from stays, with what is left in it
            return freed;
        }
        long length = file.length();
        return file.delete() ? length : 0;
    }

    private static boolean isLink(java.io.File file) {
        try {
            return java.nio.file.Files.isSymbolicLink(file.toPath());
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Ends the app's background processes (KILL_BACKGROUND_PROCESSES). Apps playing in the foreground stay. */
    static void close(Context context, String pkg) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) am.killBackgroundProcesses(pkg);
    }
}
