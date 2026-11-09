package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContactPickerTest {
    private static List<String> names(List<ContactPicker.Entry> list) {
        return list.stream().map(ContactPicker.Entry::name).collect(Collectors.toList());
    }

    private static List<ContactPicker.Entry> build(String q, List<String> f, List<String> o, List<String> c) {
        return ContactPicker.build(q, f, o, c, "Me_Self");
    }

    @Test
    void groupOrderIsFriendsOnlineConversations() {
        var r = build("", List.of("Fred"), List.of("Olga", "Omar"), List.of("Carl"));
        assertEquals(List.of("Fred", "Olga", "Omar", "Carl"), names(r));
        assertEquals(ContactPicker.Source.FRIEND, r.get(0).source());
        assertEquals(ContactPicker.Source.ONLINE, r.get(1).source());
        assertEquals(ContactPicker.Source.CONVERSATION, r.get(3).source());
    }

    @Test
    void doublesAreRemovedIgnoringCaseKeepingTheFirstGroup() {
        var r = build("", List.of("Steve"), List.of("STEVE", "Alex"), List.of("steve", "alex", "Zed"));
        assertEquals(List.of("Steve", "Alex", "Zed"), names(r));
        assertEquals(ContactPicker.Source.FRIEND, r.get(0).source());
        assertEquals(ContactPicker.Source.ONLINE, r.get(1).source());
    }

    @Test
    void yourselfIsNeverOffered() {
        var r = build("", List.of("me_self"), List.of("ME_SELF", "Bob"), List.of("Me_Self"));
        assertEquals(List.of("Bob"), names(r));
        // not even as a typed name
        assertTrue(build("Me_Self", List.of(), List.of(), List.of()).isEmpty());
    }

    @Test
    void namesThatCanNotBeMessagedAreLeftOut() {
        var r = build("", List.of(".BedrockGuy", "ab", "Good_One", "has space"), List.of(), List.of());
        assertEquals(List.of("Good_One"), names(r));
    }

    @Test
    void filterIsCaseInsensitiveSubstringWithPrefixFirstPerGroup() {
        var r = build("ste", List.of("xSteve", "Steven"), List.of("Stella", "MrSTEAK", "Bob"), List.of("Pasteur", "Stephen"));
        // groups stay in order (friends, online, conversations); inside each, names starting with the text come first
        assertEquals(ContactPicker.Source.TYPED, r.get(0).source()); // "ste" is itself a valid name
        assertEquals(List.of("Steven", "xSteve", "Stella", "MrSTEAK", "Stephen", "Pasteur"), names(r.subList(1, r.size())));
    }

    @Test
    void anExactMatchComesFirst() {
        var r = build("steve", List.of("Steve2"), List.of("Steve"), List.of());
        assertEquals(List.of("Steve", "Steve2"), names(r));
    }

    @Test
    void aValidNameNotInTheListIsOfferedFirst() {
        var r = build("Newguy", List.of("Newguy2"), List.of(), List.of());
        assertEquals(ContactPicker.Source.TYPED, r.get(0).source());
        assertEquals("Newguy", r.get(0).name());
        assertEquals("Newguy2", r.get(1).name());
        // with spaces around, trimmed
        assertEquals("Newguy", build("  Newguy ", List.of(), List.of(), List.of()).get(0).name());
    }

    @Test
    void aNameAlreadyInTheListIsNotOfferedAgain() {
        var r = build("steve", List.of(), List.of("Steve"), List.of());
        assertEquals(1, r.size());
        assertEquals(ContactPicker.Source.ONLINE, r.get(0).source());
        assertEquals(1, build("STEVE", List.of(), List.of(), List.of("Steve")).size());
    }

    @Test
    void invalidTypedTextOffersNothingExtra() {
        assertTrue(build("ab", List.of(), List.of(), List.of()).isEmpty());
        assertTrue(build("a".repeat(17), List.of(), List.of(), List.of()).isEmpty());
        assertTrue(build("bad name", List.of(), List.of(), List.of()).isEmpty());
        assertTrue(build("bad-name", List.of(), List.of(), List.of()).isEmpty());
        assertTrue(build("xéy", List.of(), List.of(), List.of()).isEmpty());
    }

    @Test
    void usernameRule() {
        assertTrue(ContactPicker.validName("abc"));
        assertTrue(ContactPicker.validName("A_1_b_2_c_3_d_4"));
        assertTrue(ContactPicker.validName("a".repeat(16)));
        assertFalse(ContactPicker.validName("a".repeat(17)));
        assertFalse(ContactPicker.validName("ab"));
        assertFalse(ContactPicker.validName(null));
        assertFalse(ContactPicker.validName("ab\n"));
        assertFalse(ContactPicker.validName(""));
    }

    @Test
    void nullGroupsAndQueryAreFine() {
        assertEquals(List.of("Bob"), names(ContactPicker.build(null, null, List.of("Bob"), null, null)));
    }

    @Test
    void emptyQueryShowsEverythingAndNoTypedEntry() {
        var r = build("   ", List.of("Aaa"), List.of("Bbb"), List.of("Ccc"));
        assertEquals(3, r.size());
        assertFalse(r.stream().anyMatch(e -> e.source() == ContactPicker.Source.TYPED));
    }
}
