package io.floci.az.services.search.engine.odata;

import io.floci.az.services.search.engine.index.GeoPoint;
import io.floci.az.services.search.engine.odata.FilterExpression.Operand;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The well-known text inside {@code geography'...'} literals: {@code POINT(lon lat)} and
 * {@code POLYGON((lon lat, lon lat, ...))}. A polygon must be closed (first and last point equal) and have at least
 * four points.
 */
final class WellKnownText {

    private WellKnownText() {
    }

    static Operand parse(String text, String parameter) {
        String trimmed = text.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.startsWith("POINT")) {
            String body = inner(trimmed.substring("POINT".length()), 1, text, parameter);
            return new Operand.PointConstant(point(body, text, parameter));
        }
        if (upper.startsWith("POLYGON")) {
            String body = inner(trimmed.substring("POLYGON".length()), 2, text, parameter);
            List<GeoPoint> ring = new ArrayList<>();
            for (String pair : body.split(",")) {
                ring.add(point(pair, text, parameter));
            }
            if (ring.size() < 4 || !ring.get(0).equals(ring.get(ring.size() - 1))) {
                throw ODataLexer.syntax(parameter, "the polygon '" + text + "' must be closed (its first and last "
                    + "points equal) and have at least four points");
            }
            return new Operand.PolygonConstant(ring);
        }
        throw ODataLexer.syntax(parameter, "'" + text + "' is not a geography POINT or POLYGON literal");
    }

    private static String inner(String rest, int depth, String text, String parameter) {
        String body = rest.trim();
        for (int level = 0; level < depth; level++) {
            if (!body.startsWith("(") || !body.endsWith(")")) {
                throw ODataLexer.syntax(parameter, "malformed geography literal '" + text + "'");
            }
            body = body.substring(1, body.length() - 1).trim();
        }
        return body;
    }

    private static GeoPoint point(String pair, String text, String parameter) {
        String[] parts = pair.trim().split("\\s+");
        if (parts.length != 2) {
            throw ODataLexer.syntax(parameter, "malformed coordinates in geography literal '" + text + "'");
        }
        try {
            double longitude = Double.parseDouble(parts[0]);
            double latitude = Double.parseDouble(parts[1]);
            if (longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
                throw ODataLexer.syntax(parameter, "the coordinates in geography literal '" + text
                    + "' are out of range");
            }
            return new GeoPoint(longitude, latitude);
        } catch (NumberFormatException e) {
            throw ODataLexer.syntax(parameter, "malformed coordinates in geography literal '" + text + "'");
        }
    }

    /** Whether {@code point} lies inside {@code ring}, by ray casting over longitude and latitude. */
    static boolean contains(List<GeoPoint> ring, GeoPoint point) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            double xi = ring.get(i).longitude();
            double yi = ring.get(i).latitude();
            double xj = ring.get(j).longitude();
            double yj = ring.get(j).latitude();
            boolean crosses = (yi > point.latitude()) != (yj > point.latitude());
            if (crosses && point.longitude() < (xj - xi) * (point.latitude() - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }
}
