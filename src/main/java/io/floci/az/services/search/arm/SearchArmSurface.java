package io.floci.az.services.search.arm;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.GlobalName;
import io.floci.az.core.arm.LocationPolicy;
import io.floci.az.core.arm.NameRule;
import io.floci.az.core.cloud.CloudSuffix;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/** The ARM resource types {@link SearchArmService} serves. */
@ApplicationScoped
public class SearchArmSurface implements ArmSurfaceProvider {

    static final String NAMESPACE = "Microsoft.Search";

    /**
     * 2 to 60 lowercase letters, digits and dashes, no dash in the first two characters or the last one, and no
     * consecutive dashes: the {@code searchServiceName} path parameter pattern (2025-05-01 and 2026-11-01), which
     * Learn's "Name your service" rules state the same way.
     */
    static final NameRule SERVICE_NAME = new NameRule(2, 60, Pattern.compile("[a-z0-9][a-z0-9]+(?:-[a-z0-9]+)*"),
        "InvalidResourceName", "The resource name '%s' is invalid: a search service name is 2 to 60 lowercase "
            + "letters, digits or dashes, cannot use a dash as one of its first two characters or as its last one, "
            + "and cannot contain consecutive dashes.");

    /**
     * A service name is a global DNS label under {@code search.windows.net}, and the data plane finds a service by its
     * name alone, so it is one namespace across clouds. No public source gives Azure's create-conflict answer, so
     * ARM's generic {@code Conflict} stands in.
     */
    static final GlobalName SERVICE_DNS_NAME = new GlobalName(CloudSuffix.SEARCH, 409, "Conflict",
        "The search service name '%s' is already in use.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.PROVIDER_SERVICE, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "searchServices", LocationPolicy.REQUIRED)
                .withName(SERVICE_NAME)
                .withGlobalName(SERVICE_DNS_NAME))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
