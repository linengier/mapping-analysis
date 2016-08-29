package org.mappinganalysis.model.functions.merge;

import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import org.apache.flink.api.common.functions.GroupReduceFunction;
import org.apache.flink.api.common.functions.JoinFunction;
import org.apache.flink.api.common.functions.RichFilterFunction;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.operators.IterativeDataSet;
import org.apache.flink.api.java.tuple.Tuple1;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple4;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.graph.Triplet;
import org.apache.flink.graph.Vertex;
import org.apache.flink.types.NullValue;
import org.apache.flink.util.Collector;
import org.apache.log4j.Logger;
import org.mappinganalysis.io.output.ExampleOutput;
import org.mappinganalysis.model.MergeTuple;
import org.mappinganalysis.model.ObjectMap;
import org.mappinganalysis.model.SrcTrgTuple;
import org.mappinganalysis.model.functions.decomposition.representative.MajorityPropertiesGroupReduceFunction;
import org.mappinganalysis.model.functions.preprocessing.AddShadingTypeMapFunction;
import org.mappinganalysis.model.functions.simcomputation.AggSimValueTripletMapFunction;
import org.mappinganalysis.model.functions.simcomputation.SimilarityComputation;
import org.mappinganalysis.model.functions.stats.FrequencyMapByFunction;
import org.mappinganalysis.util.Constants;
import org.mappinganalysis.util.SourcesUtils;
import org.mappinganalysis.util.Utils;
import org.mappinganalysis.util.functions.RightSideOnlyJoinFunction;
import org.mappinganalysis.util.functions.filter.OldHashCcFilterFunction;
import org.mappinganalysis.util.functions.filter.RefineIdExcludeFilterFunction;
import org.mappinganalysis.util.functions.filter.RefineIdFilterFunction;
import org.mappinganalysis.util.functions.keyselector.OldHashCcKeySelector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * previously Refinement
 */
public class Merge {
  private static final Logger LOG = Logger.getLogger(Merge.class);

  /**
   * Prepare vertex dataset for the following refinement step
   * @param vertices representative vertices
   * @return prepared vertices
   */
  public static DataSet<Vertex<Long, ObjectMap>> init(DataSet<Vertex<Long, ObjectMap>> vertices,
                                                      ExampleOutput out) {
    DataSet<Triplet<Long, ObjectMap, NullValue>> oldHashCcTriplets = vertices
        .filter(new OldHashCcFilterFunction())
        .groupBy(new OldHashCcKeySelector())
        .reduceGroup(new TripletCreateGroupReduceFunction());

    return rejoinSingleVertexClustersFromSimSort(vertices, oldHashCcTriplets, out);
  }

  /**
     * Execute the refinement step - compare clusters with each other and combine similar clusters.
     * @param baseClusters prepared dataset
     * @return refined dataset
     */
  public static DataSet<Vertex<Long, ObjectMap>> execute(
      DataSet<Vertex<Long, ObjectMap>> baseClusters,
      ExampleOutput out)
      throws Exception {
    int maxClusterSize = 4;
    if (Constants.INPUT_DIR.contains("linklion")) {
      maxClusterSize = 5;
    }
    IterativeDataSet<Vertex<Long, ObjectMap>> workingSet = baseClusters.iterate(Integer.MAX_VALUE);

    // base input data
    DataSet<Vertex<Long, ObjectMap>> left = workingSet
        .filter(new ClusterSizeFilterFunction(maxClusterSize));
    left = printSuperstep(left);
    DataSet<Vertex<Long, ObjectMap>> right = left;

    // restrict triplets to restrict problem

    DataSet<Triplet<Long, ObjectMap, NullValue>> baseTriplets = left
        .map(new AddShadingTypeMapFunction())
        .flatMap(new MergeTupleMapper())
        .groupBy(1, 2)
        .reduceGroup(new BaseTripletCreateFunction());

    baseTriplets.leftOuterJoin(left)
        .where(0)
        .equalTo(0)
        .with(new AdvancedTripletCreateFunction(0))
        .leftOuterJoin(left)
        .where(1)
        .equalTo(0)
        .with(new AdvancedTripletCreateFunction(1));

    // todo sim computation + optimize sim comp

    DataSet<Triplet<Long, ObjectMap, NullValue>> triplets = left.cross(right)
        .with(new TripletCreateCrossFunction(maxClusterSize))
        .filter(value -> value.getSrcVertex().getId() != 0L && value.getTrgVertex().getId() != 0L);

    // - similarity on intriplets + threshold
    DataSet<Triplet<Long, ObjectMap, ObjectMap>> similarTriplets = SimilarityComputation
        .computeSimilarities(triplets, Constants.DEFAULT_VALUE)
        .map(new AggSimValueTripletMapFunction(Constants.IGNORE_MISSING_PROPERTIES,
            Constants.MIN_LABEL_PRIORITY_SIM))
        .withForwardedFields("f0;f1;f2;f3")
        .filter(new MinRequirementThresholdFilterFunction(Constants.MIN_CLUSTER_SIM));

    // - exclude duplicate ontology vertices
    // - mark matches with more than 1 equal src/trg high similarity triplets
    similarTriplets = similarTriplets
        .leftOuterJoin(extractNoticableTriplets(similarTriplets))
        .where(0,1)
        .equalTo(0,1)
        .with(new ExcludeDuplicateOntologyTripletFlatJoinFunction())
        .distinct(0,1);

    DataSet<Tuple4<Long, Long, Double, Integer>> srcTrgSimOneTuple = similarTriplets
        .filter(new RefineIdFilterFunction())
        .map(new CreateNoticableTripletMapFunction());

    DataSet<Triplet<Long, ObjectMap, ObjectMap>> filteredNoticableTriplets = srcTrgSimOneTuple
        .groupBy(0)
        .max(2).andSum(3)
        .union(srcTrgSimOneTuple
            .groupBy(1)
            .max(2).andSum(3))
        .filter(tuple -> tuple.f3 > 1)
        .leftOuterJoin(similarTriplets)
        .where(0, 1)
        .equalTo(0, 1)
        .with((tuple, triplet) -> {
          LOG.info("final triplet: " + triplet.toString());
          return triplet;
        })
        .returns(new TypeHint<Triplet<Long, ObjectMap, ObjectMap>>() {});

    DataSet<Vertex<Long, ObjectMap>> newClusters = similarTriplets
        .filter(new RefineIdExcludeFilterFunction()) // EXCLUDE_VERTEX_ACCUMULATOR counter
        .union(filteredNoticableTriplets)
        .map(new SimilarClusterMergeMapFunction());

    DataSet<Vertex<Long, ObjectMap>> newVertices = excludeClusteredVerticesFromInput(workingSet, similarTriplets);

    return workingSet.closeWith(newClusters.union(newVertices), newClusters);
  }

  private static DataSet<Vertex<Long, ObjectMap>> printSuperstep(DataSet<Vertex<Long, ObjectMap>> left) {
    DataSet<Vertex<Long, ObjectMap>> superstepPrinter = left
        .first(1)
        .filter(new RichFilterFunction<Vertex<Long, ObjectMap>>() {
          private Integer superstep = null;
          @Override
          public void open(Configuration parameters) throws Exception {
            this.superstep = getIterationRuntimeContext().getSuperstepNumber();
          }
          @Override
          public boolean filter(Vertex<Long, ObjectMap> vertex) throws Exception {
            LOG.info("Superstep: " + superstep);
            return false;
          }
        });

    left = left.union(superstepPrinter);
    return left;
  }

  /**
   * Remove cluster ids from vertex set which are merged.
   * @param baseVertexSet base vertex set
   * @param similarTriplets triplets where source and target vertex needs to be removed from base vertex set
   * @return cleaned base vertex set
   */
  private static DataSet<Vertex<Long, ObjectMap>> excludeClusteredVerticesFromInput(
      DataSet<Vertex<Long, ObjectMap>> baseVertexSet,
      DataSet<Triplet<Long, ObjectMap, ObjectMap>> similarTriplets) {
    return similarTriplets
          .flatMap(new VertexExtractFlatMapFunction())
          .<Tuple1<Long>>project(0)
          .distinct()
          .rightOuterJoin(baseVertexSet)
          .where(0).equalTo(0)
          .with(new RightSideOnlyJoinFunction<>());
  }

  /**
   * Exclude
   * 1. tuples where duplicate ontologies are found
   * 2. tuples where more than one match occurs
   */
  private static DataSet<Tuple4<Long, Long, Long, Double>> extractNoticableTriplets(
      DataSet<Triplet<Long, ObjectMap, ObjectMap>> similarTriplets) throws Exception {

    DataSet<Triplet<Long, ObjectMap, ObjectMap>> equalSourceVertex = getDuplicateTriplets(similarTriplets, 0);
    DataSet<Triplet<Long, ObjectMap, ObjectMap>> equalTargetVertex = getDuplicateTriplets(similarTriplets, 1);

    return excludeTuples(equalSourceVertex, 1)
        .union(excludeTuples(equalTargetVertex, 0))
        .distinct();
  }

  /**
   * Exclude
   * 1. tuples where duplicate ontologies are found
   * 2. tuples where more than one match occures
   *
   * return value for "to be excluded" triplets:
   * - srcId, trgId, Long.MIN_VALUE, 0D
   *
   * @param triplets input triplets
   * @param column 0 - source, 1 - target
   * @return tuples which should be excluded
   */
  private static DataSet<Tuple4<Long, Long, Long, Double>> excludeTuples(
      DataSet<Triplet<Long, ObjectMap, ObjectMap>> triplets, final int column) {
    return triplets
        .groupBy(1 - column)
        .reduceGroup(new CollectExcludeTuplesGroupReduceFunction(column));
  }


  /**
   * Return triplet data for certain vertex id's. TODO check
   * @param similarTriplets source triplets
   * @param column search for vertex id in triplets source (0) or target (1)
   * @return resulting triplets
   */
  private static DataSet<Triplet<Long, ObjectMap, ObjectMap>> getDuplicateTriplets(
      DataSet<Triplet<Long, ObjectMap,
      ObjectMap>> similarTriplets, int column) {

    DataSet<Tuple2<Long, Long>> duplicateTuples = similarTriplets
        .<Tuple2<Long, Long>>project(0,1)
        .map(new FrequencyMapByFunction(column))
        .groupBy(0)
        .sum(1)
        .filter(tuple -> {
          LOG.info("getDuplicateTriplets: " + tuple.toString());
          return tuple.f1 > 1;
        });

    return duplicateTuples.leftOuterJoin(similarTriplets)
        .where(0)
        .equalTo(column)
        .with((tuple, triplet) -> triplet)
        .returns(new TypeHint<Triplet<Long, ObjectMap, ObjectMap>>() {});
  }

  /**
   * With SimSort, we extract (potentially two) single vertices from a component.
   * Here we try to rejoin vertices which have been in one cluster previously to reduce the
   * complexity for the following merge step.
   */
  private static DataSet<Vertex<Long, ObjectMap>> rejoinSingleVertexClustersFromSimSort(
      DataSet<Vertex<Long, ObjectMap>> representativeVertices,
      DataSet<Triplet<Long, ObjectMap, NullValue>> oldHashCcTriplets, ExampleOutput out) {

    // vertices with min sim, some triplets get omitted -> error cause
    DataSet<Triplet<Long, ObjectMap, ObjectMap>> newBaseTriplets = SimilarityComputation
        .computeSimilarities(oldHashCcTriplets, Constants.DEFAULT_VALUE)
        .map(new AggSimValueTripletMapFunction(Constants.IGNORE_MISSING_PROPERTIES,
            Constants.MIN_LABEL_PRIORITY_SIM))
        .withForwardedFields("f0;f1;f2;f3");

//    out.addDataSetCount("newBaseTriplets", newBaseTriplets);
//    Utils.writeToFile(newBaseTriplets, "6-init-newBaseTriplets");

    DataSet<Triplet<Long, ObjectMap, ObjectMap>> newRepresentativeTriplets = newBaseTriplets
        .filter(new MinRequirementThresholdFilterFunction(Constants.MIN_CLUSTER_SIM));

    // reduce to single representative, some vertices are now missing
    DataSet<Vertex<Long, ObjectMap>> newRepresentativeVertices = newRepresentativeTriplets
        .flatMap(new VertexExtractFlatMapFunction())
        .groupBy(new OldHashCcKeySelector())
        .reduceGroup(new MajorityPropertiesGroupReduceFunction());

    return newRepresentativeTriplets
        .flatMap(new VertexExtractFlatMapFunction())
        .<Tuple1<Long>>project(0)
        .distinct()
        .rightOuterJoin(representativeVertices)
        .where(0)
        .equalTo(0)
        .with(new RightSideOnlyJoinFunction<>())
        .union(newRepresentativeVertices);
  }

  /**
   * Get the hash map value having the highest count of occurrence.
   * For label property, if count is equal, a longer string is preferred.
   * @param map containing value options with count of occurrence
   * @param propertyName if label, for same occurrence count the longer string is taken
   * @return resulting value
   */
  public static <T> T getFinalValue(HashMap<T, Integer> map, String propertyName) {
    Map.Entry<T, Integer> finalEntry = null;
    map = Utils.sortByValue(map);

    for (Map.Entry<T, Integer> entry : map.entrySet()) {
      if (finalEntry == null || Ints.compare(entry.getValue(), finalEntry.getValue()) > 0) {
        finalEntry = entry;
      } else if (entry.getKey() instanceof String
          && propertyName.equals(Constants.LABEL)
          && Ints.compare(entry.getValue(), finalEntry.getValue()) >= 0) {
        String labelKey = entry.getKey().toString();
        if (labelKey.length() > finalEntry.getKey().toString().length()) {
          finalEntry = entry;
        }
      }
    }

    checkArgument(finalEntry != null, "Entry must not be null");
    return finalEntry.getKey();
  }

  private static class BaseTripletCreateFunction
      implements GroupReduceFunction<MergeTuple, Triplet<Long, ObjectMap, NullValue>> {
    private final Triplet<Long, ObjectMap, NullValue> reuseTriplet;

    public BaseTripletCreateFunction() {
      reuseTriplet = new Triplet<>();
    }

    @Override
    public void reduce(Iterable<MergeTuple> values,
                       Collector<Triplet<Long, ObjectMap, NullValue>> out) throws Exception {
      HashSet<MergeTuple> leftSide = Sets.newHashSet(values);
      HashSet<MergeTuple> rightSide = Sets.newHashSet(leftSide);

      for (MergeTuple leftTuple : leftSide) {
        Integer leftSources = leftTuple.getIntSources();
        reuseTriplet.f0 = leftTuple.getVertexId();

        rightSide.remove(leftTuple);
        for (MergeTuple rightTuple : rightSide) {
          int summedSources = SourcesUtils.getSourceCount(leftSources)
              + SourcesUtils.getSourceCount(rightTuple.getIntSources());
          if (summedSources <= Constants.SOURCE_COUNT
              && !SourcesUtils.hasOverlap(leftSources, rightTuple.getIntSources())) {
            reuseTriplet.f1 = rightTuple.getVertexId();

            out.collect(reuseTriplet);
          }
        }
      }
    }
  }

  /**
   * We should not use reuseTriplet here because we have to walk twice over the triplet
   */
  private static class AdvancedTripletCreateFunction
      implements JoinFunction<Triplet<Long, ObjectMap, NullValue>,
      Vertex<Long,ObjectMap>,
      Triplet<Long, ObjectMap, NullValue>> {
//    private final Triplet<Long, ObjectMap, ObjectMap> reuseTriplet;
    private final int side;

    public AdvancedTripletCreateFunction(int side) {
//      this.reuseTriplet = new Triplet<>();
      this.side = side;
    }
    @Override
    public Triplet<Long, ObjectMap, NullValue> join(Triplet<Long, ObjectMap, NullValue> left,
                                                    Vertex<Long, ObjectMap> right) throws Exception {
      if (side == 0) {
        left.f2 = right.getValue();
        left.f4 = NullValue.getInstance();
      } else if (side == 1) {
        left.f3 = right.getValue();
      }

      return left;
    }
  }
}