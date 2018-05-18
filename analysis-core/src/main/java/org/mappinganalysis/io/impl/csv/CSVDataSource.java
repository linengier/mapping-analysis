package org.mappinganalysis.io.impl.csv;import org.apache.flink.api.common.functions.FilterFunction;import org.apache.flink.api.common.functions.FlatJoinFunction;import org.apache.flink.api.common.functions.GroupReduceFunction;import org.apache.flink.api.common.functions.MapFunction;import org.apache.flink.api.common.typeinfo.TypeHint;import org.apache.flink.api.java.DataSet;import org.apache.flink.api.java.ExecutionEnvironment;import org.apache.flink.api.java.tuple.Tuple1;import org.apache.flink.api.java.tuple.Tuple2;import org.apache.flink.api.java.tuple.Tuple3;import org.apache.flink.api.java.tuple.Tuple4;import org.apache.flink.graph.Edge;import org.apache.flink.graph.Graph;import org.apache.flink.graph.Vertex;import org.apache.flink.types.NullValue;import org.apache.flink.util.Collector;import org.apache.log4j.Logger;import org.mappinganalysis.io.functions.BasicVertexCreator;import org.mappinganalysis.io.functions.FlinkEdgeCreator;import org.mappinganalysis.io.functions.FlinkPropertyMapper;import org.mappinganalysis.io.functions.PropertyCoGroupFunction;import org.mappinganalysis.io.impl.json.JSONDataSink;import org.mappinganalysis.io.impl.json.JSONToEdgeFormatter;import org.mappinganalysis.model.FlinkProperty;import org.mappinganalysis.model.ObjectMap;import org.mappinganalysis.model.api.DataSource;import org.mappinganalysis.model.functions.preprocessing.IsolatedEdgeRemover;import org.mappinganalysis.model.functions.preprocessing.IsolatedVertexRemover;import org.mappinganalysis.model.functions.preprocessing.utils.ComponentSourceTuple;import org.mappinganalysis.util.AbstractionUtils;import org.mappinganalysis.util.Constants;import org.mappinganalysis.util.ElementCounter;import org.mappinganalysis.util.functions.keyselector.CcIdKeySelector;/** * CSV Data Loader for Flink * * restriction: hardcoded for Musicbrainz dataet */public class CSVDataSource implements DataSource {  private static final Logger LOG = Logger.getLogger(CSVDataSource.class);  private String vertexPath;  private String edgePath;  private ExecutionEnvironment env;  /**   * Most general constructor, only used internal.   * @param path file path   * @param verticesFile vertices file   * @param edgesFile edges file   * @param env env   */  private CSVDataSource(String path,                        String verticesFile,                        String edgesFile,                        ExecutionEnvironment env) {    if (!path.endsWith(Constants.SLASH)) {      path = path.concat(Constants.SLASH);    }    this.vertexPath = path.concat(Constants.INPUT).concat(verticesFile);    if (edgesFile != null) {      this.edgePath = path.concat(Constants.INPUT).concat(edgesFile);    }    this.env = env;  }  public CSVDataSource(String path, String vertexPath, ExecutionEnvironment env) {    this(path, vertexPath, null, env);  }  /**   * Constructor for mappinganalysis compatibility   */  private CSVDataSource(ExecutionEnvironment env) {    this.env = env;  }  /**   * Get a graph without edges.   */  @Override  public Graph<Long, ObjectMap, NullValue> getGraph() throws Exception {    String fakePath = CSVDataSource.class        .getResource("/data/incremental/").getFile();    DataSet<Edge<Long, NullValue>> edges = env.readTextFile(fakePath)        .map(new JSONToEdgeFormatter<>(NullValue.class));    return Graph.fromDataSet(this.getVertices(), edges, env);  }  /**   * Specific implementation for musicbrainz   */  @Override  public DataSet<Vertex<Long, ObjectMap>> getVertices() throws Exception {    System.out.println("CSVDataSource: " + vertexPath);    return env.readCsvFile(vertexPath)        .fieldDelimiter(",")        .parseQuotedStrings('"')        .includeFields("110101111111")        .ignoreFirstLine()        .types(Long.class, // TID            Long.class, // CID//            Long.class, // CTID            Long.class, // SourceID, 1 to 5//            String.class, // id - strange mix numbers and letters            String.class, // number - song number? sometimes letters involved            String.class, // title            String.class, // length, e.g., 4min 32sec, 432, 432000, 4.58            String.class, // artist            String.class, // album            String.class, // year, e.g., 2009, '09            String.class) // language        .map(new MusicCSVToVertexFormatter());  }  /**   * GEO CSV Reader   */  // TODO rewrite to new datasource format  public static void createInputGraphFromCsv(      ExecutionEnvironment env)      throws Exception {    CSVDataSource source = new CSVDataSource(env);    final String vertexFile = "concept.csv";    final String edgeFile = "linksWithIDs.csv";    final String propertyFile = "concept_attributes.csv";    final String ccFile = "cc.csv";    DataSet<Vertex<Long, ObjectMap>> vertices = source        .getVerticesFromCsv(Constants.INPUT_PATH + vertexFile, Constants.INPUT_PATH + propertyFile);    if (LOG.isDebugEnabled()) {      vertices = vertices          .map(new ElementCounter("csv-vertex-count"));    }    vertices = restrictVerticesForSpecificDatasource(env, ccFile, vertices);    DataSet<Edge<Long, NullValue>> edges = source        .getEdgesFromCsv(Constants.INPUT_PATH + edgeFile)        .runOperation(new IsolatedEdgeRemover<>(vertices));    vertices = vertices.runOperation(new IsolatedVertexRemover<>(edges));    new JSONDataSink(Constants.INPUT_PATH, Constants.LL_MODE.concat("InputGraph"))        .writeGraph(Graph.fromDataSet(vertices, edges, env));    env.execute("Read input graph from csv");  }  /**   * Get vertices from csv file. Vertices are restricted to 5 possible geo sources: dbp, gn, lgd, nyt, fb   * @param vertexFile local file or hdfs   * @param propertyFile local file or hdfs   * @return vertex dataset   */  private DataSet<Vertex<Long, ObjectMap>> getVerticesFromCsv(      final String vertexFile,      String propertyFile) throws Exception {    DataSet<Tuple3<Integer, String, String>> vertices = env.readCsvFile(vertexFile)        .fieldDelimiter(";")        .ignoreInvalidLines()        .types(Integer.class, String.class, String.class);    return vertices        .filter(new BigGeoSourceFilterFunction())        .map(new BasicVertexCreator())        .coGroup(getPropertiesFromCsv(propertyFile))        .where(0)        .equalTo(0)        .with(new PropertyCoGroupFunction());  }  /**   * Read vertex properties from file, each line is parsed, restrictions (some properties are not needed)   * are not included.   * @param propertiesFile local file or hdfs   * @return vertex properties   */  private DataSet<FlinkProperty> getPropertiesFromCsv(String propertiesFile)      throws Exception {    DataSet<Tuple4<Integer, String, String, String>> properties = env.readCsvFile(propertiesFile)        .fieldDelimiter(";")        .ignoreInvalidLines()        .types(Integer.class, String.class, String.class, String.class);    properties = properties        .map(new MapFunction<Tuple4<Integer,String,String,String>, Tuple4<Integer, String, String, String>>() {      @Override      public Tuple4<Integer, String, String, String> map(Tuple4<Integer, String, String, String> value) throws Exception {//        if (value.f0 == 5382 || value.f0 == 4216 || value.f0 == 5421) {//          LOG.info("### INPUTPROP: " + value.toString());//          // 2016-11-23 11:41:09,814 INFO  org.mappinganalysis.io.impl.csv.CSVDataSource - ### INPUTPROP: (4216,label,Al Ma???m??d??yah,string)//          // 2016-11-23 11:41:09,677 INFO  org.mappinganalysis.io.impl.csv.CSVDataSource - ### INPUTPROP: (5421,label,D??sseldorf,string)//        }          return value;        }    });    return properties        .map(new FlinkPropertyMapper())        .withForwardedFields("f1;f2;f3");  }  /**   * Read edges from link file. Simply source id and target id are used to instantiate starting edge.   * @param edgeFile local file or hdfs   * @return basic edges   */  private DataSet<Edge<Long, NullValue>> getEdgesFromCsv(String edgeFile) {    return env.readCsvFile(edgeFile)        .fieldDelimiter(";")        .ignoreInvalidLines()        .types(Integer.class, Integer.class)        .map(new FlinkEdgeCreator());  }  private static class BigGeoSourceFilterFunction      implements FilterFunction<Tuple3<Integer, String, String>> {    @Override    public boolean filter(Tuple3<Integer, String, String> inputTuple) throws Exception {      return inputTuple.f2.equals(Constants.DBP_NS)          || inputTuple.f2.equals(Constants.GN_NS)          || inputTuple.f2.equals(Constants.LGD_NS)          || inputTuple.f2.equals(Constants.NYT_NS)          || inputTuple.f2.equals(Constants.FB_NS);    }  }  private static DataSet<Vertex<Long, ObjectMap>> restrictVerticesForSpecificDatasource(      ExecutionEnvironment env, String ccFile,      DataSet<Vertex<Long, ObjectMap>> vertices) throws Exception {    if (Constants.INPUT_PATH.contains("linklion")) {      if (Constants.LL_MODE.equals("nyt")          || Constants.LL_MODE.equals("write")          || Constants.LL_MODE.equals("print")          || Constants.LL_MODE.equals("plan")) {        vertices = getNytVerticesLinklion(env, ccFile, vertices); // only 500!!      } else if (Constants.LL_MODE.equals("random")) {        vertices = getRandomCcsFromLinklion(env, ccFile, vertices, 50000);      }    }    return vertices;  }  /**   * Restrict LinkLion dataset by taking random CCs   */  private static DataSet<Vertex<Long, ObjectMap>> getRandomCcsFromLinklion(      ExecutionEnvironment env,      String ccFile,      DataSet<Vertex<Long, ObjectMap>> vertices,      int componentNumber) {    DataSet<Tuple2<Long, Long>> vertexIdAndCcs = getBaseVertexCcs(env, ccFile);    // todo why not 10k components?    DataSet<Tuple1<Long>> relevantCcs = vertexIdAndCcs.<Tuple1<Long>>project(1).first(componentNumber);    vertices = restrictVerticesToGivenCcs(vertices, vertexIdAndCcs, relevantCcs);    return vertices;  }  /**   * Restrict LinkLion dataset by (currently) taking all vertices from CCs   * where nyt entities are contained (~6000 entities).   *   * Second option: take random ccs, WIP   */  private static DataSet<Vertex<Long, ObjectMap>> getNytVerticesLinklion(      ExecutionEnvironment env,      String ccFile,      DataSet<Vertex<Long, ObjectMap>> vertices) throws Exception {    DataSet<Vertex<Long, ObjectMap>> nytVertices = vertices.filter(vertex ->        vertex.getValue().getDataSource().equals(Constants.NYT_NS));//    out.addDataSetCount("nyt vertices", nytVertices);    DataSet<Tuple2<Long, Long>> vertexCcs = getBaseVertexCcs(env, ccFile);    DataSet<Tuple1<Long>> relevantCcs = vertexCcs.rightOuterJoin(nytVertices)        .where(0)        .equalTo(0)        .with(new FlatJoinFunction<Tuple2<Long, Long>, Vertex<Long, ObjectMap>, Tuple1<Long>>() {          @Override          public void join(Tuple2<Long, Long> first, Vertex<Long, ObjectMap> second,                           Collector<Tuple1<Long>> out) throws Exception {            out.collect(new Tuple1<>(first.f1));          }        });    DataSet<ComponentSourceTuple> componentSourceTuples = getComponentSourceTuples(vertices, vertexCcs);    DataSet<Tuple1<Long>> additionalCcs = vertexCcs.leftOuterJoin(relevantCcs)        .where(1)        .equalTo(0)        .with(new FlatJoinFunction<Tuple2<Long, Long>, Tuple1<Long>, Tuple2<Long, Long>>() {          @Override          public void join(Tuple2<Long, Long> left,                           Tuple1<Long> right,                           Collector<Tuple2<Long, Long>> out) throws Exception {            if (right == null) {              out.collect(left);            }          }        })        .<Tuple1<Long>>project(1)        .distinct();    /*      now based on sources count in component, we want at least 3 different sources     */    additionalCcs = additionalCcs        .join(componentSourceTuples)        .where(0)        .equalTo(0)        .with((Tuple1<Long> tuple, ComponentSourceTuple compTuple, Collector<Tuple1<Long>> collector) -> {          if (AbstractionUtils.getSourceCount(compTuple) >= 3) {            collector.collect(tuple);          }        })        .returns(new TypeHint<Tuple1<Long>>() {})//        .with(new FlatJoinFunction<Tuple1<Long>, ComponentSourceTuple, Tuple1<Long>>() {//          @Override//          public void join(Tuple1<Long> left,//                           ComponentSourceTuple right,//                           Collector<Tuple1<Long>> out) throws Exception {//            if (right != null && AbstractionUtils.getSourceCount(right) >= 3) {//              out.collect(left);//            }//          }//        })        .first(500);    return restrictVerticesToGivenCcs(vertices, vertexCcs, relevantCcs.union(additionalCcs));  }  private static DataSet<Tuple2<Long, Long>> getBaseVertexCcs(      ExecutionEnvironment env,      String ccFile) {    return env.readCsvFile(Constants.INPUT_PATH + ccFile)        .fieldDelimiter(";")        .ignoreInvalidLines()        .types(Integer.class, Integer.class)        .map(value -> new Tuple2<>((long) value.f0, (long) value.f1))        .returns(new TypeHint<Tuple2<Long, Long>>() {});  }  /**   * helper method nyt things   * @param vertices   * TODO check component source tuple usage   */  @Deprecated  public static DataSet<ComponentSourceTuple> getComponentSourceTuples(      DataSet<Vertex<Long, ObjectMap>> vertices,      DataSet<Tuple2<Long, Long>> ccs) {    if (ccs != null) {      vertices = vertices.leftOuterJoin(ccs)          .where(0)          .equalTo(0)          .with(new FlatJoinFunction<Vertex<Long, ObjectMap>,              Tuple2<Long, Long>,              Vertex<Long, ObjectMap>>() {            @Override            public void join(Vertex<Long, ObjectMap> left, Tuple2<Long, Long> right, Collector<Vertex<Long, ObjectMap>> out) throws Exception {              left.getValue().addProperty(Constants.CC_ID, right.f1);              out.collect(left);            }          });    }    return vertices        .groupBy(new CcIdKeySelector())        .reduceGroup(new GroupReduceFunction<Vertex<Long,ObjectMap>, ComponentSourceTuple>() {          @Override          public void reduce(Iterable<Vertex<Long, ObjectMap>> vertices, Collector<ComponentSourceTuple> out) throws Exception {            ComponentSourceTuple result = new ComponentSourceTuple();            boolean isFirst = true;            for (Vertex<Long, ObjectMap> vertex : vertices) {              if (isFirst) {                result.setCcId(vertex.getValue().getCcId());                isFirst = false;              }              result.addSource(vertex.getValue().getDataSource());            }            out.collect(result);          }        });  }  /**   * Restrict a set of vertices to a subset having the relevant cc ids.   * @param vertices input vertices   * @param vertexCcs vertex / cc id assignment   * @param relevantCcs cc ids for result vertex subset   * @return resulting vertices   */  private static DataSet<Vertex<Long, ObjectMap>> restrictVerticesToGivenCcs(      DataSet<Vertex<Long, ObjectMap>> vertices,      DataSet<Tuple2<Long, Long>> vertexCcs,      DataSet<Tuple1<Long>> relevantCcs) {    vertices = relevantCcs.join(vertexCcs)        .where(0)        .equalTo(1)        .with((left, right) -> new Tuple1<>(right.f0))        .returns(new TypeHint<Tuple1<Long>>() {})        .join(vertices)        .where(0)        .equalTo(0)        .with((id, vertex) -> vertex)        .returns(new TypeHint<Vertex<Long, ObjectMap>>() {})        .distinct(0);    return vertices;  }}