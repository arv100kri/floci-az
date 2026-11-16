package io.floci.az.services.search.indexer;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.search.model.SearchJson;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code markdown} parsing mode of Learn's "Index Markdown blobs and files". Only ATX headers ({@code #} to
 * {@code ######}, outside fenced code blocks) split the text; every other element is plain text. Headers deeper than
 * {@code markdownHeaderDepth} are plain text too. Lines keep their text and end with {@code \r\n}, as the documented
 * output shows.
 *
 * <ul>
 *   <li>{@code oneToMany}: one document per section that has content, with {@code content} (the section's raw
 *       Markdown), {@code sections} ({@code h1} to the configured depth, an empty string where no header is set) and
 *       {@code ordinal_position} (1, 2, ... in document order).</li>
 *   <li>{@code oneToOne}: one document with {@code document_content} (the non-blank lines of the whole text) and
 *       {@code sections}, the header tree: each section has {@code header_level}, {@code header_name},
 *       {@code content} (its non-blank lines, without a final line break) and {@code ordinal_position}; a top-level
 *       section always has a {@code sections} array, a nested one only when it has subsections, as the documented
 *       example has it.</li>
 * </ul>
 */
final class MarkdownSections {

    private static final Pattern HEADER = Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?(?:[ \\t]+#+)?[ \\t]*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(```|~~~)");
    private static final String NEWLINE = "\r\n";

    private MarkdownSections() {
    }

    /** One header line, or a line of text ({@code level} 0). */
    private record Line(int level, String text) {
    }

    /** The {@code oneToMany} documents of {@code markdown}. */
    static List<ObjectNode> oneToMany(String markdown, int depth) {
        List<ObjectNode> documents = new ArrayList<>();
        String[] headers = new String[depth];
        Arrays.fill(headers, "");
        List<String> content = new ArrayList<>();
        for (Line line : lines(markdown, depth)) {
            if (line.level() == 0) {
                content.add(line.text());
                continue;
            }
            emit(documents, headers, content);
            headers[line.level() - 1] = line.text();
            for (int deeper = line.level(); deeper < depth; deeper++) {
                headers[deeper] = "";
            }
        }
        emit(documents, headers, content);
        return documents;
    }

    private static void emit(List<ObjectNode> documents, String[] headers, List<String> content) {
        String text = joinTrimmed(content);
        content.clear();
        if (text.isEmpty()) {
            return;
        }
        ObjectNode document = SearchJson.object();
        document.put("content", text);
        ObjectNode sections = document.putObject("sections");
        for (int level = 0; level < headers.length; level++) {
            sections.put("h" + (level + 1), headers[level]);
        }
        document.put("ordinal_position", documents.size() + 1);
        documents.add(document);
    }

    /** The {@code oneToOne} document of {@code markdown}. */
    static ObjectNode oneToOne(String markdown, int depth) {
        List<Line> lines = lines(markdown, depth);
        StringBuilder all = new StringBuilder();
        for (Line line : lines) {
            String text = line.level() == 0 ? line.text() : "#".repeat(line.level()) + " " + line.text();
            if (!text.isBlank()) {
                all.append(text).append(NEWLINE);
            }
        }
        ObjectNode document = SearchJson.object();
        document.put("document_content", all.toString());
        ArrayNode topLevel = document.putArray("sections");
        Deque<ObjectNode> open = new ArrayDeque<>();
        Deque<Integer> levels = new ArrayDeque<>();
        List<String> content = new ArrayList<>();
        ObjectNode currentSection = null;
        int ordinal = 0;
        for (Line line : lines) {
            if (line.level() == 0) {
                if (currentSection != null) {
                    content.add(line.text());
                }
                continue;
            }
            closeContent(currentSection, content);
            while (!levels.isEmpty() && levels.peek() >= line.level()) {
                levels.pop();
                open.pop();
            }
            ObjectNode section = SearchJson.object();
            section.put("header_level", "h" + line.level());
            section.put("header_name", line.text());
            section.put("content", "");
            section.put("ordinal_position", ++ordinal);
            if (open.isEmpty()) {
                section.putArray("sections");
                topLevel.add(section);
            } else {
                ObjectNode parent = open.peek();
                ArrayNode siblings = parent.get("sections") instanceof ArrayNode existing ? existing
                    : parent.putArray("sections");
                siblings.add(section);
            }
            open.push(section);
            levels.push(line.level());
            currentSection = section;
        }
        closeContent(currentSection, content);
        return document;
    }

    private static void closeContent(ObjectNode section, List<String> content) {
        if (section != null) {
            List<String> nonBlank = content.stream().filter(text -> !text.isBlank()).toList();
            section.put("content", String.join(NEWLINE, nonBlank));
        }
        content.clear();
    }

    /** The lines of {@code markdown}, headers recognized up to {@code depth} and never inside a fenced block. */
    private static List<Line> lines(String markdown, int depth) {
        List<Line> lines = new ArrayList<>();
        boolean fenced = false;
        String fence = null;
        for (String raw : markdown.split("\r\n|\r|\n", -1)) {
            Matcher fenceMatcher = FENCE.matcher(raw);
            if (fenceMatcher.find()) {
                if (!fenced) {
                    fenced = true;
                    fence = fenceMatcher.group(1);
                } else if (fence.equals(fenceMatcher.group(1))) {
                    fenced = false;
                }
                lines.add(new Line(0, raw));
                continue;
            }
            Matcher header = fenced ? null : HEADER.matcher(raw);
            if (header != null && header.matches() && header.group(1).length() <= depth) {
                String title = header.group(2) == null ? "" : header.group(2).strip();
                lines.add(new Line(header.group(1).length(), title));
            } else {
                lines.add(new Line(0, raw));
            }
        }
        return lines;
    }

    /** The lines without leading and trailing blank lines, each ending with {@code \r\n}. */
    private static String joinTrimmed(List<String> content) {
        int start = 0;
        int end = content.size();
        while (start < end && content.get(start).isBlank()) {
            start++;
        }
        while (end > start && content.get(end - 1).isBlank()) {
            end--;
        }
        StringBuilder text = new StringBuilder();
        for (int index = start; index < end; index++) {
            text.append(content.get(index)).append(NEWLINE);
        }
        return text.toString();
    }
}
