package org.mappinganalysis;import com.google.common.base.Preconditions;import com.google.common.collect.Lists;import org.apache.commons.cli.*;import org.apache.flink.api.common.JobExecutionResult;import org.apache.flink.api.common.ProgramDescription;import org.apache.flink.api.common.functions.MapFunction;import org.apache.flink.api.java.DataSet;import org.apache.flink.api.java.ExecutionEnvironment;import org.apache.flink.graph.Edge;import org.apache.flink.graph.Graph;import org.apache.flink.graph.Vertex;import org.apache.flink.types.NullValue;import org.apache.log4j.Logger;import org.mappinganalysis.io.output.ExampleOutput;import org.mappinganalysis.model.ObjectMap;import org.mappinganalysis.model.Preprocessing;import org.mappinganalysis.model.functions.refinement.Refinement;import org.mappinganalysis.model.functions.representative.MajorityPropertiesGroupReduceFunction;import org.mappinganalysis.model.functions.simcomputation.SimilarityComputation;import org.mappinganalysis.utils.Utils;import org.mappinganalysis.utils.functions.keyselector.HashCcIdKeySelector;import java.util.ArrayList;import java.util.List;/** * Mapping analysis example */public class MappingAnalysisExample implements ProgramDescription {  private static final Logger LOG = Logger.getLogger(MappingAnalysisExample.class);  private static ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();  /**   * Command line options   */  private static final String OPTION_LINK_FILTER_PREPROCESSING = "lfp";  private static final String OPTION_PRE_CLUSTER_FILTER = "pcf";  private static final String OPTION_ONLY_INITIAL_CLUSTER = "oic";  private static final String OPTION_DATA_SET_NAME = "ds";  private static final String OPTION_WRITE_STATS = "ws";  private static final String OPTION_CLUSTER_STATS = "cs";  private static final String OPTION_IGNORE_MISSING_PROPERTIES = "imp";  private static final String OPTION_PROCESSING_MODE = "pm";  private static final String OPTION_TYPE_MISS_MATCH_CORRECTION = "tmmc";  private static boolean IS_TYPE_MISS_MATCH_CORRECTION_ACTIVE;  private static boolean STOP_AFTER_INITIAL_CLUSTERING;  private static List<Long> CLUSTER_STATS;  private static Options OPTIONS;  static {    OPTIONS = new Options();    // general    OPTIONS.addOption(OPTION_DATA_SET_NAME, "dataset-name", true,        "Choose one of the datasets [" + Utils.CMD_GEO + " (default), " + Utils.CMD_LL + "].");    OPTIONS.addOption(OPTION_PROCESSING_MODE, "processing-mode", true,        "Choose the processing mode [SimSort + TypeGroupBy (default), simSortOnly].");    OPTIONS.addOption(OPTION_IGNORE_MISSING_PROPERTIES, "ignore-missing-properties", false,        "Do not penalize missing properties on resources in similarity computation process (default: false).");    OPTIONS.addOption(OPTION_ONLY_INITIAL_CLUSTER, "only-initial-cluster", false,        "Don't compute final clusters, stop after preprocessing (default: false).");    // Preprocessing    OPTIONS.addOption(OPTION_LINK_FILTER_PREPROCESSING, "link-filter-preprocessing", false,        "Exclude edges where vertex has several target vertices having equal dataset ontology (default: false).");    OPTIONS.addOption(OPTION_TYPE_MISS_MATCH_CORRECTION, "type-miss-match-correction", false,        "Exclude edges where directly connected source and target vertices have different type property values. " +            "(default: false).");    // todo to be changed    OPTIONS.addOption(OPTION_PRE_CLUSTER_FILTER, "pre-cluster-filter", true,        "Specify preprocessing filter strategy for entity properties ["            + Utils.DEFAULT_VALUE + " (combined), geo, label, type]");    OPTIONS.addOption(OPTION_WRITE_STATS, "write-stats", false,        "Write statistics to output (default: false).");    Option clusterStats = new Option(OPTION_CLUSTER_STATS, "cluster-stats", true,        "Be more verbose while processing specified cluster ids.");    clusterStats.setArgs(Option.UNLIMITED_VALUES);    OPTIONS.addOption(clusterStats);  }  /**   * main program   * @param args cmd args   * @throws Exception   */  public static void main(String[] args) throws Exception {    Preconditions.checkArgument(        args.length == 3, "args[0]: choose dataset (linklion or geo), args[1]: input dir, " +            "args[2]: verbosity(less, info, debug)");    CommandLine cmd = parseArguments(args);    String dataset;    final String optionDataset = args[0];    Utils.INPUT_DIR = args[1];    Utils.VERBOSITY = args[2];    switch (optionDataset) {      case Utils.CMD_LL:        dataset = Utils.LL_FULL_NAME;        break;      case Utils.CMD_GEO:        dataset = Utils.GEO_FULL_NAME;        break;      default:        return;    }    Utils.IS_LINK_FILTER_ACTIVE = cmd.hasOption(OPTION_LINK_FILTER_PREPROCESSING);    IS_TYPE_MISS_MATCH_CORRECTION_ACTIVE = cmd.hasOption(OPTION_TYPE_MISS_MATCH_CORRECTION);    Utils.IGNORE_MISSING_PROPERTIES = cmd.hasOption(OPTION_IGNORE_MISSING_PROPERTIES);    Utils.PRE_CLUSTER_STRATEGY = cmd.getOptionValue(OPTION_PRE_CLUSTER_FILTER, Utils.DEFAULT_VALUE);    STOP_AFTER_INITIAL_CLUSTERING = cmd.hasOption(OPTION_ONLY_INITIAL_CLUSTER);    Utils.PRINT_STATS = cmd.hasOption(OPTION_WRITE_STATS);    String[] clusterStats = cmd.getOptionValues(OPTION_CLUSTER_STATS);    if (clusterStats != null) {      CLUSTER_STATS = Utils.convertWsSparatedString(clusterStats);    }    final Graph<Long, ObjectMap, NullValue> graph = Preprocessing.getInputGraphFromCsv(env);    execute(graph, dataset);  }  /**   * Mapping analysis computation   */  private static void execute(Graph<Long, ObjectMap, NullValue> inputGraph, String dataset) throws Exception {    ExampleOutput out = new ExampleOutput(env);//    out.addVertexAndEdgeSizes("pre", inputGraph);//    ArrayList<Long> clusterList = Lists.newArrayList(1458L);//, 2913L);//, 4966L, 5678L);    final ArrayList<Long> vertexList;    if (dataset.equals(Utils.GEO_FULL_NAME)) {      vertexList = Lists.newArrayList(123L, 122L, 2060L, 1181L);//1827L, 5026L, 6932L, 3420L, 5586L, 3490L, 3419L);    } else {      vertexList = Lists.newArrayList(100972L, 121545L, 276947L, 235633L, 185488L, 100971L, 235632L, 121544L, 909033L);    }    Utils.IGNORE_MISSING_PROPERTIES = true;    Utils.IS_LINK_FILTER_ACTIVE = true;//    out.addSelectedVertices("pre preprocessing selected vertices",//        inputGraph.getVertices(), vertexList);    final Graph<Long, ObjectMap, ObjectMap> preprocGraph = Preprocessing        .execute(inputGraph, Utils.IS_LINK_FILTER_ACTIVE, env);    final double minClusterSim = 0.7;    Graph<Long, ObjectMap, ObjectMap> graph = SimilarityComputation        .executeAdvanced(preprocGraph, Utils.DEFAULT_VALUE, minClusterSim, env);//    out.addClusterSizes("cluster sizes", graph.getVertices());//    out.addTuples("postSimSort", Utils.writeVertexComponentsToHDFS(graph, Utils.HASH_CC, "postSimSort"));//    out.addSelectedVertices("postSimSort", graph.getVertices(), vertexList);//    Utils.writeVertexComponentsToHDFS(graph, Utils.HASH_CC, "postSimSort");//    /* 4. Representative */    DataSet<Vertex<Long, ObjectMap>> representativeVertices = graph.getVertices()        .groupBy(new HashCcIdKeySelector())        .reduceGroup(new MajorityPropertiesGroupReduceFunction());//    /* 5. Refinement */    representativeVertices = Refinement.init(representativeVertices, minClusterSim);//    representativeVertices = Refinement.execute(representativeVertices);//    representativeVertices = Refinement.executeThird(representativeVertices, 1, 1, 0.7);//    representativeVertices = Refinement.executeThird(representativeVertices, 1, 2, 0.7);//    representativeVertices = Refinement.executeThird(representativeVertices, 2, 2, 0.7);//    representativeVertices = Refinement.executeThird(representativeVertices, 1, 3, 0.7);//    out.addClusterSizes("final cluster sizes", representativeVertices);    Utils.writeToHdfs(representativeVertices, "representativeVertices");//    LOG.info();//    System.out.println(env.getExecutionPlan());//    env.execute();//    LOG.info(representativeVertices.count());    out.addVertexSizes("end", representativeVertices);//    representativeVertices.first(1).print();//    out.addRandomFinalClustersWithMinSize(//        "final random clusters with vertex properties from preprocessing",//        representativeVertices,//        graph.getVertices(),//        15, 20);////    out.addRandomBaseClusters("base random clusters with vertex properties from final",//        graph.getVertices(),//        representativeVertices, 20);    out.print();//    if (dataset.equals(Utils.GEO_FULL_NAME)) {//      final ArrayList<Long> preSplitBigClusterList//          = Lists.newArrayList(4794L, 28L, 3614L, 4422L, 1429L, 1458L, 1868L);//      out.addSelectedBaseClusters("big components final values",//          Preprocessing.execute(inputGraph, true, env).getVertices(),//          representativeVertices,//          preSplitBigClusterList);//    }//    out.addSelectedVertices("end vertices", representativeVertices, vertexList);//        Stats.printAccumulatorValues(env, graph, simSortKeySelector);//        Stats.printComponentSizeAndCount(graph.getVertices());//        Stats.countPrintGeoPointsPerOntology(preprocGraph);//        printEdgesSimValueBelowThreshold(allEdgesGraph, accumulatedSimValues);//    env.execute();//    JobExecutionResult jobExecResult = env.getLastJobExecutionResult();////    LOG.info("[1] ### BaseVertexCreator vertex counter: "//        + jobExecResult.getAccumulatorResult(Utils.BASE_VERTEX_COUNT_ACCUMULATOR));//    LOG.info("[1] ### PropertyCoGroupFunction vertex counter: "//        + jobExecResult.getAccumulatorResult(Utils.VERTEX_COUNT_ACCUMULATOR));//    LOG.info("[1] ### FlinkEdgeCreator edge counter: "//        + jobExecResult.getAccumulatorResult(Utils.EDGE_COUNT_ACCUMULATOR));//    LOG.info("[1] ### FlinkPropertyMapper property counter: "//        + jobExecResult.getAccumulatorResult(Utils.PROP_COUNT_ACCUMULATOR));//    LOG.info("[1] ### typeMismatchCorrection wrong edges counter: "//        + jobExecResult.getAccumulatorResult(Utils.EDGE_EXCLUDE_ACCUMULATOR));//    LOG.info("[1] ### applyLinkFilterStrategy correct edges counter: "//        + jobExecResult.getAccumulatorResult(Utils.LINK_FILTER_ACCUMULATOR));////    LOG.info("[3] ### Representatives created: "//        + jobExecResult.getAccumulatorResult(Utils.REPRESENTATIVE_ACCUMULATOR)); // MajorityPropertiesGRFunction//    LOG.info("[3] ### Clusters created in refinement step: "//        + jobExecResult.getAccumulatorResult(Utils.REFINEMENT_MERGE_ACCUMULATOR)); // SimilarClusterMergeMapFunction//    LOG.info("[3] ### Excluded vertex counter: "//        + jobExecResult.getAccumulatorResult(Utils.EXCLUDE_VERTEX_ACCUMULATOR)); // ExcludeVertexFlatJoinFunction  }  /**   * Parses the program arguments or returns help if args are empty.   *   * @param args program arguments   * @return command line which can be used in the program   */  private static CommandLine parseArguments(String[] args) throws ParseException {    CommandLineParser parser = new BasicParser();    return parser.parse(OPTIONS, args);  }  @Override  public String getDescription() {    return MappingAnalysisExample.class.getName();  }}