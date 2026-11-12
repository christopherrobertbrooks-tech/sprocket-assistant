package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

/** Weather on the desktop JDK: Open-Meteo's answer turned into Sprocket's short report, the town picked. No network. */
public class WeatherTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    static void has(String what, String text, String part) {
        n++;
        if (!text.contains(part)) { fails++; System.out.println("FAIL " + what + ": no <" + part + "> in:\n" + text); }
    }

    // the shape of https://api.open-meteo.com/v1/forecast with Weather.forecastUrl's fields (US units)
    static final String FORECAST = "{\"timezone\":\"America/Chicago\","
            + "\"current\":{\"time\":\"2026-10-17T09:15\",\"temperature_2m\":61.2,\"apparent_temperature\":58.9,"
            + "\"relative_humidity_2m\":70,\"precipitation\":0.0,\"weather_code\":3,\"wind_speed_10m\":8.1,\"wind_gusts_10m\":15.2},"
            + "\"hourly\":{\"time\":[\"2026-10-17T09:00\",\"2026-10-17T10:00\",\"2026-10-17T11:00\",\"2026-10-17T12:00\","
            + "\"2026-10-17T13:00\",\"2026-10-17T14:00\",\"2026-10-17T15:00\",\"2026-10-17T16:00\",\"2026-10-17T17:00\","
            + "\"2026-10-17T18:00\",\"2026-10-17T19:00\",\"2026-10-17T20:00\",\"2026-10-17T21:00\"],"
            + "\"temperature_2m\":[61,62,63,65,66,67,67.4,66,64,63,60,58,56],"
            + "\"precipitation_probability\":[5,5,10,10,20,30,40,45,35,25,10,null,null],"
            + "\"weather_code\":[3,3,3,3,61,61,61,61,3,3,2,1,0]},"
            + "\"daily\":{\"time\":[\"2026-10-17\",\"2026-10-18\",\"2026-10-19\"],\"weather_code\":[61,3,0],"
            + "\"temperature_2m_max\":[68.4,64.0,70.2],\"temperature_2m_min\":[50.1,47.3,49.8],"
            + "\"precipitation_probability_max\":[45,10,0],\"precipitation_sum\":[0.12,0.0,0.0],"
            + "\"sunrise\":[\"2026-10-17T07:12\",\"2026-10-18T07:13\",\"2026-10-19T07:14\"],"
            + "\"sunset\":[\"2026-10-17T18:15\",\"2026-10-18T18:13\",\"2026-10-19T18:12\"]}}";

    public static void main(String[] a) throws Exception {
        String s = Weather.summary(new JSONObject(FORECAST), "Des Moines, Iowa", true);
        has("where and when", s, "Weather near Des Moines, Iowa (Open-Meteo, 9:15 AM)");
        has("now", s, "Now: 61°F, feels like 59°F, overcast, wind 8 mph gusting to 15, humidity 70%.");
        has("the next hours, every 3, rain chance when it matters", s, "Next hours: 12 PM 65°F, 3 PM 67°F rain 40%, 6 PM 63°F rain 25%, 9 PM 56°F.");
        has("today", s, "Today (Saturday): slight rain, high 68°F, low 50°F, rain chance 45%, 0.12 in; sunrise 7:12 AM, sunset 6:15 PM.");
        has("tomorrow by name", s, "Sunday: overcast, high 64°F, low 47°F, rain chance 10%.");
        has("the day after", s, "Monday: clear sky, high 70°F, low 50°F, rain chance 0%.");
        eq("short enough to send every time", s.length() < 600, true);
        has("metric elsewhere", Weather.summary(new JSONObject(FORECAST), "Paris", false), "Now: 61°C");

        eq("codes: thunder", Weather.words(95), "thunderstorm");
        eq("codes: unknown", Weather.words(42), "weather code 42");
        has("asks for the right fields", Weather.forecastUrl(41.587, -93.625, true),
                "latitude=41.59&longitude=-93.63&current=temperature_2m,apparent_temperature");
        has("US units", Weather.forecastUrl(41.587, -93.625, true), "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch");
        eq("metric: Open-Meteo's default units", Weather.forecastUrl(48.85, 2.35, false).contains("fahrenheit"), false);
        has("a town by name", Weather.geocodeUrl("Paris, Texas"), "geocoding-api.open-meteo.com/v1/search?name=Paris&count=10");

        JSONArray towns = new JSONArray()
                .put(new JSONObject().put("name", "Paris").put("admin1", "Île-de-France").put("country", "France").put("country_code", "FR")
                        .put("latitude", 48.85).put("longitude", 2.35))
                .put(new JSONObject().put("name", "Paris").put("admin1", "Texas").put("country", "United States").put("country_code", "US")
                        .put("latitude", 33.66).put("longitude", -95.56));
        eq("\"Paris, Texas\" is the Texas one", Weather.pick(towns, "Paris, Texas").getString("admin1"), "Texas");
        eq("plain \"Paris\" is the first (the biggest)", Weather.pick(towns, "Paris").getString("country"), "France");
        eq("its name for the report", Weather.label(Weather.pick(towns, "Paris, Texas")), "Paris, Texas, United States");
        eq("no towns", Weather.pick(new JSONArray(), "Nowhere"), null);

        System.out.println(fails == 0 ? "weather: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
