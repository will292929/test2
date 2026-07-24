package com.austinstrat.teamtexts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

public class MessageComposerTest {
    @Test public void replacesNameAndCsvFields() {
        MessageSection greeting = new MessageSection("Greeting");
        greeting.options.add("Good morning, {first_name}!");
        MessageSection detail = new MessageSection("Detail");
        detail.options.add("Your shift is {Shift}.");
        java.util.List<MessageSection> sections = java.util.Arrays.asList(greeting, detail);
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Full Name", "Alex Morgan");
        row.put("Phone", "2075550123");
        row.put("Shift", "North");

        String result = MessageComposer.compose(sections, row,
                "Full Name", "Phone", new Random(1));
        assertEquals("Good morning, Alex! Your shift is North.", result);
        assertFalse(MessageComposer.containsUnresolvedPlaceholder(result));
    }

    @Test public void preservesUnknownPlaceholderForVisibleWarning() {
        MessageSection section = new MessageSection("Message");
        section.options.add("Hello {unknown}");
        String result = MessageComposer.compose(java.util.Collections.singletonList(section),
                java.util.Collections.emptyMap(), "", "", new Random(1));
        assertTrue(MessageComposer.containsUnresolvedPlaceholder(result));
    }
}
