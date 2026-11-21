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
        // oz is "ounce", never the name Oz (11-20)
        eq("a quarter", Speech.forVoice("Deals on a 1/4oz of coffee"), "Deals on a quarter ounce of coffee");
        eq("an eighth and a half", Speech.forVoice("1/8 oz or 1/2oz"), "eighth ounce or half ounce");
        eq("ounces", Speech.forVoice("The 1oz jar is $100, the 3.5 oz bag too"), "The 1 ounce jar is 100 bucks, the 3.5 ounces bag too");
        eq("a store's name", Speech.forVoice("Opening Market OZ now"), "Opening Market ounce now");
        eq("not inside words", Speech.forVoice("Ozzy reads Ozymandias"), "Ozzy reads Ozymandias");
        // prices (11-20: "$31.50" was said "thirty one dollars fifty")
        eq("dollars and cents", Speech.forVoice("Party trays are $31.50, was $45."), "Party trays are 31 dollars and 50 cents, was 45 dollars.");   // 81, 45: dollars
        eq("whole dollars", Speech.forVoice("Iced tea is $6.00."), "Iced tea is 6 dollars.");
        eq("one of each", Speech.forVoice("It's $1.01."), "It's 1 dollar and 1 cent.");
        eq("cents only", Speech.forVoice("Add $0.75 for cheese."), "Add 75 cents for cheese.");
        eq("thousands", Speech.forVoice("Total $1,234.56 today"), "Total 1,234 dollars and 56 cents today");
        eq("3 for $100", Speech.forVoice("3 for $100"), "3 for 100 bucks");   // 100: a buck-day
        // "bucks" now and then (11-20): about one price in three, the same price always said the same way
        eq("a list varies", Speech.forVoice("$19.98, $22.49, $33.79"), "19 dollars and 98 cents, 22 dollars and 49 cents, 33 bucks and 79 cents");
        eq("one buck", Speech.forVoice("$1"), "1 buck");
        eq("cents only stay cents", Speech.forVoice("$0.76"), "76 cents");
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
