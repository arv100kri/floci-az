package io.floci.az.services.search.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code #Microsoft.Skills.Util.DocumentExtractionSkill}: extracts {@code content} from the {@code file_data} file
 * reference ({@code {"$type":"file","data":"<base64>"}}, or {@code "url"} plus an optional {@code "sasToken"}, which is
 * downloaded). Text formats are extracted: plain text, Markdown, CSV and JSON as they are, HTML and XML without their
 * markup. Other formats (PDF, Office, images, archives) are reported with the documented "unsupported content type"
 * warning and produce no content: the emulator has no parsers for them. {@code parsingMode} {@code text} keeps markup,
 * {@code dataToExtract} {@code allMetadata} extracts no content, and {@code normalized_images} is always empty; asking
 * for images is a warning.
 */
final class DocumentExtractionSkill implements SkillExecutor {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile("(?is)<(script|style)\\b.*?</\\1\\s*>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern CDATA = Pattern.compile("(?s)<!\\[CDATA\\[(.*?)]]>");
    private static final Pattern BLOCK_END = Pattern.compile(
        "(?i)<\\s*(br|/p|/div|/h[1-6]|/li|/tr|/title|/table|/ul|/ol|/section|/article|/header|/footer)\\b[^>]*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n[ \\t]*(\\n[ \\t]*)+");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Map<String, String> ENTITIES = Map.of("amp", "&", "lt", "<", "gt", ">", "quot", "\"",
        "apos", "'", "nbsp", " ");

    private final SkillHttp http;

    DocumentExtractionSkill(SkillHttp http) {
        this.http = http;
    }

    @Override
    public List<RequiredInput> requiredInputs() {
        return List.of(new RequiredInput("file_data", InputType.OBJECT));
    }

    @Override
    public List<ObjectNode> execute(SkillDefinition skill, List<ObjectNode> records, SkillMessages messages) {
        ObjectNode json = skill.json();
        String parsingMode = lower(SkillDefinition.text(json, "parsingMode"), "default");
        boolean contentWanted = !"allmetadata".equals(lower(SkillDefinition.text(json, "dataToExtract"),
            "contentandmetadata"));
        JsonNode configuration = json.get("configuration");
        String imageAction = lower(configuration == null ? null : SkillDefinition.text(configuration, "imageAction"),
            "none");
        List<ObjectNode> results = new ArrayList<>(records.size());
        for (ObjectNode record : records) {
            ObjectNode result = NODES.objectNode();
            if (!imageAction.equals("none")) {
                messages.warning(skill, "Could not generate normalized images: image extraction ('imageAction' "
                    + configuration.get("imageAction").asText() + ") is not emulated by floci-az.");
            }
            byte[] file = read(skill, (ObjectNode) record.get("file_data"), messages);
            if (file != null && contentWanted) {
                String content = extract(skill, file, parsingMode, messages);
                if (content != null) {
                    result.put("content", content);
                }
            }
            result.putArray("normalized_images");
            results.add(file == null ? null : result);
        }
        return results;
    }

    private static String lower(String value, String defaultValue) {
        return value == null ? defaultValue : value.toLowerCase(Locale.ROOT);
    }

    /** The file's bytes; null, with a warning or an error, when the reference cannot be read. */
    private byte[] read(SkillDefinition skill, ObjectNode fileData, SkillMessages messages) {
        String type = SkillDefinition.text(fileData, "$type");
        String data = SkillDefinition.text(fileData, "data");
        String url = SkillDefinition.text(fileData, "url");
        if (!"file".equals(type) || (data == null && url == null)) {
            messages.warning(skill, "Could not execute skill because one or more skill input was invalid. Required "
                + "skill input was not of the expected format. Name: 'file_data'; a file reference is an object with "
                + "\"$type\": \"file\" and either \"data\" or \"url\".");
            return null;
        }
        if (data != null) {
            try {
                return Base64.getMimeDecoder().decode(data.replace('-', '+').replace('_', '/'));
            } catch (IllegalArgumentException e) {
                messages.warning(skill, "Could not execute skill because one or more skill input was invalid. The "
                    + "'data' of 'file_data' is not valid base64.");
                return null;
            }
        }
        return download(skill, url, SkillDefinition.text(fileData, "sasToken"), messages);
    }

    private byte[] download(SkillDefinition skill, String url, String sasToken, SkillMessages messages) {
        String target = url;
        if (sasToken != null && !sasToken.isBlank()) {
            String token = sasToken.startsWith("?") ? sasToken.substring(1) : sasToken;
            target = url + (url.contains("?") ? "&" : "?") + token;
        }
        URI uri = WebApiSkill.parseUri(target);
        if (uri == null) {
            messages.error(skill, "Could not execute skill because the file at '" + url + "' could not be "
                + "downloaded: it is not a valid http or https URL.");
            return null;
        }
        try {
            SkillHttp.Reply reply = http.send("GET", uri, Map.of(), null, DOWNLOAD_TIMEOUT, Set.of(429, 503));
            if (reply.isSuccess()) {
                return reply.body();
            }
            messages.error(skill, "Could not execute skill because the file at '" + url + "' could not be "
                + "downloaded: status " + reply.status() + ".");
        } catch (HttpTimeoutException e) {
            messages.error(skill, "Could not execute skill because the download of '" + url + "' timed out.");
        } catch (IOException e) {
            messages.error(skill, "Could not execute skill because the file at '" + url + "' could not be "
                + "downloaded: " + WebApiSkill.describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            messages.error(skill, "Could not execute skill because the download of '" + url + "' was interrupted.");
        }
        return null;
    }

    /** The text content of {@code file}; null, with the documented warning, for a format the emulator cannot read. */
    static String extract(SkillDefinition skill, byte[] file, String parsingMode, SkillMessages messages) {
        String binaryType = binaryContentType(file);
        String text = binaryType == null ? decodeText(file) : null;
        if (text == null) {
            messages.warning(skill, "Could not extract content or metadata from your document. Document has "
                + "unsupported content type '" + (binaryType == null ? "application/octet-stream" : binaryType)
                + "'. floci-az extracts text formats only.");
            return null;
        }
        if (parsingMode.equals("text") || parsingMode.equals("json")) {
            return text;
        }
        String head = text.stripLeading().toLowerCase(Locale.ROOT);
        if (head.startsWith("<!doctype html") || head.startsWith("<html") || head.contains("<body")) {
            return htmlToText(text);
        }
        if (head.startsWith("<?xml") || (head.startsWith("<") && head.contains(">"))) {
            return markupToText(text);
        }
        return text;
    }

    /** The content type of a binary format recognised by its signature, or null for anything else. */
    static String binaryContentType(byte[] file) {
        if (startsWith(file, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            return "application/pdf";
        }
        if (startsWith(file, new byte[] {0x50, 0x4B, 0x03, 0x04})) {
            return "application/zip";
        }
        if (startsWith(file, new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0})) {
            return "application/x-ole-storage";
        }
        if (startsWith(file, new byte[] {0x1F, (byte) 0x8B})) {
            return "application/gzip";
        }
        if (startsWith(file, "{\\rtf".getBytes(StandardCharsets.US_ASCII))) {
            return "application/rtf";
        }
        if (startsWith(file, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47})) {
            return "image/png";
        }
        if (startsWith(file, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "image/jpeg";
        }
        if (startsWith(file, "GIF8".getBytes(StandardCharsets.US_ASCII))) {
            return "image/gif";
        }
        if (startsWith(file, new byte[] {0x49, 0x49, 0x2A, 0x00}) || startsWith(file, new byte[] {0x4D, 0x4D, 0x00,
            0x2A})) {
            return "image/tiff";
        }
        return null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (data[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    /** The bytes as text: UTF-8, or UTF-16 with a byte order mark; null when they are not text. */
    static String decodeText(byte[] file) {
        if (file.length >= 2 && (file[0] & 0xFF) == 0xFF && (file[1] & 0xFF) == 0xFE) {
            return new String(file, 2, file.length - 2, StandardCharsets.UTF_16LE);
        }
        if (file.length >= 2 && (file[0] & 0xFF) == 0xFE && (file[1] & 0xFF) == 0xFF) {
            return new String(file, 2, file.length - 2, StandardCharsets.UTF_16BE);
        }
        int offset = file.length >= 3 && (file[0] & 0xFF) == 0xEF && (file[1] & 0xFF) == 0xBB
            && (file[2] & 0xFF) == 0xBF ? 3 : 0;
        for (int index = offset; index < file.length; index++) {
            if (file[index] == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(file, offset, file.length - offset))
                .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** The text of an HTML document: scripts, styles and tags removed, block ends as line breaks, entities decoded. */
    static String htmlToText(String html) {
        String text = SCRIPT_OR_STYLE.matcher(html).replaceAll(" ");
        text = COMMENT.matcher(text).replaceAll(" ");
        text = BLOCK_END.matcher(text).replaceAll("\n");
        text = TAG.matcher(text).replaceAll(" ");
        return tidy(decodeEntities(text));
    }

    /** The text of an XML document: CDATA kept, comments and tags removed, entities decoded. */
    static String markupToText(String xml) {
        String text = COMMENT.matcher(xml).replaceAll(" ");
        text = CDATA.matcher(text).replaceAll("$1");
        text = TAG.matcher(text).replaceAll(" ");
        return tidy(decodeEntities(text));
    }

    private static String tidy(String text) {
        String collapsed = SPACES.matcher(text).replaceAll(" ");
        collapsed = BLANK_LINES.matcher(collapsed).replaceAll("\n");
        StringBuilder lines = new StringBuilder();
        for (String line : collapsed.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                if (!lines.isEmpty()) {
                    lines.append('\n');
                }
                lines.append(trimmed);
            }
        }
        return lines.toString();
    }

    private static String decodeEntities(String text) {
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder decoded = new StringBuilder(text.length());
        while (matcher.find()) {
            String entity = matcher.group(1);
            String replacement;
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                replacement = codePoint(entity.substring(2), 16, matcher.group());
            } else if (entity.startsWith("#")) {
                replacement = codePoint(entity.substring(1), 10, matcher.group());
            } else {
                replacement = ENTITIES.getOrDefault(entity.toLowerCase(Locale.ROOT), matcher.group());
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
}
