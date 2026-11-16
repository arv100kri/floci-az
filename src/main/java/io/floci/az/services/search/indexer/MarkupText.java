package io.floci.az.services.search.indexer;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text extraction for HTML and XML ("Strip HTML elements and extract text", "Strip XML elements and extract text"):
 * comments, scripts and styles are removed, block elements become line breaks, every other tag disappears, character
 * references are decoded and whitespace is collapsed. HTML documents also yield their {@code <title>} and their
 * {@code description} and {@code keywords} meta tags.
 */
final class MarkupText {

    private static final Pattern COMMENTS = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern SCRIPTS = Pattern.compile("<(script|style)\\b[^>]*>.*?</\\1\\s*>",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern CDATA = Pattern.compile("<!\\[CDATA\\[(.*?)]]>", Pattern.DOTALL);
    private static final Pattern BLOCK_TAGS = Pattern.compile("</?(p|div|br|li|ul|ol|h[1-6]|tr|table|section|article"
        + "|header|footer|blockquote|pre|hr|title|nav|aside|main|dd|dt|dl)\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern TITLE = Pattern.compile("<title\\b[^>]*>(.*?)</title\\s*>",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern META = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile("([a-zA-Z-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);");
    private static final Map<String, String> NAMED = Map.of("amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos",
        "'", "nbsp", " ", "copy", "\u00a9", "reg", "\u00ae", "ndash", "\u2013", "mdash", "\u2014");

    private MarkupText() {
    }

    /**
     * The text and metadata of an HTML or XML document.
     *
     * @param text        the extracted text
     * @param title       the HTML title, or null
     * @param description the HTML description meta tag, or null
     * @param keywords    the HTML keywords meta tag, or null
     */
    record Extracted(String text, String title, String description, String keywords) {
    }

    static Extracted extract(String markup, boolean html) {
        String title = null;
        String description = null;
        String keywords = null;
        if (html) {
            Matcher titleMatcher = TITLE.matcher(markup);
            if (titleMatcher.find()) {
                title = collapse(decode(TAGS.matcher(titleMatcher.group(1)).replaceAll(""))).strip();
            }
            Matcher meta = META.matcher(markup);
            while (meta.find()) {
                String name = null;
                String content = null;
                Matcher attribute = ATTRIBUTE.matcher(meta.group());
                while (attribute.find()) {
                    String value = attribute.group(3) != null ? attribute.group(3) : attribute.group(4);
                    String attributeName = attribute.group(1).toLowerCase(Locale.ROOT);
                    if ("name".equals(attributeName)) {
                        name = value.toLowerCase(Locale.ROOT);
                    } else if ("content".equals(attributeName)) {
                        content = decode(value);
                    }
                }
                if ("description".equals(name)) {
                    description = content;
                } else if ("keywords".equals(name)) {
                    keywords = content;
                }
            }
        }
        String text = COMMENTS.matcher(markup).replaceAll(" ");
        text = SCRIPTS.matcher(text).replaceAll(" ");
        if (html) {
            text = TITLE.matcher(text).replaceAll(" ");
        }
        text = CDATA.matcher(text).replaceAll("$1");
        text = BLOCK_TAGS.matcher(text).replaceAll("\n");
        text = TAGS.matcher(text).replaceAll(html ? "" : " ");
        return new Extracted(collapse(decode(text)).strip(), title, description, keywords);
    }

    private static String decode(String text) {
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder decoded = new StringBuilder();
        while (matcher.find()) {
            String entity = matcher.group(1);
            String replacement;
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                replacement = codePoint(entity.substring(2), 16, matcher.group());
            } else if (entity.startsWith("#")) {
                replacement = codePoint(entity.substring(1), 10, matcher.group());
            } else {
                replacement = NAMED.getOrDefault(entity.toLowerCase(Locale.ROOT), matcher.group());
            }
            matcher.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(decoded);
        return decoded.toString();
    }

    private static String codePoint(String digits, int radix, String original) {
        try {
            int codePoint = Integer.parseInt(digits, radix);
            return Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : original;
        } catch (NumberFormatException e) {
            return original;
        }
    }

    /** Collapses runs of spaces and tabs, trims every line, and drops blank lines. */
    private static String collapse(String text) {
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\r\n|\r|\n", -1)) {
            String trimmed = line.replaceAll("[ \\t\\u00a0]+", " ").strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append(trimmed);
        }
        return result.toString();
    }
}
