package org.mappinganalysis.model.functions.merge;

import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.log4j.Logger;
import org.junit.Test;
import org.mappinganalysis.TestBase;
import org.mappinganalysis.io.impl.DataDomain;
import org.mappinganalysis.io.impl.json.JSONDataSource;
import org.mappinganalysis.model.MergeGeoTriplet;
import org.mappinganalysis.model.MergeGeoTuple;
import org.mappinganalysis.model.functions.preprocessing.AddShadingTypeMapFunction;

import static org.junit.Assert.assertTrue;

public class MergeGeoTupleCreatorTest {
  private static ExecutionEnvironment env;
  private static final Logger LOG = Logger.getLogger(MergeGeoBlockingTest.class);

  /**
   * 1. part
   * @throws Exception
   */
  @Test
  public void testMergeTupleCreator() throws Exception {
    env = TestBase.setupLocalEnvironment();
    TestBase.setupConstants();

    String graphPath = MergeGeoBlockingTest.class
        .getResource("/data/representative/mergeExec/").getFile();

    DataSet<MergeGeoTuple> result = new JSONDataSource(graphPath, true, env)
        .getVertices()
        .map(new AddShadingTypeMapFunction())
        .map(new MergeGeoTupleCreator());

    assertTrue(11 == result.collect().size());
  }

  /**
   * 2. part
   * @throws Exception
   */
  @Test
  public void testMergeTripletCreator() throws Exception {
    env = TestBase.setupLocalEnvironment();
    TestBase.setupConstants();

    String graphPath = MergeGeoBlockingTest.class
        .getResource("/data/representative/mergeExec/").getFile();

    DataSet<MergeGeoTuple> result = new JSONDataSource(graphPath, true, env)
        .getVertices()
        .map(new AddShadingTypeMapFunction())
        .map(new MergeGeoTupleCreator());

    DataSet<MergeGeoTriplet> initialWorkingSet = result
        .filter(new SourceCountRestrictionFilter<>(DataDomain.GEOGRAPHY, 5))
        .groupBy(7) // TODO
        .reduceGroup(new MergeGeoTripletCreator(5));

    initialWorkingSet.print();
  }


}