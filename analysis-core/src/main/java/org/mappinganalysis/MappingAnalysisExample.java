package org.mappinganalysis;import com.google.common.base.Preconditions;import com.google.common.primitives.Doubles;import org.apache.commons.cli.*;import org.apache.flink.api.common.ProgramDescription;import org.apache.flink.api.java.DataSet;import org.apache.flink.api.java.ExecutionEnvironment;import org.apache.flink.graph.Graph;import org.apache.flink.graph.Vertex;import org.apache.flink.types.NullValue;import org.apache.log4j.Logger;import org.mappinganalysis.io.output.ExampleOutput;import org.mappinganalysis.model.ObjectMap;import org.mappinganalysis.model.Preprocessing;import org.mappinganalysis.model.functions.refinement.Refinement;import org.mappinganalysis.model.functions.representative.MajorityPropertiesGroupReduceFunction;import org.mappinganalysis.model.functions.simcomputation.SimilarityComputation;import org.mappinganalysis.util.Constants;import org.mappinganalysis.util.Utils;import org.mappinganalysis.util.functions.keyselector.HashCcIdKeySelector;/** * Mapping analysis example */public class MappingAnalysisExample implements ProgramDescription {  private static final Logger LOG = Logger.getLogger(MappingAnalysisExample.class);  private static ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();  /**   * @param args cmd args   * @throws Exception   */  public static void main(String[] args) throws Exception {    Preconditions.checkArgument(        args.length == 8, "args[0]: input dir, " +            "args[1]: verbosity(less, info, debug), " +            "args[2]: isSimSortEnabled (isSimsort), " +            "args[3]: min cluster similarity (e.g., 0.7), " +            "args[4]: isGraphRestrictionActive (isRestrict), " +            "args[5]: min SimSort similarity (e.g., 0.5)" +            "args[6]: linklion mode (all, nyt, random)" +            "args[7]: processing mode (preproc, analysis)");    CommandLine cmd = parseArguments(args);    Constants.INPUT_DIR = args[0];    Constants.VERBOSITY = args[1];    Constants.IS_SIMSORT_ENABLED = args[2].equals("isSimsort");    Constants.IS_SIMSORT_ALT_ENABLED = args[2].equals("isSimsortAlt");    Constants.MIN_CLUSTER_SIM = Doubles.tryParse(args[3]);    Constants.MIN_SIMSORT_SIM = Doubles.tryParse(args[5]);    Constants.IS_RESTRICT_ACTIVE = args[4].equals("isRestrict");    Constants.LL_MODE = args[6];    Constants.IS_PREPROC_ONLY = args[7].equals("preproc");    Constants.MIN_LABEL_PRIORITY_SIM = 0.5;    Constants.IS_LINK_FILTER_ACTIVE = cmd.hasOption(OPTION_LINK_FILTER_PREPROCESSING);    Constants.IGNORE_MISSING_PROPERTIES = cmd.hasOption(OPTION_IGNORE_MISSING_PROPERTIES);    Constants.PRE_CLUSTER_STRATEGY = cmd.getOptionValue(OPTION_PRE_CLUSTER_FILTER, Constants.DEFAULT_VALUE);    /**     * Read data, execute analysis     */    ExampleOutput out = new ExampleOutput(env);    if (Constants.IS_PREPROC_ONLY) {      Graph<Long, ObjectMap, NullValue> graph = Preprocessing.getInputGraphFromCsv(env, out);      execute(graph, out);    } else {      // TODO use parameter      execute(Utils.readFromJSONFile(Constants.LL_MODE + "PreprocGraph", env), out);    }  }  /**   * Mapping analysis computation   */  private static <EV> void execute(Graph<Long, ObjectMap, EV> inputGraph, ExampleOutput out) throws Exception {    Constants.IGNORE_MISSING_PROPERTIES = true;    Constants.IS_LINK_FILTER_ACTIVE = true;    out.addVertexAndEdgeSizes("input size", inputGraph);    Graph<Long, ObjectMap, ObjectMap> graph = Preprocessing        .execute(inputGraph, out, env);    graph = SimilarityComputation.executeAdvanced(        graph,        Constants.DEFAULT_VALUE,        env,        out);//    Utils.writeToFile(graph.getVertices(), "4_post_sim_sort");//    out.addVertexAndEdgeSizes("4 vertex and edge sizes post simsort", graph);//    /* 4. Representative */    DataSet<Vertex<Long, ObjectMap>> representativeVertices = graph.getVertices()        .groupBy(new HashCcIdKeySelector())        .reduceGroup(new MajorityPropertiesGroupReduceFunction());//    Utils.writeToFile(representativeVertices, "5_cluster_representatives");    out.addClusterSizes("5 cluster sizes representatives", representativeVertices);    Utils.writeVerticesToJSONFile(representativeVertices, "representativesJSON");//    /* 5. Refinement */    representativeVertices = Refinement.init(representativeVertices, out);//    out.addClusterSizes("6a cluster sizes merge init", representativeVertices);//    Utils.writeToFile(representativeVertices, "6_init_merge_vertices");    DataSet<Vertex<Long, ObjectMap>> mergedClusters = Refinement.execute(representativeVertices, out);//    Utils.writeToFile(mergedClusters, "6 mergedVertices");    out.addClusterSizes("6b cluster sizes merged", mergedClusters);    if (!Constants.IS_PREPROC_ONLY) {      out.print();    }//    Stats.printResultEdgeCounts(inputGraph, out, mergedClusters);////    out.addSelectedBaseClusters("selected base clusters final values",//        preprocGraph.getVertices(),//        representativeVertices, Utils.getVertexList(dataset));//    out.printEvalThreePercent("3% eval", mergedClusters, preprocGraph.getVertices());//    Stats.addChangedWhileMergingVertices(out, representativeVertices, mergedClusters);//    Stats.printAccumulatorValues(env, graph);  }  /**   * Parses the program arguments or returns help if args are empty.   *   * @param args program arguments   * @return command line which can be used in the program   */  private static CommandLine parseArguments(String[] args) throws ParseException {    CommandLineParser parser = new BasicParser();    return parser.parse(OPTIONS, args);  }  /**   * Command line options   */  private static final String OPTION_LINK_FILTER_PREPROCESSING = "lfp";  private static final String OPTION_PRE_CLUSTER_FILTER = "pcf";  private static final String OPTION_ONLY_INITIAL_CLUSTER = "oic";  private static final String OPTION_DATA_SET_NAME = "ds";  private static final String OPTION_WRITE_STATS = "ws";  private static final String OPTION_CLUSTER_STATS = "cs";  private static final String OPTION_IGNORE_MISSING_PROPERTIES = "imp";  private static final String OPTION_PROCESSING_MODE = "pm";  private static final String OPTION_TYPE_MISS_MATCH_CORRECTION = "tmmc";  private static Options OPTIONS;  static {    OPTIONS = new Options();    // general    OPTIONS.addOption(OPTION_DATA_SET_NAME, "dataset-name", true,        "Choose one of the datasets [" + Constants.CMD_GEO + " (default), " + Constants.CMD_LL + "].");    OPTIONS.addOption(OPTION_PROCESSING_MODE, "processing-mode", true,        "Choose the processing mode [SimSort + TypeGroupBy (default), simSortOnly].");    OPTIONS.addOption(OPTION_IGNORE_MISSING_PROPERTIES, "ignore-missing-properties", false,        "Do not penalize missing properties on resources in similarity computation process (default: false).");    OPTIONS.addOption(OPTION_ONLY_INITIAL_CLUSTER, "only-initial-cluster", false,        "Don't compute final clusters, stop after preprocessing (default: false).");    // Preprocessing    OPTIONS.addOption(OPTION_LINK_FILTER_PREPROCESSING, "link-filter-preprocessing", false,        "Exclude edges where vertex has several target vertices having equal dataset ontology (default: false).");    OPTIONS.addOption(OPTION_TYPE_MISS_MATCH_CORRECTION, "type-miss-match-correction", false,        "Exclude edges where directly connected source and target vertices have different type property values. " +            "(default: false).");    // todo to be changed    OPTIONS.addOption(OPTION_PRE_CLUSTER_FILTER, "pre-cluster-filter", true,        "Specify preprocessing filter strategy for entity properties ["            + Constants.DEFAULT_VALUE + " (combined), geo, label, type]");    OPTIONS.addOption(OPTION_WRITE_STATS, "write-stats", false,        "Write statistics to output (default: false).");    Option clusterStats = new Option(OPTION_CLUSTER_STATS, "cluster-stats", true,        "Be more verbose while processing specified cluster ids.");    clusterStats.setArgs(Option.UNLIMITED_VALUES);    OPTIONS.addOption(clusterStats);  }  @Override  public String getDescription() {    return MappingAnalysisExample.class.getName();  }}