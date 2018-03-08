package org.mappinganalysis.model.functions.clusterstrategies;

import org.apache.flink.api.java.ExecutionEnvironment;
import org.mappinganalysis.model.functions.blocking.BlockingStrategy;

class BigIncrementalClustering extends IncrementalClustering {
  BigIncrementalClustering(BlockingStrategy blockingStrategy, String metric, ExecutionEnvironment env) {
    super(new BigIncrementalClusteringFunction(blockingStrategy, metric, env));
  }
}
