package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A source scan: the only code that queues a message to be sent is {@code MessengerUi.submit()} (Enter or the Send
 * button), and the game's chat and command send calls appear only in the module's tick sender.
 */
class SendPathsTest {
    private static final Pattern ENQUEUE = Pattern.compile("\\b(?:core\\(\\)|core)\\s*\\.\\s*(send|sendGlobal)\\s*\\(");

    private static List<Path> sources() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String root : new String[]{"src/main/java", "src/1.21.4/java", "src/1.21.11/java"}) {
            Path p = Path.of(root);
            if (!Files.isDirectory(p)) continue;
            try (Stream<Path> s = Files.walk(p)) {
                s.filter(f -> f.toString().endsWith(".java")).forEach(out::add);
            }
        }
        return out;
    }

    @Test
    void onlyTheInputLineQueuesMessages() throws IOException {
        List<Path> files = sources();
        assertTrue(files.size() > 50, "the scan must find the sources (run from the project folder)");
        List<String> callers = new ArrayList<>();
        for (Path f : files) {
            String text = Files.readString(f);
            var m = ENQUEUE.matcher(text);
            while (m.find()) {
                callers.add(f.getFileName() + ":" + m.group(1));
                if (!f.getFileName().toString().equals("MessengerUi.java")) callers.add("UNEXPECTED " + f);
            }
        }
        assertTrue(callers.stream().noneMatch(c -> c.startsWith("UNEXPECTED")), callers.toString());
        // and in MessengerUi, only inside submit()
        String ui = Files.readString(Path.of("src/main/java/com/porter/qwerte/messenger/MessengerUi.java"));
        int submit = ui.indexOf("private void submit()");
        assertTrue(submit > 0);
        var m = ENQUEUE.matcher(ui);
        while (m.find()) assertTrue(m.start() > submit, "queued outside submit() at " + m.start());
        assertEquals(1, ui.split("private void submit\\(\\)", -1).length - 1);
        // submit() is only reached by Enter and the Send button
        Pattern call = Pattern.compile("\\bsubmit\\(\\)");
        int calls = 0;
        var cm = call.matcher(ui);
        while (cm.find()) calls++;
        assertEquals(3, calls, "the declaration, Enter and the Send button");
    }

    @Test
    void theGameSendCallsAreOnlyInTheTickSender() throws IOException {
        for (Path f : sources()) {
            if (!f.toString().replace('\\', '/').contains("/messenger/") && !f.getFileName().toString().equals("Messenger.java")) continue;
            String text = Files.readString(f);
            int chat = text.split("\\.sendChat\\(", -1).length - 1;
            int command = text.split("\\.sendCommand\\(", -1).length - 1;
            if (f.getFileName().toString().equals("Messenger.java")) {
                assertEquals(1, chat, "sendChat in Messenger.java");
                assertEquals(1, command, "sendCommand in Messenger.java");
            } else {
                assertEquals(0, chat + command, f.toString());
            }
        }
    }
}
