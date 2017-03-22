package org.mappinganalysis.model;

import org.apache.flink.api.common.functions.FlatJoinFunction;
import org.apache.flink.api.common.functions.GroupReduceFunction;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.tuple.Tuple1;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.graph.Edge;
import org.apache.flink.graph.Graph;
import org.apache.flink.graph.Vertex;
import org.apache.flink.types.NullValue;
import org.apache.flink.util.Collector;
import org.apache.log4j.Logger;
import org.mappinganalysis.graph.utils.ConnectedComponentIdAdder;
import org.mappinganalysis.io.DataLoader;
import org.mappinganalysis.io.output.ExampleOutput;
import org.mappinganalysis.model.functions.preprocessing.EqualDataSourceLinkRemover;
import org.mappinganalysis.model.functions.preprocessing.IsolatedEdgeRemover;
import org.mappinganalysis.model.functions.preprocessing.IsolatedVertexRemover;
import org.mappinganalysis.model.functions.preprocessing.TypeMisMatchCorrection;
import org.mappinganalysis.model.functions.preprocessing.utils.ComponentSourceTuple;
import org.mappinganalysis.model.functions.preprocessing.utils.InternalTypeMapFunction;
import org.mappinganalysis.model.functions.simcomputation.BasicEdgeSimilarityComputation;
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
  @Deprecated
  public static Graph<Long, ObjectMap, ObjectMap> execute(Graph<Long, ObjectMap, NullValue> graph,
                                                          String verbosity,
                                                          ExampleOutput out,
                                                          ExecutionEnvironment env) throws Exception {
    graph = graph.mapVertices(new InternalTypeMapFunction())
        .run(new EqualDataSourceLinkRemover(env));

    // todo stats still needed?
    // stats start
    // TODO cc computation produces memory an out exception, dont use
    if (verbosity.equals(Constants.DEBUG)) {
      graph = graph.run(new ConnectedComponentIdAdder<>(env)); // only needed for stats
      out.addPreClusterSizes("1 cluster sizes input graph", graph.getVertices(), Constants.CC_ID);
    }
    // stats end

    return graph
        .run(new TypeMisMatchCorrection(env))
        .run(new BasicEdgeSimilarityComputation(Constants.DEFAULT_VALUE, env));
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

    DataSet<Edge<Long, NullValue>> edges = loader
        .getEdgesFromCsv(Constants.INPUT_DIR + edgeFile)
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

  /**
   * Restrict a set of vertices to a subset having the relevant cc ids.
   * @param vertices input vertices
   * @param vertexCcs vertex / cc id assignment
   * @param relevantCcs cc ids for result vertex subset
   * @return resulting vertices
   */
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

  private static DataSet<Tuple2<Long, Long>> getBaseVertexCcs(
      ExecutionEnvironment env,
      String ccFile) {
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

  /**
   * helper method nyt things
   * @param vertices
   * @param ccs
   * @return
   */
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

    return vertices
        .groupBy(new CcIdKeySelector())
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
}
