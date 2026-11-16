package io.floci.az.services.search.engine.index;

/**
 * A geography point, as {@code Edm.GeographyPoint} values and {@code geography'POINT(lon lat)'} literals hold it.
 *
 * @param longitude degrees east, -180 to 180
 * @param latitude  degrees north, -90 to 90
 */
public record GeoPoint(double longitude, double latitude) {

    /** The mean Earth radius, in kilometres, the great-circle distance uses. */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    /** The great-circle (haversine) distance to {@code other}, in kilometres, as {@code geo.distance} returns it. */
    public double distanceKm(GeoPoint other) {
        double latitude1 = Math.toRadians(latitude);
        double latitude2 = Math.toRadians(other.latitude);
        double deltaLatitude = latitude2 - latitude1;
        double deltaLongitude = Math.toRadians(other.longitude - longitude);
        double haversine = Math.sin(deltaLatitude / 2) * Math.sin(deltaLatitude / 2)
            + Math.cos(latitude1) * Math.cos(latitude2) * Math.sin(deltaLongitude / 2) * Math.sin(deltaLongitude / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(haversine)));
    }
}
