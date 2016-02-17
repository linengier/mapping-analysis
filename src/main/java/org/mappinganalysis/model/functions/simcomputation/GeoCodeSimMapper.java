package org.mappinganalysis.model.functions.simcomputation;

import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.graph.Edge;
import org.apache.flink.graph.Triplet;
import org.apache.flink.types.NullValue;
import org.mappinganalysis.model.ObjectMap;
import org.mappinganalysis.utils.GeoDistance;
import org.mappinganalysis.utils.Utils;

import java.util.Map;

/**
 * Return triple including the distance between 2 geo points as edge value.
 */
public class GeoCodeSimMapper implements MapFunction<Triplet<Long, ObjectMap, NullValue>,
        Triplet<Long, ObjectMap, ObjectMap>> {
  private final double maxDistInMeter;

  public GeoCodeSimMapper(double maxDistInMeter) {
    this.maxDistInMeter = maxDistInMeter;
  }

  @Override
  public Triplet<Long, ObjectMap, ObjectMap> map(Triplet<Long,
      ObjectMap, NullValue> triplet) throws Exception {
    Map<String, Object> source = triplet.getSrcVertex().getValue();
    Map<String, Object> target = triplet.getTrgVertex().getValue();

    Double distance = GeoDistance.distance(Utils.getDouble(source.get(Utils.LAT)),
        Utils.getDouble(source.get(Utils.LON)),
        Utils.getDouble(target.get(Utils.LAT)),
        Utils.getDouble(target.get(Utils.LON)));

    ObjectMap property = new ObjectMap();

    if (distance >= maxDistInMeter) {
      property.put(Utils.SIM_DISTANCE, 0.0);
    } else {
      double result = 1.0 - (distance / maxDistInMeter);
      property.put(Utils.SIM_DISTANCE, result);
    }

    return new Triplet<>(
        triplet.getSrcVertex(),
        triplet.getTrgVertex(),
        new Edge<>(
            triplet.getSrcVertex().getId(),
            triplet.getTrgVertex().getId(),
            property));
  }
}