package org.mappinganalysis.model.functions.merge;

import com.google.common.collect.Maps;
import org.apache.log4j.Logger;
import org.mappinganalysis.graph.AggregationMode;
import org.mappinganalysis.graph.SimilarityFunction;
import org.mappinganalysis.model.MergeGeoTriplet;
import org.mappinganalysis.util.Constants;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.HashMap;

/**
 * Geo merge
 */
public class GeoMergeSimilarity
    extends SimilarityFunction<MergeGeoTriplet, MergeGeoTriplet>
    implements Serializable {
  private static final Logger LOG = Logger.getLogger(GeoMergeSimilarity.class);
  AggregationMode<MergeGeoTriplet> mode;

  public GeoMergeSimilarity(AggregationMode<MergeGeoTriplet> mode) {
    this.mode = mode;
  }

  @Override
  public MergeGeoTriplet map(MergeGeoTriplet triplet) throws Exception {
    Double labelSimilarity = getAttributeSimilarity(Constants.LABEL, triplet);

    HashMap<String, Double> values = Maps.newHashMap();
    values.put(Constants.LABEL, labelSimilarity);

    triplet.setSimilarity(mode.compute(values));

    return triplet;
  }

  private Double getAttributeSimilarity(String attrName, MergeGeoTriplet triplet) {
//    triplet.getSrcTuple()

    double similarity = 0D;
//    Utils.getTrigramMetricAndSimplifyStrings()
//        .compare(left.toLowerCase().trim(), right.toLowerCase().trim());
    BigDecimal tmpResult = new BigDecimal(similarity);

    return tmpResult.setScale(6, BigDecimal.ROUND_HALF_UP).doubleValue();
  }
}
