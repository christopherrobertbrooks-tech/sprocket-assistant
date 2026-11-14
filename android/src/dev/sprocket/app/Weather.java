package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Sprocket's weather tool: live conditions and a three-day forecast from Open-Meteo (free, no account or key), for
 * the phone's rough area or a town he names. Only rough coordinates (two decimals, about a kilometre) or the town's
 * name leave the phone, and only when Claude asks for the weather. The report is short plain text for Claude to
 * answer from. Plain Java apart from the network call: test/WeatherTest.java.
 */
final class Weather {
    private Weather() { }

    static String forecastUrl(double lat, double lon, boolean us) {
        return String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f", lat, lon)
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,"
                + "wind_speed_10m,wind_gusts_10m"
                + "&hourly=temperature_2m,precipitation_probability,weather_code&forecast_hours=13"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,"
                + "precipitation_sum,sunrise,sunset&forecast_days=3&timezone=auto"
                + (us ? "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" : "");
    }

    /** Open-Meteo's town search takes a bare name: "Paris, Texas" asks for Paris, and pick() finds the Texas one. */
    static String geocodeUrl(String place) throws Exception {
        String name = place.split(",")[0].trim();
        return "https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(name, "UTF-8")
                + "&count=10&language=en&format=json";
    }

    /** The town he meant: the first (biggest) match whose state or country fits what followed the comma. */
    static JSONObject pick(JSONArray towns, String asked) {
        if (towns == null || towns.length() == 0) return null;
        int comma = asked.indexOf(',');
        String rest = comma < 0 ? "" : asked.substring(comma + 1).trim().toLowerCase(Locale.US);
        if (!rest.isEmpty())
            for (int i = 0; i < towns.length(); i++) {
                JSONObject t = towns.optJSONObject(i);
                if (t == null) continue;
                String where = (t.optString("admin1") + " " + t.optString("country") + " " + t.optString("country_code"))
                        .toLowerCase(Locale.US);
                if (where.contains(rest)) return t;
            }
        return towns.optJSONObject(0);
    }

    static String label(JSONObject town) {
        StringBuilder b = new StringBuilder(town.optString("name"));
        for (String k : new String[]{"admin1", "country"})
            if (!town.optString(k).isEmpty() && !town.optString(k).equals(town.optString("name"))) b.append(", ").append(town.optString(k));
        return b.toString();
    }

    /** WMO weather codes, as Open-Meteo documents them. */
    static String words(int code) {
        switch (code) {
            case 0: return "clear sky";
            case 1: return "mainly clear";
            case 2: return "partly cloudy";
            case 3: return "overcast";
            case 45: case 48: return "fog";
            case 51: return "light drizzle";
            case 53: return "drizzle";
            case 55: return "heavy drizzle";
            case 56: case 57: return "freezing drizzle";
            case 61: return "slight rain";
            case 63: return "rain";
            case 65: return "heavy rain";
            case 66: case 67: return "freezing rain";
            case 71: return "light snow";
            case 73: return "snow";
            case 75: return "heavy snow";
            case 77: return "snow grains";
            case 80: return "rain showers";
            case 81: return "heavy rain showers";
            case 82: return "violent rain showers";
            case 85: case 86: return "snow showers";
            case 95: return "thunderstorm";
            case 96: case 99: return "thunderstorm with hail";
            default: return "weather code " + code;
        }
    }

    /** "2026-10-17T18:15" -> "6:15 PM"; on the hour, "6 PM". */
    static String clock(String iso, boolean minutes) {
        int t = iso.indexOf('T');
        int h = Integer.parseInt(iso.substring(t + 1, t + 3)), m = Integer.parseInt(iso.substring(t + 4, t + 6));
        String hh = String.valueOf(h % 12 == 0 ? 12 : h % 12);
        return hh + (minutes ? String.format(Locale.US, ":%02d", m) : "") + (h < 12 ? " AM" : " PM");
    }

    private static long r(double v) { return Math.round(v); }

    /** Open-Meteo's answer as a short report. near: the place's name for the first line. */
    static String summary(JSONObject f, String near, boolean us) throws Exception {
        String deg = us ? "°F" : "°C", wind = us ? " mph" : " km/h", rain = us ? " in" : " mm";
        JSONObject c = f.getJSONObject("current");
        String now = c.getString("time");
        StringBuilder b = new StringBuilder("Weather near ").append(near).append(" (Open-Meteo, ").append(clock(now, true)).append(")\n");
        b.append("Now: ").append(r(c.getDouble("temperature_2m"))).append(deg)
                .append(", feels like ").append(r(c.getDouble("apparent_temperature"))).append(deg)
                .append(", ").append(words(c.getInt("weather_code")))
                .append(", wind ").append(r(c.getDouble("wind_speed_10m"))).append(wind)
                .append(" gusting to ").append(r(c.getDouble("wind_gusts_10m")))
                .append(", humidity ").append(r(c.getDouble("relative_humidity_2m"))).append("%.\n");
        // the next hours, every third one on the clock (12, 3, 6, 9), with the rain chance when it is 20% or more
        JSONObject h = f.getJSONObject("hourly");
        JSONArray ht = h.getJSONArray("time"), temp = h.getJSONArray("temperature_2m"), pop = h.getJSONArray("precipitation_probability");
        StringBuilder next = new StringBuilder();
        for (int i = 0; i < ht.length(); i++) {
            String t = ht.getString(i);
            if (t.compareTo(now) <= 0 || Integer.parseInt(t.substring(t.indexOf('T') + 1, t.indexOf('T') + 3)) % 3 != 0) continue;
            next.append(next.length() == 0 ? "" : ", ").append(clock(t, false)).append(' ').append(r(temp.getDouble(i))).append(deg);
            double p = pop.optDouble(i, -1);
            if (p >= 20) next.append(" rain ").append(r(p)).append('%');
        }
        if (next.length() > 0) b.append("Next hours: ").append(next).append(".\n");
        JSONObject d = f.getJSONObject("daily");
        JSONArray days = d.getJSONArray("time");
        for (int i = 0; i < days.length(); i++) {
            String day = LocalDate.parse(days.getString(i)).getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US);
            b.append(i == 0 ? "Today (" + day + ")" : day).append(": ").append(words(d.getJSONArray("weather_code").getInt(i)))
                    .append(", high ").append(r(d.getJSONArray("temperature_2m_max").getDouble(i))).append(deg)
                    .append(", low ").append(r(d.getJSONArray("temperature_2m_min").getDouble(i))).append(deg);
            double p = d.getJSONArray("precipitation_probability_max").optDouble(i, -1);
            if (p >= 0) b.append(", rain chance ").append(r(p)).append('%');
            double amount = d.getJSONArray("precipitation_sum").optDouble(i, 0);
            if (amount > 0) b.append(", ").append(us ? String.format(Locale.US, "%.2f", amount) : String.valueOf(r(amount))).append(rain);
            if (i == 0) b.append("; sunrise ").append(clock(d.getJSONArray("sunrise").getString(0), true))
                    .append(", sunset ").append(clock(d.getJSONArray("sunset").getString(0), true));
            b.append(".\n");
        }
        return b.toString().trim();
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(10000);
        int code = h.getResponseCode();
        if (code != 200) throw new Exception("Open-Meteo answered " + code);
        try (InputStream in = h.getInputStream()) {
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * The tool itself (network: off the main thread). asked: a town he named, or empty for where he is; here: the
     * phone's rough coordinates (null when location is off); near: their town's name, if known.
     */
    static String lookup(String asked, double[] here, String near, boolean us) throws Exception {
        if (asked != null && !asked.trim().isEmpty()) {
            JSONObject town = pick(get(geocodeUrl(asked.trim())).optJSONArray("results"), asked.trim());
            if (town == null) return "No town called \"" + asked.trim() + "\" found. Ask the boss to say it another way.";
            return summary(get(forecastUrl(town.getDouble("latitude"), town.getDouble("longitude"), us)), label(town), us);
        }
        if (here == null)
            return "The boss's location is unknown (location is off for Sprocket, or the phone has no fix yet). Ask them which town.";
        return summary(get(forecastUrl(here[0], here[1], us)), near == null || near.isEmpty() ? "the boss" : near, us);
    }
}
