package org.mappinganalysis.model.functions.decomposition;

import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.graph.Graph;
import org.apache.flink.graph.Vertex;
import org.apache.log4j.Logger;
import org.mappinganalysis.model.ObjectMap;
import org.mappinganalysis.model.functions.decomposition.representative.MajorityPropertiesGroupReduceFunction;
import org.mappinganalysis.model.functions.decomposition.simsort.SimSort;
import org.mappinganalysis.util.Constants;
import org.mappinganalysis.util.functions.keyselector.HashCcIdKeySelector;

/**
 * Executes TypeGroupBy and SimSort method and returns the resulting graph.
 */
public class Decomposition {
  private static final Logger LOG = Logger.getLogger(Decomposition.class);

  public static Graph<Long, ObjectMap, ObjectMap> executeDecomposition(
      Graph<Long, ObjectMap, ObjectMap> graph, ExecutionEnvironment env) throws Exception {
    // typegroupby already done
    // simsort execute
    if (Constants.IS_SIMSORT_ENABLED) {
      graph = SimSort.execute(graph, 1000, env);
    } else if (Constants.IS_SIMSORT_ALT_ENABLED) {
      graph = SimSort.executeAlternative(graph, env); // not yet implemented
    }

    return graph;
  }


  /**
   * Create representatives based on hash component ids for each vertex in a graph.
   */
  public static DataSet<Vertex<Long, ObjectMap>> createRepresentatives(Graph<Long, ObjectMap, ObjectMap> graph) {
    return graph.getVertices()
        .groupBy(new HashCcIdKeySelector())
        .reduceGroup(new MajorityPropertiesGroupReduceFunction());
  }
}
