package org.mappinganalysis.model.functions.merge;

import com.google.common.collect.Sets;
import org.apache.flink.api.common.functions.RichFlatJoinFunction;
import org.apache.flink.graph.Vertex;
import org.apache.flink.util.Collector;
import org.mappinganalysis.model.MergeGeoTuple;
import org.mappinganalysis.model.ObjectMap;
import org.mappinganalysis.util.AbstractionUtils;
import org.mappinganalysis.util.Constants;

/**
 * Create final vertices from merge tuples.
 * Only active merge tuples are translated to default Gelly vertices.
 */
public class FinalMergeGeoVertexCreator
    extends RichFlatJoinFunction<MergeGeoTuple, Vertex<Long, ObjectMap>, Vertex<Long, ObjectMap>> {
  @Override
  public void join(MergeGeoTuple tuple,
                   Vertex<Long, ObjectMap> second,
                   Collector<Vertex<Long, ObjectMap>> out) throws Exception {
    if (tuple.isActive()) {
      ObjectMap map = new ObjectMap(Constants.GEO);
      map.setLabel(tuple.getLabel());

      map.setGeoProperties(tuple.getLatitude(), tuple.getLongitude());
      map.setClusterDataSources(
          AbstractionUtils.getSourcesStringSet(
              Constants.GEO,
              tuple.getIntSources()));
      map.setTypes(
          Constants.TYPE_INTERN,
          AbstractionUtils.getTypesStringSet(tuple.getIntTypes()));
      map.setClusterVertices(
          Sets.newHashSet(tuple.getClusteredElements()));

      out.collect(new Vertex<>(tuple.getId(), map));
    }
  }
}