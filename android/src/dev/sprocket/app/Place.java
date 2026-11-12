package dev.sprocket.app;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;

import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Where the phone is, roughly (the owner 2026-10-17: "Phone's location"): approximate location only (the permission he
 * grants once), turned into a town by the phone's own geocoder, kept on the phone in Sprocket's settings. The town
 * goes to Claude with each question (web search's location, the note in his newest message); the rough coordinates
 * go to Open-Meteo only when he asks about the weather. Refreshed in the background when Sprocket opens.
 */
final class Place {
    private Place() { }

    private static final long FRESH_MS = 20 * 60 * 1000;            // a town is good for 20 minutes
    private static final ExecutorService ONE = Executors.newSingleThreadExecutor();   // one refresh at a time

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private static SharedPreferences p(Context c) { return c.getSharedPreferences("sprocket", Context.MODE_PRIVATE); }

    /** The last town found (empty strings when there is none yet); the time zone and country from the phone. */
    static Claude.Where where(Context c) {
        SharedPreferences s = p(c);
        if (!Config.on("location")) return new Claude.Where(TimeZone.getDefault().getID(), Locale.getDefault().getCountry(), "", "");
        String country = s.getString("place_country", "");
        if (country.isEmpty()) country = Locale.getDefault().getCountry();
        return new Claude.Where(TimeZone.getDefault().getID(), country, s.getString("place_city", ""), s.getString("place_region", ""));
    }

    /** The rough coordinates, or null when there are none. */
    static double[] coords(Context c) {
        SharedPreferences s = p(c);
        if (!Config.on("location")) return null;
        if (!s.contains("place_lat")) return null;
        return new double[]{Double.longBitsToDouble(s.getLong("place_lat", 0)), Double.longBitsToDouble(s.getLong("place_lon", 0))};
    }

    /** US units for the weather: the phone's own country (the US, Liberia and Myanmar use Fahrenheit). */
    static boolean usUnits(Context c) {
        String k = where(c).country;
        return k.equals("US") || k.equals("LR") || k.equals("MM");
    }

    /** In the background, if the town is old: a new rough fix and its town. */
    static void refresh(Context c) {
        if (!Config.on("location")) return;              // switched off in Settings
        Context app = c.getApplicationContext();
        ONE.execute(() -> refreshNow(app, false));
    }

    /** On a worker thread: a fix now if there is none (the weather tool needs one), waiting up to ~10 s. */
    static void ensure(Context c) {
        if (coords(c) != null) return;
        try { ONE.submit(() -> refreshNow(c.getApplicationContext(), true)).get(12, TimeUnit.SECONDS); } catch (Exception ignored) { }
    }

    private static void refreshNow(Context c, boolean force) {
        if (!allowed(c)) return;
        SharedPreferences s = p(c);
        if (!force && System.currentTimeMillis() - s.getLong("place_at", 0) < FRESH_MS) return;
        Location fix = fix(c);
        if (fix == null) return;
        // rounded to two decimals (about a kilometre) before it is kept or sent anywhere
        double lat = Math.round(fix.getLatitude() * 100) / 100.0, lon = Math.round(fix.getLongitude() * 100) / 100.0;
        SharedPreferences.Editor e = s.edit().putLong("place_lat", Double.doubleToLongBits(lat))
                .putLong("place_lon", Double.doubleToLongBits(lon)).putLong("place_at", System.currentTimeMillis());
        try {
            if (Geocoder.isPresent()) {
                @SuppressWarnings("deprecation")   // the blocking form: we are on a worker thread already
                List<Address> a = new Geocoder(c, Locale.US).getFromLocation(lat, lon, 1);
                if (a != null && !a.isEmpty()) {
                    Address x = a.get(0);
                    String city = x.getLocality() != null ? x.getLocality() : x.getSubAdminArea();
                    e.putString("place_city", city == null ? "" : city)
                            .putString("place_region", x.getAdminArea() == null ? "" : x.getAdminArea())
                            .putString("place_country", x.getCountryCode() == null ? "" : x.getCountryCode());
                }
            }
        } catch (Exception ex) {
            android.util.Log.w("Sprocket", "no town for the fix: " + ex);
        }
        e.apply();
    }

    /** The newest rough fix the phone already has, or a fresh one (Android 11+) if that is old or missing. */
    private static Location fix(Context c) {
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (lm == null) return null;
        Location best = null;
        for (String prov : lm.getProviders(true)) {
            try {
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException ignored) { }
        }
        if ((best == null || System.currentTimeMillis() - best.getTime() > 30 * 60 * 1000) && Build.VERSION.SDK_INT >= 30) {
            String prov = Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) ? LocationManager.FUSED_PROVIDER
                    : LocationManager.NETWORK_PROVIDER;
            CountDownLatch done = new CountDownLatch(1);
            Location[] got = {null};
            try {
                lm.getCurrentLocation(prov, null, Runnable::run, l -> { got[0] = l; done.countDown(); });
                done.await(10, TimeUnit.SECONDS);
            } catch (Exception ignored) { }
            if (got[0] != null) best = got[0];
        }
        return best;
    }
}
