package org.mappinganalysis.model.functions;

import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.graph.Triplet;
import org.mappinganalysis.model.FlinkVertex;

import java.util.Map;

/**
 * GeoCode threshold in meter.
 */
public class GeoCodeThreshold
    implements FilterFunction<Triplet<Long, FlinkVertex, Map<String, Object>>> {
  @Override
  public boolean filter(Triplet<Long, FlinkVertex, Map<String, Object>> distanceThreshold) throws Exception {
    return ((double) distanceThreshold.getEdge().getValue().get("distance")) < 50000;
  }
}
