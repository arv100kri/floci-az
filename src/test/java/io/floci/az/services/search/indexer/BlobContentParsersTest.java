package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlobContentParsersTest {

    @Test
    void delimitedTextKeepsQuotedDelimitersAndLineBreaks() {
        List<List<String>> records = DelimitedText.parse("id|note\r\n\r\n1| \"a|b\" \r\n2|\"two\nlines\"\n", '|');
        assertEquals(List.of(List.of("id", "note"), List.of("1", "a|b"), List.of("2", "two\nlines")), records);
        assertEquals(List.of("id", "datePublished", "tags"), DelimitedText.headers("id, datePublished,tags"));
    }

    @Test
    void markdownIgnoresHeadersInCodeBlocksAndBelowTheConfiguredDepth() {
        String markdown = "intro line\n# Title\n```\n# not a header\n```\n### Deep\nbody\n";
        List<ObjectNode> sections = MarkdownSections.oneToMany(markdown, 2);
        assertEquals(2, sections.size());
        assertEquals("intro line\r\n", sections.getFirst().get("content").textValue());
        assertEquals("", sections.getFirst().path("sections").path("h1").textValue());
        assertEquals("```\r\n# not a header\r\n```\r\n### Deep\r\nbody\r\n",
            sections.get(1).get("content").textValue());
        assertEquals("Title", sections.get(1).path("sections").path("h1").textValue());
        assertEquals(2, sections.get(1).get("ordinal_position").intValue());
    }

    @Test
    void markupIsStrippedToItsText() {
        MarkupText.Extracted html = MarkupText.extract("<!-- hidden --><html><head><meta name=\"Description\" "
            + "content=\"A &quot;quoted&quot; page\"></head><body><style>p {}</style><ul><li>One &amp; two</li>"
            + "<li>&#x33;</li></ul></body></html>", true);
        assertEquals("One & two\n3", html.text());
        assertEquals("A \"quoted\" page", html.description());
        assertEquals("Ship it now", MarkupText.extract("<order><item>Ship</item><item>it</item> now</order>", false)
            .text());
    }
}
