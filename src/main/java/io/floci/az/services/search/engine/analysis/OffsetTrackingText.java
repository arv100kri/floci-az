package io.floci.az.services.search.engine.analysis;

import java.util.Arrays;

/**
 * Builds the output of a character filter while recording where each output character came from, so token offsets in
 * the output can be mapped back to the input ({@link CharFilter.Result#offsetCorrection()}). Copied characters map to
 * themselves; the characters of a replacement map into the span they replaced; a deletion maps nothing, so an offset
 * just after it maps past the deleted input. Not thread-safe; one instance per filter application.
 */
final class OffsetTrackingText {

    private final String input;
    private final StringBuilder text;
    private int[] sources;
    private int length;

    OffsetTrackingText(String input) {
        this.input = input;
        this.text = new StringBuilder(input.length());
        this.sources = new int[input.length() + 1];
    }

    /** Copies {@code input[from, to)} unchanged. */
    void copy(int from, int to) {
        for (int index = from; index < to; index++) {
            append(input.charAt(index), index);
        }
    }

    /** Writes {@code replacement} in place of {@code input[from, to)}; an empty replacement deletes the span. */
    void replace(String replacement, int from, int to) {
        int span = Math.max(0, to - from - 1);
        for (int index = 0; index < replacement.length(); index++) {
            append(replacement.charAt(index), from + Math.min(index, span));
        }
    }

    private void append(char character, int source) {
        if (length == sources.length) {
            sources = Arrays.copyOf(sources, sources.length * 2 + 1);
        }
        text.append(character);
        sources[length++] = source;
    }

    CharFilter.Result result() {
        if (length == input.length() && text.toString().equals(input)) {
            return CharFilter.Result.unchanged(input);
        }
        int[] map = Arrays.copyOf(sources, length + 1);
        map[length] = input.length();
        int inputLength = input.length();
        return new CharFilter.Result(text.toString(),
            offset -> offset >= 0 && offset < map.length ? map[offset] : inputLength);
    }
}
