package org.mappinganalysis.graph;

import com.google.common.collect.Sets;
import org.apache.flink.api.common.accumulators.LongCounter;
import org.apache.flink.api.common.functions.*;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.graph.Edge;
import org.apache.flink.graph.Vertex;
import org.apache.flink.types.NullValue;
import org.apache.flink.util.Collector;
import org.mappinganalysis.model.ObjectMap;
import org.mappinganalysis.model.functions.CcIdKeySelector;
import org.mappinganalysis.utils.Utils;

import java.util.HashSet;

public class ClusterComputation {

  /**
   * Within a set of vertices, compute all edges for each contained component, restrict to simple edges with boolean
   * @param vertices vertices set
   * @param isResultSimpleEdgeSet specify if result set should be restricted (most likely to be true)
   * @return edge set
   */
  public static DataSet<Edge<Long, NullValue>> computeComponentEdges(
      DataSet<Vertex<Long, ObjectMap>> vertices, boolean isResultSimpleEdgeSet) {
    if (isResultSimpleEdgeSet) {
      DataSet<Edge<Long, NullValue>> edgeSet = computeComponentEdges(vertices);
      return getDistinctSimpleEdges(edgeSet);
    } else {
      return computeComponentEdges(vertices);
    }
  }

  /**
   * [deprecated] Within a set of vertices, compute all edges for each contained component.
   * @param vertices vertices set
   * @return edge set
   */
  public static DataSet<Edge<Long, NullValue>> computeComponentEdges(
      DataSet<Vertex<Long, ObjectMap>> vertices) {
    return vertices.coGroup(vertices)
        .where(new CcIdKeySelector())
        .equalTo(new CcIdKeySelector())
        .with(new EdgeExtractCoGroupFunction());
  }

  /**
   * Example: (1, 2), (2, 1), (1, 3), (1, 1) as input will result in (1, 2), (1,3)
   */
  public static DataSet<Edge<Long, NullValue>> getDistinctSimpleEdges(DataSet<Edge<Long, NullValue>> input) {
    return input
        .filter(new FilterFunction<Edge<Long, NullValue>>() {
          @Override
          public boolean filter(Edge<Long, NullValue> edge) throws Exception {
            return (long) edge.getSource() != edge.getTarget();
          }
        })
        .map(new MapFunction<Edge<Long, NullValue>, Edge<Long, NullValue>>() {
          @Override
          public Edge<Long, NullValue> map(Edge<Long, NullValue> edge) throws Exception {
            return edge.getSource() < edge.getTarget() ? edge : edge.reverse();
          }
        })
        .distinct()
        .filter(new RichFilterFunction<Edge<Long, NullValue>>() {
          private LongCounter restrictEdgeCounter = new LongCounter();

          @Override
          public void open(final Configuration parameters) throws Exception {
            super.open(parameters);
            getRuntimeContext().addAccumulator(Utils.RESTRICT_EDGE_COUNT_ACCUMULATOR, restrictEdgeCounter);
          }
          @Override
          public boolean filter(Edge<Long, NullValue> longNullValueEdge) throws Exception {
            restrictEdgeCounter.add(1L);
            return true;
          }
        });
  }

  /**
   * TODO Uses parts of getDistinctSimpleEdges, rewrite and test
   */
  public static DataSet<Edge<Long, NullValue>> restrictToNewEdges(DataSet<Edge<Long, NullValue>> input,
                                                                  DataSet<Edge<Long, NullValue>> tmpResult) {
    return tmpResult
        .filter(new FilterFunction<Edge<Long, NullValue>>() {
          @Override
          public boolean filter(Edge<Long, NullValue> edge) throws Exception {
            return (long) edge.getSource() != edge.getTarget();
          }
        })
        .leftOuterJoin(input)
        .where(0, 1).equalTo(0, 1)
        .with(new ExcludeInputJoinFunction())
        .leftOuterJoin(input)
        .where(0, 1).equalTo(1, 0)
        .with(new ExcludeInputJoinFunction())
        .map(new MapFunction<Edge<Long, NullValue>, Edge<Long, NullValue>>() {
          @Override
          public Edge<Long, NullValue> map(Edge<Long, NullValue> edge) throws Exception {
            return edge.getSource() < edge.getTarget() ? edge : edge.reverse();
          }
        })
        .distinct();
  }

  private static class ExcludeInputJoinFunction implements FlatJoinFunction<Edge<Long, NullValue>,
      Edge<Long, NullValue>, Edge<Long, NullValue>> {
    @Override
    public void join(Edge<Long, NullValue> left, Edge<Long, NullValue> right,
                     Collector<Edge<Long, NullValue>> collector) throws Exception {
      if (right == null) {
        collector.collect(left);
      }
    }
  }

  private static class EdgeExtractCoGroupFunction implements CoGroupFunction<Vertex<Long, ObjectMap>, Vertex<Long, ObjectMap>, Edge<Long, NullValue>> {
    @Override
    public void coGroup(Iterable<Vertex<Long, ObjectMap>> left,
                        Iterable<Vertex<Long, ObjectMap>> right,
                        Collector<Edge<Long, NullValue>> collector) throws Exception {
      HashSet<Vertex<Long, ObjectMap>> rightSet = Sets.newHashSet(right);
      for (Vertex<Long, ObjectMap> vertexLeft : left) {
        for (Vertex<Long, ObjectMap> vertexRight : rightSet) {
          collector.collect(new Edge<>(vertexLeft.getId(),
              vertexRight.getId(), NullValue.getInstance()));
        }
      }
    }
  }
}
