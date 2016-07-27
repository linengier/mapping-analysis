package org.mappinganalysis.model.functions.simsort;

import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.graph.Vertex;
import org.mappinganalysis.model.ObjectMap;
import org.mappinganalysis.util.Constants;

public class AggSimValueKeySelector implements KeySelector<Vertex<Long, ObjectMap>, Double> {
  @Override
  public Double getKey(Vertex<Long, ObjectMap> vertex) throws Exception {
    return vertex.getValue().getVertexSimilarity();
  }
}
