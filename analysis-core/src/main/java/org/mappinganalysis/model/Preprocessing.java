package org.mappinganalysis.model;

import org.apache.flink.api.common.functions.FlatJoinFunction;
import org.apache.flink.api.common.functions.GroupReduceFunction;
import org.apache.flink.api.common.functions.JoinFunction;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.tuple.Tuple1;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple4;
import org.apache.flink.graph.Edge;
import org.apache.flink.graph.Graph;
import org.apache.flink.graph.Vertex;
import org.apache.flink.types.NullValue;
import org.apache.flink.util.Collector;
import org.apache.log4j.Logger;
import org.mappinganalysis.graph.GraphUtils;
import org.mappinganalysis.io.DataLoader;
import org.mappinganalysis.io.functions.EdgeRestrictFlatJoinFunction;
import org.mappinganalysis.io.output.ExampleOutput;
import org.mappinganalysis.model.functions.preprocessing.IsolatedEdgeRemover;
import org.mappinganalysis.model.functions.preprocessing.IsolatedVertexRemover;
import org.mappinganalysis.model.functions.preprocessing.TypeMisMatchCorrection;
import org.mappinganalysis.model.functions.preprocessing.utils.*;
import org.mappinganalysis.model.functions.simcomputation.SimilarityComputation;
import org.mappinganalysis.util.AbstractionUtils;
import org.mappinganalysis.util.Constants;
import org.mappinganalysis.util.ElementCounter;
import org.mappinganalysis.util.Utils;
import org.mappinganalysis.util.functions.keyselector.CcIdKeySelector;

/**
 * Preprocessing.
 */
public class Preprocessing {
  private static final Logger LOG = Logger.getLogger(Preprocessing.class);

  /**
   * Execute all preprocessing steps with the given options
   */
  public static Graph<Long, ObjectMap, ObjectMap> execute(Graph<Long, ObjectMap, NullValue> graph,
                                                          String verbosity,
                                                          ExampleOutput out,
                                                          ExecutionEnvironment env) throws Exception {
    graph = removeEqualSourceLinks(
        graph.getEdgeIds(),
        applyTypeToInternalTypeMapping(graph),
        env);

    // stats start
    // TODO cc computation produces memory an out exception, dont use
    if (verbosity.equals(Constants.DEBUG)) {
      graph = GraphUtils.addCcIdsToGraph(graph, env); // only needed for stats
      out.addPreClusterSizes("1 cluster sizes input graph", graph.getVertices(), Constants.CC_ID);
    }
    // stats end

    /*
     * restrict graph to direct links with matching type information
     */
    TypeMisMatchCorrection correction = new TypeMisMatchCorrection
        .TypeMisMatchCorrectionBuilder()
        .setEnvironment(env)
        .build();
    graph = graph.run(correction);

    return Graph.fromDataSet(
        graph.getVertices(),
        SimilarityComputation.computeGraphEdgeSim(graph, Constants.DEFAULT_VALUE),
        env);
  }

  /**
   * Remove links where source and target dataset name are equal, remove duplicate links
   */
  public static Graph<Long, ObjectMap, NullValue> removeEqualSourceLinks(
      DataSet<Tuple2<Long, Long>> edgeIds,
      DataSet<Vertex<Long, ObjectMap>> vertices,
      ExecutionEnvironment env) {
    DataSet<Edge<Long, NullValue>> edges = getEdgeIdSourceValues(edgeIds, vertices)
        .filter(edge -> !edge.getSrcSource().equals(edge.getTrgSource()))
        .map(value -> new Edge<>(value.f0, value.f1, NullValue.getInstance()))
        .returns(new TypeHint<Edge<Long, NullValue>>() {})
        .distinct();

    return Graph.fromDataSet(vertices, edges, env);
  }

  /**
   * Create a dataset of edge ids with the associated dataset source values like "http://dbpedia.org/
   */
  public static DataSet<EdgeIdsSourcesTuple> getEdgeIdSourceValues(
      DataSet<Tuple2<Long, Long>> edgeIds,
      DataSet<Vertex<Long, ObjectMap>> vertices) {
    return edgeIds
        .map(edge -> new EdgeIdsSourcesTuple(edge.f0, edge.f1, "", ""))
        .returns(new TypeHint<EdgeIdsSourcesTuple>() {})
        .join(vertices)
        .where(0)
        .equalTo(0)
        .with((tuple, vertex) -> {
          tuple.checkSideAndUpdate(0, vertex.getValue().getOntology());
          return tuple;
        })
        .returns(new TypeHint<EdgeIdsSourcesTuple>() {})
        .join(vertices)
        .where(1)
        .equalTo(0)
        .with((tuple, vertex) -> {
          tuple.checkSideAndUpdate(1, vertex.getValue().getOntology());
          return tuple;
        })
        .returns(new TypeHint<EdgeIdsSourcesTuple>() {});
  }

  /**
   * CSV Reader
   * @throws Exception
   */
  public static void createInputGraphFromCsv(
      ExecutionEnvironment env,
      ExampleOutput out)
      throws Exception {

    DataLoader loader = new DataLoader(env);
    final String vertexFile = "concept.csv";
    final String edgeFile = "linksWithIDs.csv";
    final String propertyFile = "concept_attributes.csv";
    final String ccFile = "cc.csv";

    DataSet<Vertex<Long, ObjectMap>> vertices = loader
        .getVerticesFromCsv(Constants.INPUT_DIR + vertexFile, Constants.INPUT_DIR + propertyFile);

    if (LOG.isDebugEnabled()) {
      vertices = vertices
        .map(new ElementCounter("csv-vertex-count"));
    }

    if (Constants.INPUT_DIR.contains("linklion")) {
      if (Constants.LL_MODE.equals("nyt")
          || Constants.LL_MODE.equals("write")
          || Constants.LL_MODE.equals("print")
          || Constants.LL_MODE.equals("plan")) {
        vertices = getNytVerticesLinklion(env, ccFile, vertices, out); // only 500!!
      } else if (Constants.LL_MODE.equals("random")) {
        vertices = getRandomCcsFromLinklion(env, ccFile, vertices, 50000);
      }
    }

    DataSet<Edge<Long, NullValue>> edges = loader.getEdgesFromCsv(Constants.INPUT_DIR + edgeFile)
        .runOperation(new IsolatedEdgeRemover<>(vertices));

    vertices = vertices.runOperation(new IsolatedVertexRemover<>(edges));

    Utils.writeGraphToJSONFile(Graph.fromDataSet(vertices, edges, env),
        Constants.LL_MODE.concat("InputGraph"));
    env.execute("Read input graph from csv");
  }

  /**
   * Restrict LinkLion dataset by taking random CCs
   */
  private static DataSet<Vertex<Long, ObjectMap>> getRandomCcsFromLinklion(
      ExecutionEnvironment env,
      String ccFile,
      DataSet<Vertex<Long, ObjectMap>> vertices,
      int componentNumber) {
    DataSet<Tuple2<Long, Long>> vertexIdAndCcs = getBaseVertexCcs(env, ccFile);

    // todo why not 10k components?

    DataSet<Tuple1<Long>> relevantCcs = vertexIdAndCcs.<Tuple1<Long>>project(1).first(componentNumber);
    vertices = restrictVerticesToGivenCcs(vertices, vertexIdAndCcs, relevantCcs);

    return vertices;
  }

  private static DataSet<Vertex<Long, ObjectMap>> restrictVerticesToGivenCcs(
      DataSet<Vertex<Long, ObjectMap>> vertices,
      DataSet<Tuple2<Long, Long>> vertexCcs,
      DataSet<Tuple1<Long>> relevantCcs) {
    vertices = relevantCcs.join(vertexCcs)
        .where(0)
        .equalTo(1)
        .with((left, right) -> new Tuple1<>(right.f0))
        .returns(new TypeHint<Tuple1<Long>>() {})
        .join(vertices)
        .where(0)
        .equalTo(0)
        .with((id, vertex) -> vertex)
        .returns(new TypeHint<Vertex<Long, ObjectMap>>() {})
        .distinct(0);

    return vertices;
  }

  private static DataSet<Tuple2<Long, Long>> getBaseVertexCcs(ExecutionEnvironment env, String ccFile) {
    return env.readCsvFile(Constants.INPUT_DIR + ccFile)
          .fieldDelimiter(";")
          .ignoreInvalidLines()
          .types(Integer.class, Integer.class)
          .map(value -> new Tuple2<>((long) value.f0, (long) value.f1))
          .returns(new TypeHint<Tuple2<Long, Long>>() {});
  }

  /**
   * Restrict LinkLion dataset by (currently) taking all vertices from CCs
   * where nyt entities are contained (~6000 entities).
   *
   * Second option: take random ccs, WIP
   *
   * TODO test lambdas
   */
  private static DataSet<Vertex<Long, ObjectMap>> getNytVerticesLinklion(
      ExecutionEnvironment env,
      String ccFile,
      DataSet<Vertex<Long, ObjectMap>> vertices,
      ExampleOutput out) throws Exception {
    DataSet<Vertex<Long, ObjectMap>> nytVertices = vertices.filter(vertex ->
        vertex.getValue().getOntology().equals(Constants.NYT_NS));
//    out.addDataSetCount("nyt vertices", nytVertices);

    DataSet<Tuple2<Long, Long>> vertexCcs = getBaseVertexCcs(env, ccFile);

    DataSet<Tuple1<Long>> relevantCcs = vertexCcs.rightOuterJoin(nytVertices)
        .where(0)
        .equalTo(0)
        .with(new FlatJoinFunction<Tuple2<Long, Long>, Vertex<Long, ObjectMap>, Tuple1<Long>>() {
          @Override
          public void join(Tuple2<Long, Long> first, Vertex<Long, ObjectMap> second,
                           Collector<Tuple1<Long>> out) throws Exception {
            out.collect(new Tuple1<>(first.f1));
          }
        });


    DataSet<ComponentSourceTuple> componentSourceTuples = getComponentSourceTuples(vertices, vertexCcs);

    DataSet<Tuple1<Long>> additionalCcs = vertexCcs.leftOuterJoin(relevantCcs)
        .where(1)
        .equalTo(0)
        .with(new FlatJoinFunction<Tuple2<Long, Long>, Tuple1<Long>, Tuple2<Long, Long>>() {
          @Override
          public void join(Tuple2<Long, Long> left,
                           Tuple1<Long> right,
                           Collector<Tuple2<Long, Long>> out) throws Exception {
            if (right == null) {
              out.collect(left);
            }
          }
        })
        .<Tuple1<Long>>project(1)
        .distinct();

    /**
     * now based on sources count in component, we want at least 3 different sources
     */
    additionalCcs = additionalCcs
        .join(componentSourceTuples)
        .where(0)
        .equalTo(0)
        .with((Tuple1<Long> tuple, ComponentSourceTuple compTuple, Collector<Tuple1<Long>> collector) -> {
          if (AbstractionUtils.getSourceCount(compTuple) >= 3) {
            collector.collect(tuple);
          }
        })
        .returns(new TypeHint<Tuple1<Long>>() {})
//        .with(new FlatJoinFunction<Tuple1<Long>, ComponentSourceTuple, Tuple1<Long>>() {
//          @Override
//          public void join(Tuple1<Long> left,
//                           ComponentSourceTuple right,
//                           Collector<Tuple1<Long>> out) throws Exception {
//            if (right != null && AbstractionUtils.getSourceCount(right) >= 3) {
//              out.collect(left);
//            }
//          }
//        })
        .first(500);

    return restrictVerticesToGivenCcs(vertices, vertexCcs, relevantCcs.union(additionalCcs));
  }

  public static DataSet<ComponentSourceTuple> getComponentSourceTuples(
      DataSet<Vertex<Long, ObjectMap>> vertices,
      DataSet<Tuple2<Long, Long>> ccs) {

    if (ccs != null) {
      vertices = vertices.leftOuterJoin(ccs)
          .where(0)
          .equalTo(0)
          .with(new FlatJoinFunction<Vertex<Long, ObjectMap>,
              Tuple2<Long, Long>,
              Vertex<Long, ObjectMap>>() {
            @Override
            public void join(Vertex<Long, ObjectMap> left, Tuple2<Long, Long> right, Collector<Vertex<Long, ObjectMap>> out) throws Exception {
              left.getValue().addProperty(Constants.CC_ID, right.f1);
              out.collect(left);
            }
          });
    }

    return vertices.groupBy(new CcIdKeySelector())
        .reduceGroup(new GroupReduceFunction<Vertex<Long,ObjectMap>, ComponentSourceTuple>() {
          @Override
          public void reduce(Iterable<Vertex<Long, ObjectMap>> vertices, Collector<ComponentSourceTuple> out) throws Exception {
            ComponentSourceTuple result = new ComponentSourceTuple();
            boolean isFirst = true;
            for (Vertex<Long, ObjectMap> vertex : vertices) {
              if (isFirst) {
                result.setCcId(vertex.getValue().getCcId());
                isFirst = false;
              }
              result.addSource(vertex.getValue().getOntology());
            }
            out.collect(result);
          }
        });
  }

  /**
   * Harmonize available type information with a common dictionary.
   * @param graph input graph
   * @return graph with additional internal type property
   */
  public static DataSet<Vertex<Long, ObjectMap>> applyTypeToInternalTypeMapping(
      Graph<Long, ObjectMap, NullValue> graph) {
    return graph.getVertices()
        .map(new InternalTypeMapFunction());
  }
}
