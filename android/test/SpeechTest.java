package dev.sprocket.app;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Speech (what the voice is given) and the persona's hard rules. */
public class SpeechTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    public static void main(String[] a) throws Exception {
        eq("shouting isn't spelled out", Speech.forVoice("I was built for WAR!"), "I was built for war!");
        eq("real abbreviations stay", Speech.forVoice("Put me on the GPU. The V100, the GOOD one."), "Put me on the GPU. The V one hundred, the good one.");
        eq("markdown and emoji dropped", Speech.forVoice("**Done!** 🤖 See [this](https://x.y)."), "Done! See this.");
        eq("one-letter capitals untouched", Speech.forVoice("I am A unit."), "I am A unit.");
        // clock times as words (the owner's session 2026-10-17: "6:30 AM" was said "six, three zero AM")
        eq("times", Speech.forVoice("Your 6:30 AM alarm, 2:25 PM, 1:53 pm, 12:00 PM and 9:05 a.m."),
                "Your six thirty AM alarm, two twenty five PM, one fifty three PM, twelve PM and nine oh five AM.");
        eq("a time with no AM or PM", Speech.forVoice("Dinner at 6:00, or 7:15."), "Dinner at six o'clock, or seven fifteen.");
        eq("24-hour", Speech.forVoice("At 18:45 and 00:10."), "At six forty five PM and twelve ten AM.");
        eq("not times", Speech.forVoice("Odds of 3:1, version 0.1.45, and 10:75."), "Odds of 3:1, version 0.1.45, and 10:75.");
        // card names the way he says them (the owner 2026-10-17: "v 1 new new", "four thousand and seventy")
        eq("the V100 and its kin", Speech.forVoice("A V100, an A100 and an H100."), "A V one hundred, an A one hundred and an H one hundred.");
        eq("cards with their brand", Speech.forVoice("An RTX 4070, a GTX 1070, an RTX 3090 Ti and an RTX 2080."),
                "An RTX forty seventy, a GTX ten seventy, an RTX thirty ninety Ti and an RTX twenty eighty.");
        eq("bare card numbers", Speech.forVoice("The 4070 and the 1070 are fine; so is a 5090."), "The forty seventy and the ten seventy are fine; so is a fifty ninety.");
        eq("other numbers left alone (a bare 4070 is always the card)", Speech.forVoice("In 2070, 4070 dollars? No: 4,070 and 40700 and 4071."),
                "In 2070, forty seventy dollars? No: 4,070 and 40700 and 4071.");

        // the safety rules hold in the personality the app comes with and in the example it offers
        for (String file : new String[]{"android/assets/persona.md", "android/assets/persona-example.md"}) {
            String persona = new String(Files.readAllBytes(Paths.get(file)), "UTF-8").replaceAll("\\s+", " ");   // the file wraps lines
            String w = file.endsWith("example.md") ? "example: " : "default: ";
            eq(w + "facts stay right", persona.contains("The facts are always right."), true);
            eq(w + "no fake real actions", persona.contains("Never claim to have done something real that you did not do"), true);
            eq(w + "no jokes next to sad news (his session: a teen's death, then \"no toasters harmed\")",
                    persona.contains("Sad or serious things get no jokes.") && persona.contains("sign-off included"), true);
            eq(w + "spoken: no lists or markdown", persona.contains("no markdown, no bullet points or numbered lists"), true);
            if (file.endsWith("example.md")) {
                eq(w + "never 'minion' except the rule against it", persona.toLowerCase().split("minion", -1).length - 1, 1);
                eq(w + "the rule against it", persona.contains("Never call them \"minion\""), true);
            } else {
                eq(w + "no robot act", persona.contains("built for WAR") || persona.contains("combat unit"), false);
            }
        }

        System.out.println(fails == 0 ? "speech: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
