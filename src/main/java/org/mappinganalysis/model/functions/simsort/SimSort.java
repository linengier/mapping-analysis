package org.mappinganalysis.model.functions.simsort;

import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.graph.Edge;
import org.apache.flink.graph.EdgeDirection;
import org.apache.flink.graph.Graph;
import org.apache.flink.graph.Vertex;
import org.apache.flink.graph.spargel.VertexCentricConfiguration;
import org.apache.flink.types.NullValue;
import org.apache.log4j.Logger;
import org.mappinganalysis.graph.ClusterComputation;
import org.mappinganalysis.model.ObjectMap;
import org.mappinganalysis.model.functions.clustering.EdgeExtractCoGroupFunction;
import org.mappinganalysis.model.functions.representative.VertexStatusFilter;
import org.mappinganalysis.model.functions.simcomputation.SimCompUtility;
import org.mappinganalysis.utils.Utils;

public class SimSort {
  private static final Logger LOG = Logger.getLogger(SimSort.class);

  /**
   * create all missing edges, add default vertex sim values
   * @param graph input graph
   * @param simSortKeySelector cc id or hash cc id
   * @param env execution environment
   * @return preprocessed graph
   */
  public static Graph<Long, ObjectMap, ObjectMap> prepare( Graph<Long, ObjectMap, ObjectMap> graph,
      KeySelector<Vertex<Long, ObjectMap>, Long> simSortKeySelector, ExecutionEnvironment env) {
    DataSet<Edge<Long, NullValue>> allEdges = graph.getVertices()
        .coGroup(graph.getVertices())
        .where(simSortKeySelector)
        .equalTo(simSortKeySelector)
        .with(new EdgeExtractCoGroupFunction());

    Graph<Long, ObjectMap, NullValue> distinctEdgesGraph = Graph
        .fromDataSet(graph.getVertices(), ClusterComputation.getDistinctSimpleEdges(allEdges), env);

    // TODO eliminate partly duplicate sim computation
    DataSet<Edge<Long, ObjectMap>> simEdges = SimCompUtility.computeEdgeSimFromGraph(distinctEdgesGraph);

    return Graph.fromDataSet(graph.getVertices(), simEdges, env)
        .mapVertices(new MapFunction<Vertex<Long, ObjectMap>, ObjectMap>() {
          @Override
          public ObjectMap map(Vertex<Long, ObjectMap> vertex) throws Exception {
            vertex.getValue().put(Utils.VERTEX_AGG_SIM_VALUE, Utils.DEFAULT_VERTEX_SIM);
            return vertex.getValue();
          }
        });
  }

  /**
   * Execute SimSort procedure based on vertex-centric-iteration
   * @param graph input graph
   * @param maxIterations max vertex-centric-iteration count
   * @param minimumSimilarity similarity which is needed to be in the cluster
   * @return resulting graph with new clusters
   */
  public static Graph<Long, ObjectMap, ObjectMap> execute(Graph<Long, ObjectMap, ObjectMap> graph,
                                                   Integer maxIterations, Double minimumSimilarity) {
    VertexCentricConfiguration aggParameters = new VertexCentricConfiguration();
    aggParameters.setName("TypeGroupBy");
    aggParameters.setDirection(EdgeDirection.ALL);

    return graph.runVertexCentricIteration(
        new SimSortVertexUpdateFunction(minimumSimilarity),
        new SimSortMessagingFunction(), maxIterations, aggParameters);
  }

  public static Graph<Long, ObjectMap, ObjectMap> excludeLowSimVertices(Graph<Long, ObjectMap, ObjectMap> graph,
                                                                        ExecutionEnvironment env) throws Exception {
//    DataSet<Vertex<Long, ObjectMap>> excludedVertices = graph.getVertices()
//        .filter(new VertexStatusFilter(false)); // EXCLUDE_FROM_COMPONENT_ACCUMULATOR count

    Graph<Long, ObjectMap, ObjectMap> componentGraph = graph
        .filterOnVertices(new VertexStatusFilter(true));

    // TODO check result, only graph.getVertices needed
//    return Graph.fromDataSet(componentGraph.getVertices().union(excludedVertices),
//        componentGraph.getEdges(), env);
    return Graph.fromDataSet(graph.getVertices(),
        componentGraph.getEdges(), env);
  }
}
