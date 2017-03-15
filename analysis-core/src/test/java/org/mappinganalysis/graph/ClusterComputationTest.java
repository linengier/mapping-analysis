package org.mappinganalysis.graph;

import com.google.common.collect.Lists;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.graph.Edge;
import org.apache.flink.graph.Graph;
import org.apache.flink.graph.Vertex;
import org.apache.flink.types.NullValue;
import org.apache.log4j.Logger;
import org.junit.Test;
import org.mappinganalysis.graph.utils.EdgeComputationVertexCcSet;
import org.mappinganalysis.model.ObjectMap;
import org.mappinganalysis.util.Constants;
import org.mappinganalysis.util.functions.LeftMinusRightSideJoinFunction;
import org.mappinganalysis.util.functions.keyselector.CcIdKeySelector;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClusterComputationTest {
  private static final Logger LOG = Logger.getLogger(ClusterComputationTest.class);

  private static final ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

  @Test
  public void computeMissingEdgesTest() throws Exception {
    Graph<Long, NullValue, NullValue> graph = createTestGraph();
    DataSet<Vertex<Long, ObjectMap>> inputVertices = arrangeVertices(graph);
    DataSet<Edge<Long, NullValue>> allEdges =
        EdgeComputationVertexCcSet.computeComponentEdges(inputVertices, new CcIdKeySelector());

    assertEquals(9, allEdges.count());

    DataSet<Edge<Long, NullValue>> newEdges
        = restrictToNewEdges(graph.getEdges(), allEdges);
    assertEquals(1, newEdges.count());
    assertTrue(newEdges.collect().contains(new Edge<>(5681L, 5984L, NullValue.getInstance())));

    final DataSet<Edge<Long, NullValue>> distinctEdges
        = EdgeComputationVertexCcSet.getDistinctSimpleEdges(allEdges);

    assertEquals(3, distinctEdges.count());
    assertTrue(distinctEdges.collect().contains(new Edge<>(5681L, 5984L, NullValue.getInstance())));
  }

  private DataSet<Vertex<Long, ObjectMap>> arrangeVertices(Graph<Long, NullValue, NullValue> graph) {
    return graph
        .getVertices()
        .map(vertex -> {
          ObjectMap prop = new ObjectMap();
          prop.put(Constants.CC_ID, 5680L);

          return new Vertex<>(vertex.getId(), prop);
        })
        .returns(new TypeHint<Vertex<Long, ObjectMap>>() {});
  }

  private Graph<Long, NullValue, NullValue> createTestGraph() {
    List<Edge<Long, NullValue>> edgeList = Lists.newArrayList();
    edgeList.add(new Edge<>(5680L, 5681L, NullValue.getInstance()));
    edgeList.add(new Edge<>(5680L, 5984L, NullValue.getInstance()));

    return Graph.fromCollection(edgeList, env);
  }

  /**
   * Restrict given set to edges which are not in the input edges set.
   * @param input edges in this dataset should no longer be in the result set
   * @param processEdges remove edges from input edge dataset from these and return
   *
   * Used for tests.
   */
  private DataSet<Edge<Long, NullValue>> restrictToNewEdges(
      DataSet<Edge<Long, NullValue>> input,
      DataSet<Edge<Long, NullValue>> processEdges) {
    return processEdges
        .filter(edge -> edge.getSource().longValue() != edge.getTarget())
        .leftOuterJoin(input)
        .where(0, 1)
        .equalTo(0, 1)
        .with(new LeftMinusRightSideJoinFunction<>())
        .leftOuterJoin(input)
        .where(0, 1)
        .equalTo(1, 0)
        .with(new LeftMinusRightSideJoinFunction<>())
        .map(edge -> edge.getSource() < edge.getTarget() ? edge : edge.reverse())
        .returns(new TypeHint<Edge<Long, NullValue>>() {})
        .distinct();
  }
}
