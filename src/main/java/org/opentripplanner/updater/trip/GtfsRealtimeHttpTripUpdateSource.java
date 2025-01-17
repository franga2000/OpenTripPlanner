package org.opentripplanner.updater.trip;

import com.google.protobuf.ExtensionRegistry;
import com.google.transit.realtime.GtfsRealtime;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import de.mfdz.MfdzRealtimeExtensions;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.opentripplanner.framework.collection.MapUtils;
import org.opentripplanner.framework.io.HttpUtils;
import org.opentripplanner.framework.tostring.ToStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GtfsRealtimeHttpTripUpdateSource implements TripUpdateSource {

  private static final Logger LOG = LoggerFactory.getLogger(GtfsRealtimeHttpTripUpdateSource.class);
  public static final Map<String, String> DEFAULT_HEADERS = Map.of(
    "Accept",
    "application/x-google-protobuf, application/x-protobuf, application/protobuf, application/octet-stream, */*"
  );
  /**
   * Feed id that is used to match trip ids in the TripUpdates
   */
  private final String feedId;
  private final String url;
  private final Map<String, String> headers;
  /**
   * True iff the last list with updates represent all updates that are active right now, i.e. all
   * previous updates should be disregarded
   */
  private boolean fullDataset = true;
  private ExtensionRegistry registry = ExtensionRegistry.newInstance();

  public GtfsRealtimeHttpTripUpdateSource(Parameters config) {
    this.feedId = config.getFeedId();
    this.url = config.getUrl();
    this.headers = MapUtils.combine(config.headers(), DEFAULT_HEADERS);
    MfdzRealtimeExtensions.registerAllExtensions(registry);
  }

  @Override
  public List<TripUpdate> getUpdates() {
    FeedMessage feedMessage;
    List<FeedEntity> feedEntityList;
    List<TripUpdate> updates = null;
    fullDataset = true;
    try {
      InputStream is = HttpUtils.openInputStream(URI.create(url), this.headers);
      if (is != null) {
        // Decode message
        feedMessage = FeedMessage.parseFrom(is, registry);
        feedEntityList = feedMessage.getEntityList();

        // Change fullDataset value if this is an incremental update
        if (
          feedMessage.hasHeader() &&
          feedMessage.getHeader().hasIncrementality() &&
          feedMessage
            .getHeader()
            .getIncrementality()
            .equals(GtfsRealtime.FeedHeader.Incrementality.DIFFERENTIAL)
        ) {
          fullDataset = false;
        }

        // Create List of TripUpdates
        updates = new ArrayList<>(feedEntityList.size());
        for (FeedEntity feedEntity : feedEntityList) {
          if (feedEntity.hasTripUpdate()) updates.add(feedEntity.getTripUpdate());
        }
      } else {
        LOG.error("GTFS-RT feed at {} did not return usable data", url);
      }
    } catch (Exception e) {
      LOG.error("Failed to parse GTFS-RT feed from {}", url, e);
    }
    return updates;
  }

  @Override
  public boolean getFullDatasetValueOfLastUpdates() {
    return fullDataset;
  }

  @Override
  public String getFeedId() {
    return this.feedId;
  }

  public String toString() {
    return ToStringBuilder
      .of(this.getClass())
      .addStr("feedId", feedId)
      .addStr("url", url)
      .toString();
  }

  interface Parameters {
    String getFeedId();

    String getUrl();

    Map<String, String> headers();
  }
}
