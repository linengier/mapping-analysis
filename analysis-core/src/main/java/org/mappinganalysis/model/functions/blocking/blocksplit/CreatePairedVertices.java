package org.mappinganalysis.model.functions.blocking.blocksplit;

import org.apache.flink.api.common.functions.GroupCombineFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple5;
import org.apache.flink.util.Collector;
import org.apache.log4j.Logger;
import org.mappinganalysis.io.impl.DataDomain;
import org.mappinganalysis.model.MergeMusicTriplet;
import org.mappinganalysis.model.MergeMusicTuple;
import org.mappinganalysis.util.AbstractionUtils;
import org.mappinganalysis.util.Constants;
import org.mappinganalysis.util.Utils;

import java.util.ArrayList;
import java.util.Collection;

public class CreatePairedVertices
    implements GroupCombineFunction<Tuple5<MergeMusicTuple, String, Long, Boolean, Integer>, MergeMusicTriplet> {
  private static final Logger LOG = Logger.getLogger(CreatePairedVertices.class);
  private DataDomain dataDomain;
  private String source;

  public CreatePairedVertices(DataDomain dataDomain, String source) {
    this.dataDomain = dataDomain;
    this.source = source;
  }

  @Override
  public void combine(Iterable<Tuple5<MergeMusicTuple, String, Long, Boolean, Integer>> input,
                      Collector<MergeMusicTriplet> out) throws Exception {
    Collection<Tuple2<MergeMusicTuple, Boolean>> tuples = new ArrayList<>();
    for (Tuple5<MergeMusicTuple, String, Long, Boolean, Integer> i : input){
      tuples.add(Tuple2.of(i.f0, i.f3));
    }

    Tuple2<MergeMusicTuple, Boolean>[] tuplesArray = tuples.toArray(new Tuple2[tuples.size()]);
    for (int i = 0; i< tuplesArray.length && tuplesArray [i].f1; i++) {
      for (int j = i+1; j< tuplesArray.length ; j++) {
//        if (!tuplesArray[i].f0.getGraphIds().containsAny(tuplesArray[j].f0.getGraphIds()))
        MergeMusicTriplet triplet = new MergeMusicTriplet(tuplesArray[i].f0, tuplesArray[j].f0);


        if (!source.equals(Constants.EMPTY_STRING)) {
          boolean sourceContains = AbstractionUtils
              .containsSrc(dataDomain, triplet.getSrcTuple().getIntSources(), source);
          int sourceCount = AbstractionUtils.getSourceCount(triplet.getSrcTuple().getIntSources());
          boolean targetContains = AbstractionUtils
              .containsSrc(dataDomain, triplet.getTrgTuple().getIntSources(), source);
          int targetCount = AbstractionUtils.getSourceCount(triplet.getTrgTuple().getIntSources());
          if (sourceContains && sourceCount == 1 || targetContains && targetCount == 1) {
            createResult(out, triplet);
          }
        } else {
          createResult(out, triplet);
        }
      }
    }
  }

  private void createResult(Collector<MergeMusicTriplet> out, MergeMusicTriplet triplet) {
    if (!AbstractionUtils.hasOverlap(triplet.getSrcTuple().getIntSources(), triplet.getTrgTuple().getIntSources())) {

//          LOG.info(triplet.toString());
      out.collect(triplet);
    }
  }
}

