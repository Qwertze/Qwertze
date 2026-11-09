package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationStoreTest {
    private static List<String> names(List<Conversation> list) {
        return list.stream().map(Conversation::name).collect(Collectors.toList());
    }

    @Test
    void friendsComeFirstThenByMostRecent() {
        ConversationStore s = new ConversationStore(50);
        s.addIncoming("Alice", "1", 100, false);
        s.addIncoming("Bob", "2", 300, false);
        s.addIncoming("Carol", "3", 200, false);
        s.addIncoming("Dave", "4", 400, false);
        Set<String> friends = Set.of("alice", "carol");
        List<Conversation> ordered = s.ordered(n -> friends.contains(n.toLowerCase()));
        assertEquals(List.of("Carol", "Alice", "Dave", "Bob"), names(ordered));
    }

    @Test
    void newActivityMovesAConversationUpWithinItsGroup() {
        ConversationStore s = new ConversationStore(50);
        s.addIncoming("Alice", "1", 100, false);
        s.addIncoming("Bob", "2", 200, false);
        assertEquals(List.of("Bob", "Alice"), names(s.ordered(n -> false)));
        s.addOutgoing("Alice", "reply", 300, Message.State.SENT);
        assertEquals(List.of("Alice", "Bob"), names(s.ordered(n -> false)));
    }

    @Test
    void equalTimesOrderByName() {
        ConversationStore s = new ConversationStore(50);
        s.addIncoming("Zed", "1", 100, false);
        s.addIncoming("Amy", "1", 100, false);
        s.addIncoming("Max", "1", 100, false);
        assertEquals(List.of("Amy", "Max", "Zed"), names(s.ordered(n -> false)));
    }

    @Test
    void aFriendIsFirstEvenWithoutRecentMessages() {
        ConversationStore s = new ConversationStore(50);
        s.addIncoming("Old_Friend", "x", 1, false);
        s.addIncoming("Stranger", "y", 999, false);
        assertEquals("Old_Friend", s.ordered(n -> n.equals("Old_Friend")).get(0).name());
    }

    @Test
    void namesAreCaseInsensitiveAndKeepTheLatestSpelling() {
        ConversationStore s = new ConversationStore(50);
        s.addIncoming("steve", "a", 1, false);
        s.addIncoming("STEVE", "b", 2, false);
        assertEquals(1, s.size());
        assertEquals("STEVE", s.get("Steve").name());
        assertEquals(2, s.get("sTeVe").messages().size());
    }

    @Test
    void unreadCountsAndReading() {
        ConversationStore s = new ConversationStore(50);
        s.addIncoming("Alice", "1", 1, false);
        s.addIncoming("Alice", "2", 2, false);
        s.addIncoming("Bob", "3", 3, false);
        assertEquals(2, s.get("Alice").unread());
        assertEquals(3, s.totalUnread());
        s.markRead("alice");
        assertEquals(0, s.get("Alice").unread());
        assertEquals(1, s.totalUnread());
        s.addOutgoing("Bob", "mine", 4, Message.State.SENT);
        assertEquals(1, s.get("Bob").unread()); // sending does not read, nor add
    }

    @Test
    void historyIsCappedPerPerson() {
        ConversationStore s = new ConversationStore(ConversationStore.MIN_CAP);
        for (int i = 0; i < 25; i++) s.addIncoming("Alice", "m" + i, i, false);
        List<Message> m = s.get("Alice").messages();
        assertEquals(ConversationStore.MIN_CAP, m.size());
        assertEquals("m24", m.get(m.size() - 1).text());
        assertEquals("m15", m.get(0).text());
    }

    @Test
    void loweringTheCapCutsAtOnce() {
        ConversationStore s = new ConversationStore(100);
        for (int i = 0; i < 50; i++) s.addIncoming("Alice", "m" + i, i, false);
        s.cap(20);
        assertEquals(20, s.get("Alice").messages().size());
        assertEquals("m30", s.get("Alice").messages().get(0).text());
    }

    @Test
    void capIsClamped() {
        assertEquals(ConversationStore.MIN_CAP, new ConversationStore(1).cap());
        assertEquals(ConversationStore.MAX_CAP, new ConversationStore(1_000_000).cap());
    }

    @Test
    void theNumberOfConversationsIsCapped() {
        ConversationStore s = new ConversationStore(50);
        for (int i = 0; i < ConversationStore.MAX_CONVERSATIONS + 20; i++) s.addIncoming("User" + i, "x", i, false);
        assertEquals(ConversationStore.MAX_CONVERSATIONS, s.size());
        assertNull(s.get("User0"));                       // the longest idle ones went
        assertNotNull(s.get("User" + (ConversationStore.MAX_CONVERSATIONS + 19)));
    }

    @Test
    void invalidNamesCanNotOpenAConversation() {
        ConversationStore s = new ConversationStore(50);
        assertThrows(IllegalArgumentException.class, () -> s.open("bad name", 0));
        assertThrows(IllegalArgumentException.class, () -> s.open(null, 0));
    }

    @Test
    void versionChangesWhenTheListCould() {
        ConversationStore s = new ConversationStore(50);
        int v0 = s.version();
        s.addIncoming("Alice", "1", 1, false);
        int v1 = s.version();
        assertTrue(v1 != v0);
        s.addOutgoing("Alice", "2", 2, Message.State.SENT);
        int v2 = s.version();
        assertTrue(v2 != v1);
        s.markRead("Alice");
        assertEquals(v2, s.version()); // reading does not reorder
        s.remove("Alice");
        assertTrue(s.version() != v2);
    }

    @Test
    void serverStoresKeepServersApart() {
        ServerStores st = new ServerStores(50);
        st.switchTo("A.example");
        st.current().addIncoming("Alice", "1", 1, false);
        st.switchTo("b.example");
        assertTrue(st.current().isEmpty());
        assertEquals("b.example", st.currentKey());
        assertEquals("local", ServerStores.normalize(null));
        assertEquals("local", ServerStores.normalize("  "));
        assertEquals("a_b", ServerStores.normalize("a b"));
        st.clear();
        assertEquals(List.of("b.example"), st.keys());
        assertTrue(st.store("a.example").isEmpty());
    }

    @Test
    void serverStoresAreLimited() {
        ServerStores st = new ServerStores(50);
        for (int i = 0; i < ServerStores.MAX_SERVERS + 5; i++) {
            st.switchTo("s" + i + ".example");
            st.current().addIncoming("Alice", "x", i, false);
        }
        assertTrue(st.keys().size() <= ServerStores.MAX_SERVERS);
        assertEquals("s" + (ServerStores.MAX_SERVERS + 4) + ".example", st.currentKey());
        assertEquals(1, st.current().size());
    }

    @Test
    void removeAndClear() {
        ConversationStore s = new ConversationStore(50);
        s.addIncoming("Alice", "1", 1, false);
        s.addIncoming("Bob", "2", 2, false);
        s.remove("ALICE");
        assertNull(s.get("Alice"));
        s.clear();
        assertTrue(s.isEmpty());
    }

    @Test
    void mergeAddsOnlyWhatIsMissingInOrder() {
        ConversationStore a = new ConversationStore(50);
        a.addIncoming("Alice", "two", 200, false);
        ConversationStore b = new ConversationStore(50);
        b.addIncoming("Alice", "one", 100, false);
        b.addIncoming("Alice", "two", 200, false);
        b.addIncoming("Bob", "hi", 50, false);
        a.mergeFrom(b);
        assertEquals(List.of("one", "two"), a.get("Alice").messages().stream().map(Message::text).collect(Collectors.toList()));
        assertEquals(1, a.get("Bob").messages().size());
    }
}
