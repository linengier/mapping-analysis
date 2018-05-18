package org.mappinganalysis.model.functions.clusterstrategies;

import com.google.common.collect.Sets;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.graph.Edge;
import org.apache.flink.graph.Graph;
import org.apache.flink.graph.Vertex;
import org.apache.flink.types.NullValue;
import org.apache.log4j.Logger;
import org.mappinganalysis.graph.utils.AllEdgesCreateGroupReducer;
import org.mappinganalysis.io.impl.DataDomain;
import org.mappinganalysis.model.ObjectMap;
import org.mappinganalysis.model.functions.CandidateCreator;
import org.mappinganalysis.model.functions.decomposition.representative.RepresentativeCreatorMultiMerge;
import org.mappinganalysis.model.functions.decomposition.simsort.SimSort;
import org.mappinganalysis.model.functions.incremental.BlockingKeySelector;
import org.mappinganalysis.model.functions.incremental.RepresentativeCreator;
import org.mappinganalysis.model.functions.merge.DualMergeGeographyMapper;
import org.mappinganalysis.model.functions.merge.FinalMergeGeoVertexCreator;
import org.mappinganalysis.model.functions.preprocessing.DefaultPreprocessing;
import org.mappinganalysis.util.Constants;
import org.mappinganalysis.util.config.IncrementalConfig;


/**
 * Incremental clustering with multiple sources.
 */
public class MultiIncrementalClusteringFunction extends IncrementalClusteringFunction {
  private static final Logger LOG = Logger.getLogger(MultiIncrementalClusteringFunction.class);
  private DataSet<Vertex<Long, ObjectMap>> toBeMergedElements = null;
  private IncrementalConfig config;

  MultiIncrementalClusteringFunction(
      DataSet<Vertex<Long, ObjectMap>> toBeMergedElements,
      IncrementalConfig config) {
    super();
    this.config = config;
    this.toBeMergedElements = toBeMergedElements
        .runOperation(new RepresentativeCreator(config));
  }

  MultiIncrementalClusteringFunction(IncrementalConfig config) {
    super();
    this.config = config;
  }

  @Override
  public DataSet<Vertex<Long, ObjectMap>> run(
      Graph<Long, ObjectMap, NullValue> input) throws Exception {
    LOG.info(config.getStep().toString());
    DataSet<Vertex<Long, ObjectMap>> vertices = input
        .getVertices();

    /*
    INITIAL CLUSTERING
     */
    if (config.getStep() == ClusteringStep.INITIAL_CLUSTERING) {
      DataSet<Edge<Long, NullValue>> edges = vertices
          .map(new BlockingKeyMapFunction(config.getConfigNoEnv()))
          .groupBy(new BlockingKeySelector())
          .reduceGroup(new AllEdgesCreateGroupReducer<>());

    /*
      representative creation based on hash cc ids, most likely only for initial clustering
     */
      return Graph
          .fromDataSet(vertices,
              edges,
              config.getExecutionEnvironment())
          .run(new DefaultPreprocessing(config))
          .run(new SimSort(config))
          .getVertices()
          .runOperation(new RepresentativeCreatorMultiMerge(config.getDataDomain()));
    } else
      /*
      VERTEX ADDITION
       */
      if (config.getStep() == ClusteringStep.VERTEX_ADDITION
          || config.getStep() == ClusteringStep.SOURCE_ADDITION) {
        /*
          add type settlement for entities without type
         */
        if (config.getDataDomain() == DataDomain.GEOGRAPHY) {
          toBeMergedElements = toBeMergedElements
              .map(vertex -> {
                if (vertex.getValue().getTypesIntern().contains(Constants.NO_TYPE)) {
                  vertex.getValue()
                      .setTypes(Constants.TYPE_INTERN, Sets.newHashSet(Constants.S));
                }
                return vertex;
              })
              .returns(new TypeHint<Vertex<Long, ObjectMap>>() {});
        }

        DataSet<Vertex<Long, ObjectMap>> clusterWorkset = input
            .getVertices()
            .union(toBeMergedElements)
            .map(new RuntimePropertiesMapFunction(config.getConfigNoEnv()));

        if (config.getStep() == ClusteringStep.VERTEX_ADDITION) {
          DataSet<Edge<Long, NullValue>> edges = clusterWorkset
              .groupBy(new BlockingKeySelector())
              .reduceGroup(new AllEdgesCreateGroupReducer<>());

          return Graph
              .fromDataSet(clusterWorkset,
                  edges,
                  config.getExecutionEnvironment())
              .run(new DefaultPreprocessing(config))
              .run(new SimSort(config))
              .getVertices()
              .runOperation(new RepresentativeCreatorMultiMerge(config.getDataDomain()));
        } else if (config.getStep() == ClusteringStep.SOURCE_ADDITION) {
          return null;
//          return clusterWorkset
//              .runOperation(new CandidateCreator(config))
//
//
//              .flatMap(new DualMergeGeographyMapper(false))
//
//              .leftOuterJoin(inputClusters) // TODO get orig vertices, not GeoTuplets
//              .where(0)
//              .equalTo(0)
//              .with(new FinalMergeGeoVertexCreator())
//
//              .runOperation(new RepresentativeCreator(config));
//          // Todo simsort?
        }
      }
    return null;
  }

}
