package org.mappinganalysis.model.functions.merge;

import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.graph.Vertex;
import org.apache.log4j.Logger;
import org.mappinganalysis.model.MergeMusicTuple;
import org.mappinganalysis.model.ObjectMap;
import org.mappinganalysis.util.Constants;
import org.mappinganalysis.util.Utils;

/**
 */
public class MergeMusicTupleCreator
    implements MapFunction<Vertex<Long, ObjectMap>, MergeMusicTuple> {
  private static final Logger LOG = Logger.getLogger(MergeGeoTupleCreator.class);

  @Override
  public MergeMusicTuple map(Vertex<Long, ObjectMap> vertex) throws Exception {
    MergeMusicTuple tuple = new MergeMusicTuple();

    ObjectMap properties = vertex.getValue();
    properties.setMode(Constants.MUSIC);

    tuple.setId(vertex.getId());
    tuple.setLabel(properties.getLabel());

    tuple.setAlbum(properties.getAlbum());
    tuple.setArtist(properties.getArtist());
    tuple.setLength(properties.getLength());
    tuple.setLang(properties.getLanguage());
    tuple.setNumber(properties.getNumber());
    tuple.setYear(properties.getYear());

    tuple.setIntSources(properties.getIntDataSources());
    tuple.addClusteredElements(properties.getVerticesList());
    tuple.setBlockingLabel(Utils.getMusicBlockingLabel(properties.getLabel()));

//    LOG.info("### CREATE: " + tuple.toString());
    return tuple;
  }
}