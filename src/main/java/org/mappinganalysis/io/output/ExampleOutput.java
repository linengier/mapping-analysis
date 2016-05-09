package org.mappinganalysis.io.output;

import org.apache.commons.lang3.StringUtils;
import org.apache.flink.api.common.functions.*;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.tuple.Tuple1;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.graph.Edge;
import org.apache.flink.graph.Graph;
import org.apache.flink.graph.Vertex;
import org.apache.flink.hadoop.shaded.com.google.common.collect.Lists;
import org.apache.flink.types.NullValue;
import org.apache.flink.util.Collector;
import org.mappinganalysis.model.ObjectMap;
import org.mappinganalysis.utils.Utils;
import org.mappinganalysis.utils.functions.keyselector.CcIdKeySelector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ExampleOutput {
  /**
   * Flink dataset, collecting the output lines
   */
  private DataSet<ArrayList<String>> outSet;
  private ExecutionEnvironment env;
  private DataSet<String> strings = null;

  public ExampleOutput(ExecutionEnvironment env) {
    this.outSet = env.fromElements(new ArrayList<String>());
    this.env = env;
  }

  /**
   * addGraph collection to output
   * @param caption output caption
   */
  public <T> void addGraph(String caption, Graph<Long, ObjectMap, T> graph) {
    DataSet<String> captionSet = env
            .fromElements("\n*** " + caption + " ***\n");
    DataSet<String> graphStringSet =
        new CanonicalAdjacencyMatrixBuilder()
            .execute(graph);

    outSet = outSet
        .cross(captionSet)
        .with(new OutputAppender())
        .cross(graphStringSet)
        .with(new OutputAppender());
  }

  public void addSelectedVertices(String caption, DataSet<Vertex<Long, ObjectMap>> vertices,
                                  ArrayList<Long> vertexList) {
    DataSet<Tuple1<Long>> vertexIds = env.fromCollection(vertexList)
        .map(new MapFunction<Long, Tuple1<Long>>() {
          @Override
          public Tuple1<Long> map(Long value) throws Exception {
            return new Tuple1<>(value);
          }
        });
    DataSet<String> captionSet = env
        .fromElements("\n*** " + caption + " ***\n");
    DataSet<String> vertexSet = vertices
        .rightOuterJoin(vertexIds)
        .where(0)
        .equalTo(0)
        .with(new FinalVertexOutputJoinFunction())
        .reduceGroup(new ConcatStrings());

    outSet = outSet
        .cross(captionSet)
        .with(new OutputAppender())
        .cross(vertexSet)
        .with(new OutputAppender());
  }

  public void addEdges(String caption, Graph<Long, ObjectMap, ObjectMap> graph, ArrayList<Long> vertexList) {

    DataSet<Long> vertexIds = env.fromCollection(vertexList);

    DataSet<String> captionSet = env
        .fromElements("\n*** " + caption + " ***\n");

    DataSet<Edge<Long, ObjectMap>> edges = graph.getEdges()
        .filter(new RichFilterFunction<Edge<Long, ObjectMap>>() {
      public List<Long> vertexIds;

      @Override
      public void open(Configuration parameters) {
        this.vertexIds = getRuntimeContext().getBroadcastVariable("vertexIds");
      }

      @Override
      public boolean filter(Edge<Long, ObjectMap> edge) throws Exception {
        if (vertexIds.contains(edge.getSource()) || vertexIds.contains(edge.getTarget())) {
          return true;
        } else {
          return false;
        }
      }
    }).withBroadcastSet(vertexIds, "vertexIds");


    DataSet<String> edgeSet = edges
        .map(new MapFunction<Edge<Long, ObjectMap>, String>() {
          @Override
          public String map(Edge<Long, ObjectMap> edge) throws Exception {
            String simValue = "";
            if (edge.getValue().containsKey(Utils.AGGREGATED_SIM_VALUE)) {
              simValue = " " + edge.getValue().get(Utils.AGGREGATED_SIM_VALUE).toString();
            }
            return "(" + edge.getSource() + ", " + edge.getTarget() + ")" + simValue;
          }
        })
        .reduceGroup(new ConcatStrings());

    outSet = outSet
        .cross(captionSet)
        .with(new OutputAppender())
        .cross(edgeSet)
        .with(new OutputAppender());
  }

  /**
   * For each chosen basic component id, show the changed final vertices
   * @param caption output caption
   * @param baseVertices random base vertices
   * @param finalVertices final vertices with properties
   * @param amount amount of clusters
   */
  public void addRandomBaseClusters(String caption, DataSet<Vertex<Long, ObjectMap>> baseVertices,
                                    DataSet<Vertex<Long, ObjectMap>> finalVertices, int amount) {
    DataSet<Tuple1<Long>> randomCcIds = baseVertices.map(new MapFunction<Vertex<Long, ObjectMap>, Tuple1<Long>>() {
      @Override
      public Tuple1<Long> map(Vertex<Long, ObjectMap> vertex) throws Exception {
        return new Tuple1<>((long) vertex.getValue().get(Utils.CC_ID));
      }
    }).distinct().first(amount);

    addFinalVertexValues(caption, randomCcIds, finalVertices, baseVertices);
  }

  /**
   * For each chosen final cluster, provide original information for the contained vertices
   * @param caption output caption
   * @param vertices cluster vertices
   * @param basicVertices basic vertices are used to find the original vertex properties
   * @param minClusterSize minimum size of clusters
   * @param amount amount of clusters
   */
  public void addRandomFinalClustersWithMinSize(String caption, DataSet<Vertex<Long, ObjectMap>> vertices,
                                                DataSet<Vertex<Long, ObjectMap>> basicVertices,
                                                int minClusterSize, int amount) {

    DataSet<String> captionSet = env.fromElements("\n*** " + caption + " ***\n");
    DataSet<Vertex<Long, ObjectMap>> randomClusters = vertices
        .filter(new MinClusterSizeFilterFunction(minClusterSize))
        .first(amount);

    DataSet<String> vertexSet = new CanonicalAdjacencyMatrixBuilder()
        .executeOnRandomFinalClusterBaseVertexValues(randomClusters, basicVertices);

    outSet = outSet
        .cross(captionSet)
        .with(new OutputAppender())
        .cross(vertexSet)
        .with(new OutputAppender());
  }

  public void addSelectedBaseClusters(String caption, DataSet<Vertex<Long, ObjectMap>> baseVertices,
                                                  DataSet<Vertex<Long, ObjectMap>> finalVertices,
                                                  ArrayList<Long> vertexList) {
    DataSet<Tuple1<Long>> vertexListTuple = env.fromCollection(vertexList)
        .map(new MapFunction<Long, Tuple1<Long>>() {
          @Override
          public Tuple1<Long> map(Long value) throws Exception {
            return new Tuple1<>(value);
          }
        });

    addFinalVertexValues(caption, vertexListTuple, finalVertices, baseVertices);
  }

  private void addFinalVertexValues(String caption, DataSet<Tuple1<Long>> vertexListTuple,
                                    DataSet<Vertex<Long, ObjectMap>> finalVertices,
                                    DataSet<Vertex<Long, ObjectMap>> baseVertices) {
    DataSet<String> captionSet = env.fromElements("\n*** " + caption + " ***\n");

    DataSet<Vertex<Long, ObjectMap>> randomClusters = vertexListTuple
        .leftOuterJoin(baseVertices)
        .where(0)
        .equalTo(new CcIdKeySelector())
        .with(new ExtractSelectedVerticesFlatJoinFunction());

    DataSet<String> vertexSet = new CanonicalAdjacencyMatrixBuilder()
        .executeOnVertices2(randomClusters, finalVertices);

    outSet = outSet
        .cross(captionSet)
        .with(new OutputAppender())
        .cross(vertexSet)
        .with(new OutputAppender());
  }


  /**
   * Add the cluster sizes for the given vertex dataset.
   * @param caption caption
   * @param vertices vertices
   */
  public void addClusterSizes(String caption, DataSet<Vertex<Long, ObjectMap>> vertices) {
    if (Utils.VERBOSITY.equals(Utils.DEBUG) || Utils.VERBOSITY.equals(Utils.INFO)) {
      DataSet<String> captionSet = env
          .fromElements("\n*** " + caption + " ***\n");

      DataSet<String> vertexSet = vertices
          .map(new MapFunction<Vertex<Long, ObjectMap>, Tuple2<Long, Long>>() {
            @Override
            public Tuple2<Long, Long> map(Vertex<Long, ObjectMap> vertex) throws Exception {
              return new Tuple2<>((long) vertex.getValue().getVerticesList().size(), 1L);
            }
          })
          .groupBy(0).sum(1)
          .reduceGroup(new ConcatTuple2Longs());

      outSet = outSet
          .cross(captionSet)
          .with(new OutputAppender())
          .cross(vertexSet)
          .with(new OutputAppender());
    }
  }
  public void addVertexSizes(String caption, DataSet<Vertex<Long, ObjectMap>> vertices) {
    DataSet<String> captionSet = env
        .fromElements("\n*** " + caption + " ***\n");
    DataSet<String> vertexSet = vertices
        .map(new MapFunction<Vertex<Long, ObjectMap>, Tuple2<Long, Long>>() {
          @Override
          public Tuple2<Long, Long> map(Vertex<Long, ObjectMap> vertex) throws Exception {
            return new Tuple2<>(vertex.getId(), 1L);
          }
        })
        .sum(1)
        .first(1)
        .map(new MapFunction<Tuple2<Long, Long>, String>() {
          @Override
          public String map(Tuple2<Long, Long> tuple) throws Exception {
            return "vertices: " + tuple.f1.toString();
          }

        });

    outSet = vertexSet.map(new MapFunction<String, ArrayList<String>>() {
      ArrayList<String> result = Lists.newArrayList();
      @Override
      public ArrayList<String> map(String s) throws Exception {
        result.add(s);
        return result;
      }
    });
//    outSet = outSet
//        .cross(captionSet)
//        .with(new OutputAppender())
//        .cross(vertexSet)
//        .with(new OutputAppender());
  }

  public <T> void  addVertexAndEdgeSizes(String caption, Graph<Long, ObjectMap, T> graph) {
    if (Utils.VERBOSITY.equals(Utils.DEBUG) || Utils.VERBOSITY.equals(Utils.INFO)) {
      DataSet<String> captionSet = env
          .fromElements("\n*** " + caption + " ***\n");
      DataSet<String> vertexSet = graph.getVertices()
          .map(new MapFunction<Vertex<Long, ObjectMap>, Tuple2<Long, Long>>() {
            @Override
            public Tuple2<Long, Long> map(Vertex<Long, ObjectMap> vertex) throws Exception {
              return new Tuple2<>(vertex.getId(), 1L);
            }
          })
          .sum(1)
          .first(1)
          .map(new MapFunction<Tuple2<Long, Long>, String>() {
            @Override
            public String map(Tuple2<Long, Long> tuple) throws Exception {
              return "vertices: " + tuple.f1.toString();
            }
          });

      DataSet<String> edgeSet = graph.getEdgeIds()
          .map(new MapFunction<Tuple2<Long, Long>, Tuple1<Long>>() {
            @Override
            public Tuple1<Long> map(Tuple2<Long, Long> longLongTuple2) throws Exception {
              return new Tuple1<>(1L);
            }
          })
          .sum(0)
          .first(1)
          .map(new MapFunction<Tuple1<Long>, String>() {
            @Override
            public String map(Tuple1<Long> tuple) throws Exception {
              return "edges: " + tuple.f0.toString();
            }
          });

      outSet = outSet
          .cross(captionSet)
          .with(new OutputAppender())
          .cross(vertexSet)
          .with(new OutputAppender())
          .cross(edgeSet).with(new OutputAppender());
    }
  }

  public <T> void addTuples(String caption, DataSet<T> tuples) {
    DataSet<String> captionSet = env
        .fromElements("\n*** " + caption + " ***\n");
    DataSet<String> tupleSet =
        new CanonicalAdjacencyMatrixBuilder().executeOnTuples(tuples);

    outSet = outSet
        .cross(captionSet)
        .with(new OutputAppender())
        .cross(tupleSet)
        .with(new OutputAppender());
  }

  public void addVertices(String caption, DataSet<Vertex<Long, ObjectMap>> vertices) {
    DataSet<String> captionSet = env
        .fromElements("\n*** " + caption + " ***\n");
    DataSet<String> vertexSet =
        new CanonicalAdjacencyMatrixBuilder().executeOnVertices(vertices);

    outSet = outSet
        .cross(captionSet)
        .with(new OutputAppender())
        .cross(vertexSet)
        .with(new OutputAppender());
  }

  private static class ConcatTuple2Longs implements GroupReduceFunction<Tuple2<Long, Long>, String> {
    @Override
    public void reduce(Iterable<Tuple2<Long, Long>> tuples, Collector<String> collector) throws Exception {
        List<String> vertexSizeList = new ArrayList<>();

        for (Tuple2<Long, Long> tuple : tuples) {
          vertexSizeList.add("size: " + tuple.f0 + " count: " + tuple.f1);
        }

        Collections.sort(vertexSizeList);
        collector.collect(StringUtils.join(vertexSizeList, "\n"));
    }
  }

  private static class FinalVertexOutputJoinFunction
      implements JoinFunction<Vertex<Long, ObjectMap>, Tuple1<Long>, String> {
    @Override
    public String join(Vertex<Long, ObjectMap> vertex, Tuple1<Long> aLong) throws Exception {
      return vertex == null ? "" : vertex.toString();//Utils.toString(vertex);
    }
  }

  /**
   * Flink function to append output data set.
   */
  private class OutputAppender
      implements CrossFunction<ArrayList<String>, String, ArrayList<String>> {
    @Override
    public ArrayList<String> cross(ArrayList<String> out, String line) throws
        Exception {

      out.add(line);

      return out;
    }
  }

  /**
   * Flink function to combine output lines.
   */
  private class LineCombiner implements MapFunction<ArrayList<String>, String> {
    @Override
    public String map(ArrayList<String> lines) throws Exception {

      return StringUtils.join(lines, "\n");
    }
  }

  /**
   * print output
   * @throws Exception
   */
  public void print() throws Exception {
    outSet.map(new LineCombiner())
        .print();
  }
}