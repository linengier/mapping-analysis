package org.mappinganalysis.model.functions.typegroupby;

import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.graph.Graph;
import org.apache.flink.graph.Vertex;
import org.apache.log4j.Logger;
import org.junit.Test;
import org.mappinganalysis.MappingAnalysisExampleTest;
import org.mappinganalysis.model.ObjectMap;
import org.mappinganalysis.util.Constants;
import org.s1ck.gdl.GDLHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TypeGroupByTest {
  private static final ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

  /**
   * test if no type vertices are handled correctly
   */
  private static final String TGB_EQUAL_SIM_NO_TYPE_LOW_CCID = "g[" +
      "(v1 {compType = \"" + Constants.NO_TYPE + "\", hashCc = -4832605177143139923L})" +
      "(v2 {compType = \"" + Constants.NO_TYPE + "\", hashCc = 6500562624977345488L})" +
      "(v3 {compType = \"AdministrativeRegion\", hashCc = -8401086609692859185L})]" +
      "(v1)-[e1:sameAs {aggSimValue = 0.9428090453147888D}]->(v2)" +
      "(v1)-[e2:sameAs {aggSimValue = 0.9428090453147888D}]->(v3)";

  /**
   * test if lowest cc is resulting cc for all vertices (no type at all)
   */
  private static final String NO_TYPE_STRING = "g[" +
      "(v1 {typeIntern = \"" + Constants.NO_TYPE + "\", compType = \"" + Constants.NO_TYPE + "\", hashCc = -814546109484291321L})" +
      "(v2 {typeIntern = \"" + Constants.NO_TYPE + "\", compType = \"" + Constants.NO_TYPE + "\", hashCc = -7443960355069871745L})" +
      "(v3 {typeIntern = \"" + Constants.NO_TYPE + "\", compType = \"" + Constants.NO_TYPE + "\", hashCc = 7462085439452287248L})]" +
      "(v1)-[e1:sameAs {aggSimValue = 0.9D}]->(v2)" +
      "(v1)-[e2:sameAs {aggSimValue = 0.9D}]->(v3)";

  private static final String TGB_SIMPLE = "g[" +
      "(v1 {compType = \"" + Constants.NO_TYPE + "\", hashCc = 12L})" +
      "(v2 {compType = \"Mountain\", hashCc = 23L})" +
      "(v3 {compType = \"Settlement\", hashCc = 42L})" +
      "(v4 {compType = \"Settlement\", hashCc = 42L})]" +
      "(v1)-[e1:sameAs {aggSimValue = .9D}]->(v2)" +
      "(v1)-[e2:sameAs {aggSimValue = .4D}]->(v3)" +
      "(v1)-[e3:sameAs {aggSimValue = .7D}]->(v4)";

  private static final String TGB_TRIPLE_UNKNOWN = "g[" +
      "(v1 {compType = \"Settlement\", hashCc = 12L})" +
      "(v2 {compType = \"" + Constants.NO_TYPE + "\", hashCc = 21L})" +
      "(v3 {compType = \"" + Constants.NO_TYPE + "\", hashCc = 33L})" +
      "(v4 {compType = \"" + Constants.NO_TYPE + "\", hashCc = 42L})" +
      "(v5 {compType = \"School\", hashCc = 51L})]" +
      "(v1)-[e1:sameAs {aggSimValue = .9D}]->(v2)" +
      "(v2)-[e2:sameAs {aggSimValue = .6D}]->(v3)" +
      "(v3)-[e3:sameAs {aggSimValue = .7D}]->(v4)" +
      "(v4)-[e4:sameAs {aggSimValue = .4D}]->(v5)";

  /**
   * Error occured only sometimes, therefore 5 graphs are computed and asserted.
   * @throws Exception
   */
  @Test
  public void tgbEqualSimNoTypeOnLowCcIdVertexTest() throws Exception {
    GDLHandler firstHandler = new GDLHandler.Builder().buildFromString(TGB_EQUAL_SIM_NO_TYPE_LOW_CCID);
    Graph<Long, ObjectMap, ObjectMap> graph = MappingAnalysisExampleTest.createTestGraph(firstHandler);

    graph = TypeGroupBy.execute(graph, Constants.DEFAULT_VALUE, 100, env, null);

    for (int i=0; i < 5; i++) {
      assertEquals(0, graph.filterOnVertices(new SpecificCcIdFilter()).getVertices().count());
    }
  }

  /**
   * No type at all for all vertices, get lowest cc id for all vertices in result
   */
  @Test
  public void noTypeTest() throws Exception {
    GDLHandler firstHandler = new GDLHandler.Builder().buildFromString(NO_TYPE_STRING);
    Graph<Long, ObjectMap, ObjectMap> graph = MappingAnalysisExampleTest.createTestGraph(firstHandler);

    graph = TypeGroupBy.execute(graph, Constants.DEFAULT_VALUE, 100, env, null);

    graph.getVertices().print();
//    for (int i=0; i < 5; i++) {
//      assertEquals(0, graph.filterOnVertices(new SpecificCcIdFilter()).getVertices().count());
//    }
  }

  @Test
  public void typeGroupByTest() throws Exception {
    GDLHandler firstHandler = new GDLHandler.Builder().buildFromString(TGB_SIMPLE);
    Graph<Long, ObjectMap, ObjectMap> firstGraph = MappingAnalysisExampleTest.createTestGraph(firstHandler);

    firstGraph = TypeGroupBy.execute(firstGraph, Constants.DEFAULT_VALUE, 100, env, null);

    for (Vertex<Long, ObjectMap> vertex : firstGraph.getVertices().collect()) {
      ObjectMap value = vertex.getValue();
      if (vertex.getId() == 1 || vertex.getId() == 2) {
        assertTrue((value.containsKey(Constants.TMP_TYPE) && value.get(Constants.TMP_TYPE).equals("Mountain"))
            || value.get(Constants.COMP_TYPE).equals("Mountain"));
        assertEquals(value.get(Constants.HASH_CC), 23L);
      } else {
        assertEquals(value.get(Constants.HASH_CC), 42L);
        assertTrue(value.get(Constants.COMP_TYPE).equals("Settlement"));
      }
    }

    GDLHandler secondHandler = new GDLHandler.Builder().buildFromString(TGB_TRIPLE_UNKNOWN);
    Graph<Long, ObjectMap, ObjectMap> secondGraph = MappingAnalysisExampleTest.createTestGraph(secondHandler);

    secondGraph = TypeGroupBy.execute(secondGraph, Constants.DEFAULT_VALUE, 100, env, null);

    for (Vertex<Long, ObjectMap> vertex : secondGraph.getVertices().collect()) {
      ObjectMap value = vertex.getValue();
      if (vertex.getId() == 5) {
        assertTrue(value.get(Constants.COMP_TYPE).equals("School"));
        assertEquals(value.get(Constants.HASH_CC), 51L);
      } else {
        assertEquals(value.get(Constants.HASH_CC), 12L);
        assertTrue((value.containsKey(Constants.TMP_TYPE) && value.get(Constants.TMP_TYPE).equals("Settlement"))
            || value.get(Constants.COMP_TYPE).equals("Settlement"));
      }
    }
  }

  private static class SpecificCcIdFilter implements FilterFunction<Vertex<Long, ObjectMap>> {
    @Override
    public boolean filter(Vertex<Long, ObjectMap> vertex) throws Exception {
      return (long) vertex.getValue().get(Constants.HASH_CC) != -8401086609692859185L;
    }
  }
}