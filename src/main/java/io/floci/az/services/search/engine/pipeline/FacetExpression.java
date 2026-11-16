package io.floci.az.services.search.engine.pipeline;

import io.floci.az.services.search.engine.index.DocumentCoercer;
import io.floci.az.services.search.model.EdmType;
import io.floci.az.services.search.model.SearchException;
import io.floci.az.services.search.model.SearchFieldDefinition;
import io.floci.az.services.search.model.SearchIndexDefinition;

import java.time.DateTimeException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One facet expression of a search request, per Learn's "Add faceted navigation" and the Search Documents reference: a
 * facetable field, then optional comma-separated {@code name:value} parameters:
 * <ul>
 *   <li>{@code count}: the most buckets returned, 10 by default, 0 for all;</li>
 *   <li>{@code sort}: {@code count} (descending, the default), {@code -count}, {@code value} or {@code -value};</li>
 *   <li>{@code values}: pipe-separated numbers or dates that split the field into ranges;</li>
 *   <li>{@code interval}: a positive number, or for dates {@code minute}, {@code hour}, {@code day}, {@code week},
 *       {@code month}, {@code quarter} or {@code year};</li>
 *   <li>{@code timeoffset}: {@code [+-]hh:mm}, {@code [+-]hhmm} or {@code [+-]hh}, only with a date interval.</li>
 * </ul>
 * {@code count} and {@code sort} cannot be combined with {@code values} or {@code interval}, which cannot be combined
 * with each other. Every violation is a 400.
 *
 * @param name       the field path as written, the key of the facet in {@code @search.facets}
 * @param field      the faceted field
 * @param count      the most value buckets returned; 0 for all
 * @param sort       the order of value buckets
 * @param boundaries the range boundaries of a {@code values} facet ({@code Long}, {@code Double} or {@code Instant}),
 *                   or null
 * @param interval   the bucket width of a numeric interval facet, or null
 * @param dateUnit   the bucket unit of a date interval facet, or null
 * @param timeOffset the UTC offset date buckets start at
 */
record FacetExpression(String name, SearchFieldDefinition field, int count, Sort sort, List<Object> boundaries,
                       Double interval, DateUnit dateUnit, ZoneOffset timeOffset) {

    static final int DEFAULT_COUNT = 10;

    /** The order of value buckets. */
    enum Sort {
        COUNT_DESCENDING, COUNT_ASCENDING, VALUE_ASCENDING, VALUE_DESCENDING
    }

    /** The calendar units of date interval facets. */
    enum DateUnit {
        MINUTE, HOUR, DAY, WEEK, MONTH, QUARTER, YEAR
    }

    /** Whether the facet counts distinct values (rather than ranges or intervals). */
    boolean countsValues() {
        return boundaries == null && interval == null && dateUnit == null;
    }

    /** Parses and binds {@code expression}. */
    static FacetExpression parse(String expression, SearchIndexDefinition definition) {
        String[] parts = expression.split(",");
        String name = parts[0].trim();
        if (name.isEmpty()) {
            throw invalid(expression, "it does not name a field");
        }
        SearchFieldDefinition field = definition.field(name).orElseThrow(() -> invalid(expression,
            "could not find a property named '" + name + "' on type 'search.document'"));
        if (!field.facetable() || field.isComplex() || field.isVector()) {
            throw invalid(expression, "the field '" + name + "' is not facetable; only fields marked facetable can "
                + "be used in facets");
        }
        Integer count = null;
        Sort sort = null;
        String values = null;
        String interval = null;
        String timeOffset = null;
        for (int index = 1; index < parts.length; index++) {
            String parameter = parts[index].trim();
            int colon = parameter.indexOf(':');
            if (colon <= 0) {
                throw invalid(expression, "'" + parameter + "' is not a name:value parameter");
            }
            String key = parameter.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = parameter.substring(colon + 1).trim();
            switch (key) {
                case "count" -> count = count(expression, value);
                case "sort" -> sort = sort(expression, value);
                case "values" -> values = value;
                case "interval" -> interval = value;
                case "timeoffset" -> timeOffset = value;
                default -> throw invalid(expression, "'" + key + "' is not a facet parameter; use count, sort, "
                    + "values, interval or timeoffset");
            }
        }
        if ((count != null || sort != null) && (values != null || interval != null)) {
            throw invalid(expression, "count and sort cannot be combined with values or interval");
        }
        if (values != null && interval != null) {
            throw invalid(expression, "values and interval cannot be combined");
        }
        EdmType type = field.type().element();
        boolean numeric = type.isNumeric();
        boolean date = type == EdmType.DATE_TIME_OFFSET;
        if ((values != null || interval != null) && !numeric && !date) {
            throw invalid(expression, "values and interval apply only to numeric and Edm.DateTimeOffset fields");
        }
        if (timeOffset != null && (interval == null || !date)) {
            throw invalid(expression, "timeoffset can only be used with an interval on an Edm.DateTimeOffset field");
        }
        List<Object> boundaries = values == null ? null : boundaries(expression, values, type);
        Double numericInterval = interval != null && numeric ? numericInterval(expression, interval) : null;
        DateUnit dateUnit = interval != null && date ? dateUnit(expression, interval) : null;
        ZoneOffset offset = timeOffset == null ? ZoneOffset.UTC : offset(expression, timeOffset);
        return new FacetExpression(name, field, count == null ? DEFAULT_COUNT : count,
            sort == null ? Sort.COUNT_DESCENDING : sort, boundaries, numericInterval, dateUnit, offset);
    }

    private static int count(String expression, String value) {
        try {
            int count = Integer.parseInt(value);
            if (count < 0) {
                throw invalid(expression, "count must not be negative");
            }
            return count;
        } catch (NumberFormatException e) {
            throw invalid(expression, "count must be an integer");
        }
    }

    private static Sort sort(String expression, String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "count" -> Sort.COUNT_DESCENDING;
            case "-count" -> Sort.COUNT_ASCENDING;
            case "value" -> Sort.VALUE_ASCENDING;
            case "-value" -> Sort.VALUE_DESCENDING;
            default -> throw invalid(expression, "sort must be count, -count, value or -value");
        };
    }

    private static List<Object> boundaries(String expression, String values, EdmType type) {
        List<Object> boundaries = new ArrayList<>();
        for (String value : values.split("\\|")) {
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                throw invalid(expression, "values must list numbers or dates separated by '|'");
            }
            boundaries.add(type == EdmType.DATE_TIME_OFFSET ? date(expression, trimmed)
                : number(expression, trimmed, type));
        }
        return List.copyOf(boundaries);
    }

    private static Object number(String expression, String value, EdmType type) {
        try {
            if (type != EdmType.DOUBLE && !value.contains(".") && !value.toLowerCase(Locale.ROOT).contains("e")) {
                return Long.parseLong(value);
            }
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number)) {
                throw invalid(expression, "'" + value + "' is not a finite number");
            }
            return number;
        } catch (NumberFormatException e) {
            throw invalid(expression, "'" + value + "' is not a number");
        }
    }

    private static Object date(String expression, String value) {
        Object instant = DocumentCoercer.parseDateTime(value);
        if (instant == null) {
            throw invalid(expression, "'" + value + "' is not an Edm.DateTimeOffset value");
        }
        return instant;
    }

    private static Double numericInterval(String expression, String value) {
        try {
            double interval = Double.parseDouble(value);
            if (!(interval > 0) || !Double.isFinite(interval)) {
                throw invalid(expression, "interval must be greater than zero");
            }
            return interval;
        } catch (NumberFormatException e) {
            throw invalid(expression, "interval must be a number greater than zero for a numeric field");
        }
    }

    private static DateUnit dateUnit(String expression, String value) {
        try {
            return DateUnit.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid(expression, "interval must be minute, hour, day, week, month, quarter or year for an "
                + "Edm.DateTimeOffset field");
        }
    }

    private static ZoneOffset offset(String expression, String value) {
        if (!value.matches("[+-]\\d{2}(:?\\d{2})?")) {
            throw invalid(expression, "timeoffset must be [+-]hh:mm, [+-]hhmm or [+-]hh");
        }
        try {
            return ZoneOffset.of(value.length() == 5 ? value.substring(0, 3) + ":" + value.substring(3) : value);
        } catch (DateTimeException e) {
            throw invalid(expression, "timeoffset '" + value + "' is out of range");
        }
    }

    private static SearchException invalid(String expression, String reason) {
        return SearchException.badRequest("Invalid facet expression '" + expression + "': " + reason
            + ".\r\nParameter name: facets");
    }
}
