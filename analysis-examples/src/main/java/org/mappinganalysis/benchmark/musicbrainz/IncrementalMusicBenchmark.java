package org.mappinganalysis.benchmark.musicbrainz;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.common.ProgramDescription;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.io.LocalCollectionOutputFormat;
import org.apache.flink.api.java.operators.FilterOperator;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.graph.Edge;
import org.apache.flink.graph.Graph;
import org.apache.flink.graph.Vertex;
import org.apache.flink.types.NullValue;
import org.mappinganalysis.io.impl.DataDomain;
import org.mappinganalysis.io.impl.csv.CSVDataSource;
import org.mappinganalysis.io.impl.json.JSONDataSink;
import org.mappinganalysis.io.impl.json.JSONDataSource;
import org.mappinganalysis.io.impl.json.JSONToEdgeFormatter;
import org.mappinganalysis.model.ObjectMap;
import org.mappinganalysis.model.functions.blocking.BlockingStrategy;
import org.mappinganalysis.model.functions.clusterstrategies.ClusteringStep;
import org.mappinganalysis.model.functions.clusterstrategies.IncrementalClustering;
import org.mappinganalysis.model.functions.clusterstrategies.IncrementalClusteringStrategy;
import org.mappinganalysis.model.functions.incremental.MatchingStrategy;
import org.mappinganalysis.util.Constants;
import org.mappinganalysis.util.Utils;
import org.mappinganalysis.util.config.IncrementalConfig;
import org.mappinganalysis.util.functions.filter.SourceFilterFunction;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.Lists.newArrayList;

public class IncrementalMusicBenchmark implements ProgramDescription {
  private static ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

  // TODO FIX or remove
  private static final String PREPROCESSING_STEP = "incremental-preprocessing";
  private static final String MERGE_STEP = "incremental-merged-clusters";
  private static final String PRE_JOB = "Incremental Preprocessing";
  private static final String MER_JOB = "Incremental Merge";

  public static void main(String[] args) throws Exception {
    Preconditions.checkArgument(args.length == 3, "args[0]: input dir, " + "args[1]: file name, " + "args[2]: selection strategy (entity, source)");
    final String inputPath = args[0];
    final String vertexFileName = args[1];
    String strategy = args[2];
    final ClusteringStep clusteringStep;
    switch (strategy) {
      case "entity":
        clusteringStep = ClusteringStep.VERTEX_ADDITION;
        break;
      case "source":
        clusteringStep = ClusteringStep.SOURCE_ADDITION;
        break;
      default:
        throw new IllegalArgumentException("Unsupported step: " + strategy);
    }

    MatchingStrategy matchStrategy = MatchingStrategy.MAX_BOTH;

    IncrementalConfig config = new IncrementalConfig(DataDomain.MUSIC, env);
    config.setBlockingStrategy(BlockingStrategy.STANDARD_BLOCKING);
    config.setStrategy(IncrementalClusteringStrategy.MULTI);
    config.setMetric(Constants.COSINE_TRIGRAM);
    config.setStep(clusteringStep);
    config.setSimSortSimilarity(0.7);
    config.setMatchStrategy(matchStrategy);

    String jobName = setJobName(config);

    // read base graph
    Graph<Long, ObjectMap, NullValue> baseGraph
        = new CSVDataSource(inputPath, vertexFileName, env)
        .getGraph();

    DataSet<Vertex<Long, ObjectMap>> newVertices = baseGraph.getVertices()
        .filter(new SourceFilterFunction("2"));

    IncrementalClustering initialClustering = new IncrementalClustering
        .IncrementalClusteringBuilder(config)
        .setMatchElements(newVertices)
        .setNewSource("2")
        .build();

    Graph<Long, ObjectMap, NullValue> startingGraph = baseGraph
        .filterOnVertices(new SourceFilterFunction("1"));

    DataSet<Vertex<Long, ObjectMap>> clusters = startingGraph
        .run(initialClustering);

    new JSONDataSink(inputPath, "1+2".concat(jobName))
        .writeVertices(clusters);
    JobExecutionResult result = env.execute("1+2".concat(jobName));
    System.out.println("1+2".concat(jobName) + " needed "
        + result.getNetRuntime(TimeUnit.SECONDS) + " seconds.");

    /*
      +3
     */
    startingGraph = new JSONDataSource(inputPath, "1+2".concat(jobName), env)
        .getGraph(ObjectMap.class, NullValue.class);

    newVertices = baseGraph.getVertices()
        .filter(new SourceFilterFunction("3"));

    IncrementalClustering addThreeClustering = new IncrementalClustering
        .IncrementalClusteringBuilder(config)
        .setMatchElements(newVertices)
        .setNewSource("3")
        .build();

    clusters = startingGraph
        .run(addThreeClustering);

    new JSONDataSink(inputPath, "+3".concat(jobName))
        .writeVertices(clusters);
    result = env.execute("+3".concat(jobName));
    System.out.println("+3".concat(jobName) + " needed "
        + result.getNetRuntime(TimeUnit.SECONDS) + " seconds.");

    /*
      +4
     */
    startingGraph = new JSONDataSource(inputPath, "+3".concat(jobName), env)
        .getGraph(ObjectMap.class, NullValue.class);

    newVertices = baseGraph.getVertices()
        .filter(new SourceFilterFunction("4"));

    IncrementalClustering addFourClustering = new IncrementalClustering
        .IncrementalClusteringBuilder(config)
        .setMatchElements(newVertices)
        .setNewSource("4")
        .build();

    clusters = startingGraph
        .run(addFourClustering);

    new JSONDataSink(inputPath, "+4".concat(jobName))
        .writeVertices(clusters);
    result = env.execute("+4".concat(jobName));
    System.out.println("+4".concat(jobName) + " needed "
        + result.getNetRuntime(TimeUnit.SECONDS) + " seconds.");

    /*
      +5
     */
    startingGraph = new JSONDataSource(inputPath, "+4".concat(jobName), env)
        .getGraph(ObjectMap.class, NullValue.class);

    newVertices = baseGraph.getVertices()
        .filter(new SourceFilterFunction("5"));

    IncrementalClustering addFiveClustering = new IncrementalClustering
        .IncrementalClusteringBuilder(config)
        .setMatchElements(newVertices)
        .setNewSource("5")
        .build();

    clusters = startingGraph
        .run(addFiveClustering);

    new JSONDataSink(inputPath, "+5".concat(jobName))
        .writeVertices(clusters);
    result = env.execute("+5".concat(jobName));
    System.out.println("+5".concat(jobName) + " needed "
        + result.getNetRuntime(TimeUnit.SECONDS) + " seconds.");
  }

  private static String setJobName(IncrementalConfig config) {
    String jobName = "Inc-";
    if (config.getMatchStrategy() == MatchingStrategy.MAX_BOTH) {
      jobName = jobName.concat("Mb-");
    } else if (config.getMatchStrategy() == MatchingStrategy.HUNGARIAN) {
      jobName = jobName.concat("Hu-");
    }
    if (config.getStep() == ClusteringStep.SOURCE_ADDITION) {
      jobName = jobName.concat("Sa-");
    } else if (config.getStep() == ClusteringStep.VERTEX_ADDITION) {
      jobName = jobName.concat("Va-");
    }
    if (config.getBlockingStrategy() == BlockingStrategy.STANDARD_BLOCKING) {
      jobName = jobName.concat("Sb-");
    } else if (config.getBlockingStrategy() == BlockingStrategy.BLOCK_SPLIT) {
      jobName = jobName.concat("Bs-");
    }
    return jobName;
  }

  @Override
  public String getDescription() {
    return null;
  }
}