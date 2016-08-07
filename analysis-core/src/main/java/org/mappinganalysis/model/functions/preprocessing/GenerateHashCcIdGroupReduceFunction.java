package org.mappinganalysis.model.functions.preprocessing;

import com.google.common.collect.ImmutableSortedSet;
import org.apache.flink.api.common.functions.GroupReduceFunction;
import org.apache.flink.graph.Vertex;
import org.apache.flink.util.Collector;
import org.mappinganalysis.model.ObjectMap;
import org.mappinganalysis.util.Constants;
import org.mappinganalysis.util.Utils;

import java.util.Set;

/**
 * Generate new component ids based on type affiliation and current component id.
 * If no type is found, the temporary component id will generated by using the vertex id.
 */
@Deprecated
public class GenerateHashCcIdGroupReduceFunction implements GroupReduceFunction<Vertex<Long, ObjectMap>, Vertex<Long, ObjectMap>> {

  @Override
  public void reduce(Iterable<Vertex<Long, ObjectMap>> vertices,
                     Collector<Vertex<Long, ObjectMap>> collector) throws Exception {
    Long hash = null;
    for (Vertex<Long, ObjectMap> vertex : vertices) {
      if (vertex.getValue().hasTypeNoType(Constants.COMP_TYPE)) {
        vertex.getValue().put(Constants.HASH_CC, Utils.getHash(vertex.getId().toString()));
      } else {
        if (hash == null) {
          Set<String> types = vertex.getValue().getTypes(Constants.COMP_TYPE);
          ImmutableSortedSet<String> typeSet = ImmutableSortedSet.copyOf(types);

          hash = Utils.getHash(typeSet.toString()
              .concat(vertex.getValue().get(Constants.CC_ID).toString()));
        }
        vertex.getValue().put(Constants.HASH_CC, hash);
      }
      collector.collect(vertex);
    }
  }
}
