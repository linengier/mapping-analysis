package org.mappinganalysis.model.functions.clusterstrategies;

import org.mappinganalysis.util.config.IncrementalConfig;

@Deprecated
class SplitIncrementalClustering extends IncrementalClustering {
  SplitIncrementalClustering(IncrementalConfig config, String part) {
    super(new SplitIncrementalClusteringFunction(config, part));
  }
}
