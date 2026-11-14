#!/usr/bin/env bash
# Sprocket's plain-Java parts on the desktop JDK (org.json from android/test: android.jar's is a stub).
set -euo pipefail
cd "$(dirname "$0")/.."
A=$HOME/.local/android; J=$A/jdk/bin; [ -d "$J" ] || J=$(dirname "$(command -v javac)")
PLAT=${ANDROID_HOME:-$A/sdk}/platforms/android-35/android.jar
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
S=android/src/dev/sprocket/app
"$J/javac" -encoding UTF-8 -cp "$PLAT" -d "$T" android/test/org/json/*.java $S/Claude.java $S/Chat.java $S/Speech.java $S/Weather.java $S/PhoneTools.java $S/NameMatch.java $S/Memory.java $S/VoiceGroups.java $S/Research.java $S/Markdown.java $S/Lists.java $S/Messages.java $S/Notices.java $S/Machines.java $S/GitHub.java $S/Goals.java $S/Feed.java $S/Studio.java $S/Conversation.java $S/Screen.java $S/Calls.java $S/NotionSync.java $S/Config.java $S/Speechy.java $S/Listen.java $S/Setup.java $S/AppNotes.java \
  android/test/ClaudeTest.java android/test/ChatTest.java android/test/SpeechTest.java android/test/WeatherTest.java android/test/PhoneToolsTest.java android/test/NameMatchTest.java android/test/MemoryTest.java android/test/VoiceGroupsTest.java android/test/ResearchTest.java android/test/ListsTest.java android/test/MessagesTest.java android/test/NoticesTest.java android/test/MachinesTest.java android/test/GitHubTest.java android/test/GoalsTest.java android/test/FeedTest.java android/test/StudioTest.java android/test/ConversationTest.java android/test/DoorTest.java android/test/ScreenTest.java android/test/CallsTest.java android/test/NotionSyncTest.java android/test/ConfigTest.java android/test/SpeechyTest.java android/test/SetupTest.java android/test/CheckTest.java android/test/AppNotesTest.java
for t in ClaudeTest ChatTest SpeechTest WeatherTest PhoneToolsTest NameMatchTest MemoryTest VoiceGroupsTest ResearchTest ListsTest MessagesTest NoticesTest MachinesTest GitHubTest GoalsTest FeedTest StudioTest ConversationTest DoorTest ScreenTest CallsTest NotionSyncTest ConfigTest SpeechyTest SetupTest CheckTest AppNotesTest; do "$J/java" -cp "$T:$PLAT" dev.sprocket.app.$t; done   # $T first: the test org.json
