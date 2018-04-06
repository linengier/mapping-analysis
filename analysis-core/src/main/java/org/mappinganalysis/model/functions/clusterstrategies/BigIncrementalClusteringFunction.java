package org.mappinganalysis.model.functions.clusterstrategies;

import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.graph.Graph;
import org.apache.flink.graph.Vertex;
import org.apache.flink.types.NullValue;
import org.apache.log4j.Logger;
import org.mappinganalysis.io.impl.DataDomain;
import org.mappinganalysis.model.ObjectMap;
import org.mappinganalysis.model.functions.CandidateCreator;
import org.mappinganalysis.model.functions.blocking.BlockingStrategy;
import org.mappinganalysis.model.functions.incremental.RepresentativeCreator;
import org.mappinganalysis.model.functions.merge.DualMergeGeographyMapper;
import org.mappinganalysis.model.functions.merge.FinalMergeGeoVertexCreator;
import org.mappinganalysis.model.functions.preprocessing.utils.InternalTypeMapFunction;
import org.mappinganalysis.util.Constants;
import org.mappinganalysis.util.config.Config;
import org.mappinganalysis.util.functions.filter.SourceFilterFunction;

public class BigIncrementalClusteringFunction
    extends IncrementalClusteringFunction {
  private static final Logger LOG = Logger.getLogger(BigIncrementalClusteringFunction.class);

  private BlockingStrategy blockingStrategy;
  private String metric;
  private ExecutionEnvironment env;

  BigIncrementalClusteringFunction(
      BlockingStrategy blockingStrategy,
      String metric,
      ExecutionEnvironment env) {
    super();
    this.blockingStrategy = blockingStrategy;
    this.metric = metric;
    this.env = env;
  }

  // TODO provenance
  // TODO sources count is fixed atm, how to have variable source count
  // TODO optimize: remove transition between vertices and tuples between merge steps
  @Override
  public DataSet<Vertex<Long, ObjectMap>> run(
      Graph<Long, ObjectMap, NullValue> input) throws Exception {
    Config config = new Config(DataDomain.GEOGRAPHY, env);
    config.setBlockingStrategy(blockingStrategy);
    config.setMetric(metric);

    DataSet<Vertex<Long, ObjectMap>> baseClusters = input
        .mapVertices(new InternalTypeMapFunction())
        .getVertices()
        .runOperation(new RepresentativeCreator(config));

    DataSet<Vertex<Long, ObjectMap>> gn = baseClusters
        .filter(new SourceFilterFunction(Constants.GN_NS));
    DataSet<Vertex<Long, ObjectMap>> nyt = baseClusters
        .filter(new SourceFilterFunction(Constants.NYT_NS));
    DataSet<Vertex<Long, ObjectMap>> dbp = baseClusters
        .filter(new SourceFilterFunction(Constants.DBP_NS));
    DataSet<Vertex<Long, ObjectMap>> fb = baseClusters
        .filter(new SourceFilterFunction(Constants.FB_NS));
    DataSet<Vertex<Long, ObjectMap>> lgd = baseClusters
        .filter(new SourceFilterFunction(Constants.LGD_NS));

    DataSet<Vertex<Long, ObjectMap>> result = gn.union(dbp)
        .runOperation(new CandidateCreator(config, Constants.DBP_NS, 2))
        .flatMap(new DualMergeGeographyMapper(false))
        .leftOuterJoin(baseClusters)
        .where(0)
        .equalTo(0)
        .with(new FinalMergeGeoVertexCreator())
        .runOperation(new RepresentativeCreator(config));

//    result = result.union(dbp)
//        .runOperation(new CandidateCreator(config, Constants.DBP_NS, 3))
//        .flatMap(new DualMergeGeographyMapper(false))
//        .leftOuterJoin(baseClusters)
//        .where(0)
//        .equalTo(0)
//        .with(new FinalMergeGeoVertexCreator())
//        .runOperation(new RepresentativeCreator(config);
//
//    result = result.union(fb)
//        .runOperation(new CandidateCreator(config, Constants.FB_NS, 4))
//        .flatMap(new DualMergeGeographyMapper(false))
//        .leftOuterJoin(baseClusters)
//        .where(0)
//        .equalTo(0)
//        .with(new FinalMergeGeoVertexCreator())
//        .runOperation(new RepresentativeCreator(config);
//
//    DataSet<Vertex<Long, ObjectMap>> finalResult = result.union(lgd)
//        .runOperation(new CandidateCreator(config, Constants.LGD_NS, 5))
//        .flatMap(new DualMergeGeographyMapper(false))
//        .leftOuterJoin(baseClusters)
//        .where(0)
//        .equalTo(0)
//        .with(new FinalMergeGeoVertexCreator())
//        .runOperation(new RepresentativeCreator(config);

    return result;
  }
}
