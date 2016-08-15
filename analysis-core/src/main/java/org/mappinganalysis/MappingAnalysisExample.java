package org.mappinganalysis;import com.google.common.base.Preconditions;import com.google.common.primitives.Doubles;import org.apache.flink.api.common.ProgramDescription;import org.apache.flink.api.java.DataSet;import org.apache.flink.api.java.ExecutionEnvironment;import org.apache.flink.graph.Graph;import org.apache.flink.graph.Vertex;import org.apache.log4j.Logger;import org.mappinganalysis.io.output.ExampleOutput;import org.mappinganalysis.model.ObjectMap;import org.mappinganalysis.model.Preprocessing;import org.mappinganalysis.model.functions.refinement.Refinement;import org.mappinganalysis.model.functions.representative.MajorityPropertiesGroupReduceFunction;import org.mappinganalysis.model.functions.simcomputation.SimilarityComputation;import org.mappinganalysis.util.Constants;import org.mappinganalysis.util.Stats;import org.mappinganalysis.util.Utils;import org.mappinganalysis.util.functions.keyselector.HashCcIdKeySelector;/** * Mapping analysis example */public class MappingAnalysisExample implements ProgramDescription {  private static final Logger LOG = Logger.getLogger(MappingAnalysisExample.class);  private static ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();  /**   * @param args cmd args   */  public static void main(String[] args) throws Exception {    Preconditions.checkArgument(        args.length == 7, "args[0]: input dir, " +            "args[1]: verbosity(less, info, debug), " +            "args[2]: isSimSortEnabled (isSimsort), " +            "args[3]: min cluster similarity (e.g., 0.7), " +            "args[4]: min SimSort similarity (e.g., 0.5)" +            "args[5]: linklion mode (all, nyt, random)" +            "args[6]: processing mode (input, preproc, analysis, eval)");    Constants.INPUT_DIR = args[0];    Constants.VERBOSITY = args[1];    Constants.IS_SIMSORT_ENABLED = args[2].equals("isSimsort");    Constants.IS_SIMSORT_ALT_ENABLED = args[2].equals("isSimsortAlt");    Constants.MIN_CLUSTER_SIM = Doubles.tryParse(args[3]);    Constants.MIN_SIMSORT_SIM = Doubles.tryParse(args[4]);    Constants.LL_MODE = args[5];    Constants.PROC_MODE = args[6];    Constants.MIN_LABEL_PRIORITY_SIM = 0.5;    /**     * Read data, execute analysis     */    ExampleOutput out = new ExampleOutput(env);    switch (Constants.PROC_MODE) {      case Constants.INPUT:        LOG.info("you most likely dont want to do this");        return;//        Graph<Long, ObjectMap, NullValue> graph = Preprocessing.getInputGraphFromCsv(env, out);//        Utils.writeGraphToJSONFile(graph, Constants.LL_MODE.concat("InputGraph"));//        env.execute();      case Constants.PREPROC:        execute(Utils.readFromJSONFile(            Constants.LL_MODE.concat(Constants.INPUT_GRAPH),            env),          out);        break;      case Constants.ANALYSIS:        execute(Utils.readFromJSONFile(Constants.LL_MODE.concat(Constants.PREPROC_GRAPH),            env),          out);        break;      case Constants.EVAL:        executeEval(out);        break;      case Constants.STATS_EDGE_INPUT:        executeStats(Constants.LL_MODE.concat(Constants.INPUT_GRAPH), "edge");        break;      case Constants.STATS_EDGE_PREPROC:        executeStats(Constants.INIT_CLUST, "edge");        break;      case Constants.STATS_VERTEX_INPUT:        executeStats(Constants.LL_MODE.concat(Constants.INPUT_GRAPH), "vertex");        break;      case Constants.STATS_VERTEX_PREPROC:        executeStats(Constants.INIT_CLUST, "vertex");        break;      case Constants.MISS:        executeStats(Constants.LL_MODE.concat(Constants.INPUT_GRAPH), "-");        break;    }  }  /**   * Mapping analysis computation   */  private static <EV> void execute(Graph<Long, ObjectMap, EV> inputGraph, ExampleOutput out) throws Exception {    Constants.IGNORE_MISSING_PROPERTIES = true;    Constants.IS_LINK_FILTER_ACTIVE = true;    if (Constants.PROC_MODE.equals(Constants.PREPROC)) {      out.addVertexAndEdgeSizes("input size", inputGraph);    }    Graph<Long, ObjectMap, ObjectMap> graph = Preprocessing        .execute(inputGraph, out, env);    if (Constants.PROC_MODE.equals(Constants.PREPROC)) {      Utils.writeGraphToJSONFile(graph, Constants.INIT_CLUST);    }    graph = SimilarityComputation.executeAdvanced(        graph,        Constants.DEFAULT_VALUE,        env,        out);    if (Constants.PROC_MODE.equals(Constants.ANALYSIS)) {      Utils.writeGraphToJSONFile(graph, "4-post-decomposition");      out.addVertexAndEdgeSizes("4 vertex and edge sizes post decomposition", graph);//    /* 4. Representative */      DataSet<Vertex<Long, ObjectMap>> representativeVertices = graph.getVertices()          .groupBy(new HashCcIdKeySelector())          .reduceGroup(new MajorityPropertiesGroupReduceFunction());      out.addClusterSizes("5 cluster sizes representatives", representativeVertices);      Utils.writeVerticesToJSONFile(representativeVertices, "5-representatives-json");//    /* 5. Refinement */      representativeVertices = Refinement.init(representativeVertices, out);//    out.addClusterSizes("6a cluster sizes merge init", representativeVertices);//    Utils.writeToFile(representativeVertices, "6_init_merge_vertices");      DataSet<Vertex<Long, ObjectMap>> mergedClusters = Refinement.execute(representativeVertices, out);      Utils.writeVerticesToJSONFile(mergedClusters, "6-merged-clusters-json");      out.addClusterSizes("6b cluster sizes merged", mergedClusters);      out.print();    }  }  /**   * if analysis rerun, add vertices to 5 + 6 directories   * @throws Exception   */  private static void executeEval(ExampleOutput out) throws Exception {    DataSet<Vertex<Long, ObjectMap>> mergedClusters        = Utils.readVerticesFromJSONFile("6-merged-clusters-json", env, false);//    DataSet<Vertex<Long, ObjectMap>> representativeVertices//        = Utils.readVerticesFromJSONFile("5-representatives-json", env, false);    Graph<Long, ObjectMap, ObjectMap> graph        = Utils.readFromJSONFile(Constants.LL_MODE.concat(Constants.PREPROC_GRAPH), env);//    Stats.printResultEdgeCounts(graph.mapEdges(new MapFunction<Edge<Long, ObjectMap>, NullValue>() {//      @Override//      public NullValue map(Edge<Long, ObjectMap> value) throws Exception {//        return NullValue.getInstance();//      }//    }), out, mergedClusters);//    out.addSelectedBaseClusters("selected base clusters final values",//        graph.getVertices(),//        representativeVertices, Utils.getVertexList(dataset));    out.printEvalThreePercent("eval", mergedClusters, graph.getVertices());    out.print();//    Stats.addChangedWhileMergingVertices(out, representativeVertices, mergedClusters);//    Stats.printAccumulatorValues(env, graph); // not working if workflow is split  }  /**   * For vertices, get vertex count per data source.   * For edges, get edge (undirected) edge counts between sources.   */  public static void executeStats(String graphPath, String edgeOrVertex) throws Exception {    Graph<Long, ObjectMap, ObjectMap> graph        = Utils.readFromJSONFile(graphPath, env);    if (edgeOrVertex.equals("edge")) { //&& graphPath.contains("InputGraph")) {      Stats.printEdgeSourceCounts(graph)          .print();    } else if (edgeOrVertex.equals("vertex")) {      Stats.printVertexSourceCounts(graph)          .print();    } else if (Constants.PROC_MODE.equals(Constants.MISS)) {      Stats.countMissingGeoAndTypeProperties(          Constants.LL_MODE.concat(Constants.INPUT_GRAPH),          false,          env)        .print();    }//    else {//      Graph<Long, ObjectMap, ObjectMap> input//          = Utils.readFromJSONFile(Constants.LL_MODE + "InputGraph", env);////      DataSet<Edge<Long, ObjectMap>> newEdges = Preprocessing//          .deleteEdgesWithoutSourceOrTarget(input.getEdges(), graph.getVertices());//      graph = Graph.fromDataSet(graph.getVertices(), newEdges, env);//      printEdgeSourceCounts(graph);//    }  }  @Override  public String getDescription() {    return MappingAnalysisExample.class.getName();  }}