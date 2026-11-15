package org.opentripplanner.updater.vehicle_rental;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.opentripplanner.framework.io.HttpHeaders;
import org.opentripplanner.routing.linking.VertexLinkerTestFactory;
import org.opentripplanner.service.vehiclerental.internal.DefaultVehicleRentalRepository;
import org.opentripplanner.service.vehiclerental.model.TestVehicleRentalStationBuilder;
import org.opentripplanner.service.vehiclerental.model.VehicleRentalPlace;
import org.opentripplanner.service.vehiclerental.street.VehicleRentalPlaceVertex;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.model.StreetModelForTest;
import org.opentripplanner.street.model.StreetTraversalPermission;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.updater.DefaultStreetRealTimeUpdateContext;
import org.opentripplanner.updater.GraphUpdaterManager;
import org.opentripplanner.updater.spi.UpdaterConstructionException;
import org.opentripplanner.updater.spi.WriteDomain;
import org.opentripplanner.updater.spi.WriteToGraphCallback;
import org.opentripplanner.updater.spi.WriteToGraphCallbacks;
import org.opentripplanner.updater.vehicle_rental.datasources.VehicleRentalDataSource;
import org.opentripplanner.updater.vehicle_rental.datasources.params.RentalPickupType;
import org.opentripplanner.updater.vehicle_rental.datasources.params.VehicleRentalDataSourceParameters;
import org.opentripplanner.utils.lang.RunnableUtils;

class VehicleRentalUpdaterTest {

  public static final VehicleRentalUpdaterParameters PARAMS = new VehicleRentalUpdaterParameters(
    "A",
    Duration.ofMinutes(1),
    Duration.ZERO,
    new FakeParams()
  );
  public static final DefaultVehicleRentalRepository REPOSITORY =
    new DefaultVehicleRentalRepository();

  @Test
  void failingDataSourceCountsAsPrimed() {
    var source = new FailingDataSource();
    var updater = new VehicleRentalUpdater(PARAMS, source, null, REPOSITORY);

    assertFalse(updater.isPrimed());
    var manager = new MockManager(updater);
    manager.startUpdaters();
    assertTrue(source.hasFailed());
    manager.stop(false);
    assertTrue(updater.isPrimed());
  }

  /**
   * It's not clear why this tests fails on Windows and I don't have a test machine to find out.
   */
  @Test
  @Disabled
  void failingSetup() {
    var source = new FailingSetupDataSource();
    var updater = new VehicleRentalUpdater(PARAMS, source, null, REPOSITORY);

    assertFalse(updater.isPrimed());
    var manager = new MockManager(updater);
    manager.startUpdaters();
    assertTrue(source.hasFailed());
    manager.stop(true);
    assertTrue(updater.isPrimed());
  }

  /**
   * A car parked next to a footway must also be linked to the road further away, otherwise the
   * rented car cannot leave the footway.
   */
  @Test
  void linkCarStationToClosestDrivableStreet() throws Exception {
    var graph = new Graph();
    // Footway about 5 m and road about 50 m from the station
    street(graph, "footway", 0.00005, StreetTraversalPermission.PEDESTRIAN);
    street(graph, "road", 0.00045, StreetTraversalPermission.ALL);
    graph.index();

    var station = TestVehicleRentalStationBuilder.of()
      .withCoordinates(0, 0)
      .withVehicleTypeCar(1, 1)
      .build();
    var updater = new VehicleRentalUpdater(
      PARAMS,
      new StaticDataSource(station),
      VertexLinkerTestFactory.of(graph),
      REPOSITORY
    );
    updater.setup(runnable -> {
      runnable.run(new DefaultStreetRealTimeUpdateContext(graph));
      return CompletableFuture.completedFuture(null);
    });
    updater.runPolling();

    var vertex = graph.getVerticesOfType(VehicleRentalPlaceVertex.class).getFirst();
    assertThat(linkedStreetNames(vertex)).containsExactly("footway", "road");
  }

  private static void street(
    Graph graph,
    String name,
    double latitude,
    StreetTraversalPermission permission
  ) {
    StreetVertex from = StreetModelForTest.intersectionVertex(latitude, -0.001);
    StreetVertex to = StreetModelForTest.intersectionVertex(latitude, 0.001);
    graph.addVertex(from);
    graph.addVertex(to);
    StreetModelForTest.streetEdgeBuilder(from, to, 220, permission)
      .withName(name)
      .buildAndConnect();
  }

  private static Set<String> linkedStreetNames(VehicleRentalPlaceVertex vertex) {
    return vertex
      .getOutgoing()
      .stream()
      .flatMap(link -> link.getToVertex().getOutgoing().stream())
      .filter(StreetEdge.class::isInstance)
      .map(StreetEdge.class::cast)
      .map(StreetEdge::getDefaultName)
      .collect(Collectors.toSet());
  }

  static class MockManager extends GraphUpdaterManager {

    public MockManager(VehicleRentalUpdater updater) {
      super(
        new WriteToGraphCallbacks().with(WriteDomain.STREET, WriteToGraphCallback.noop()),
        RunnableUtils.NOOP,
        List.of(updater)
      );
    }
  }

  private static class FailingDataSource implements VehicleRentalDataSource {

    private final CompletableFuture<Boolean> hasFailed = new CompletableFuture<>();

    @Override
    public boolean update() {
      hasFailed.complete(true);
      throw new UpdaterConstructionException("An error occurred while setting up the source.");
    }

    @Override
    public List<VehicleRentalPlace> getUpdates() {
      return List.of();
    }

    private boolean hasFailed() {
      try {
        return hasFailed.get(5, TimeUnit.SECONDS);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static class FailingSetupDataSource implements VehicleRentalDataSource {

    private final CompletableFuture<Boolean> hasFailed = new CompletableFuture<>();

    @Override
    public void setup() {
      this.hasFailed.complete(true);
      throw new UpdaterConstructionException("An error occurred while setting up the source.");
    }

    @Override
    public boolean update() {
      return true;
    }

    @Override
    public List<VehicleRentalPlace> getUpdates() {
      return List.of();
    }

    private boolean hasFailed() {
      try {
        return hasFailed.get(5, TimeUnit.SECONDS);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private record StaticDataSource(VehicleRentalPlace place) implements VehicleRentalDataSource {
    @Override
    public boolean update() {
      return true;
    }

    @Override
    public List<VehicleRentalPlace> getUpdates() {
      return List.of(place);
    }
  }

  private static class FakeParams implements VehicleRentalDataSourceParameters {

    @Override
    public String url() {
      return "https://example.com";
    }

    @Nullable
    @Override
    public String network() {
      return "Test";
    }

    @Override
    public VehicleRentalSourceType sourceType() {
      return VehicleRentalSourceType.GBFS;
    }

    @Override
    public HttpHeaders httpHeaders() {
      return HttpHeaders.empty();
    }

    @Override
    public boolean allowRentalType(RentalPickupType rentalPickupType) {
      return true;
    }
  }
}
