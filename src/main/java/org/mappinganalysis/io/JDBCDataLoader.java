package org.mappinganalysis.io;

import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.primitives.Doubles;
import org.apache.flink.api.common.functions.CoGroupFunction;
import org.apache.flink.api.common.functions.GroupReduceFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.io.jdbc.JDBCInputFormat;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.tuple.Tuple4;
import org.apache.flink.api.java.typeutils.TupleTypeInfo;
import org.apache.flink.graph.Edge;
import org.apache.flink.hadoop.shaded.com.google.common.collect.Lists;
import org.apache.flink.types.NullValue;
import org.apache.flink.util.Collector;
import org.apache.log4j.Logger;
import org.mappinganalysis.model.FlinkProperty;
import org.mappinganalysis.model.FlinkVertex;

import java.util.*;

/**
 * JDBC Data Loader for Flink
 */
public class JDBCDataLoader {
  private static final Logger LOG = Logger.getLogger(JDBCDataLoader.class);
  private final ExecutionEnvironment env;
  private final ResourceBundle prop;

  public JDBCDataLoader(ExecutionEnvironment env) {
    this.env = env;
    this.prop = ResourceBundle.getBundle("db", Locale.getDefault(), Thread.currentThread().getContextClassLoader());
  }

  /**
   * Parses and transforms the LDBC vertex files to {@link FlinkVertex} tuples.
   *
   * @return DataSet containing all vertices in the LDBC graph
   */
  @SuppressWarnings("unchecked")
  public DataSet<FlinkVertex> getVertices() throws Exception {
    LOG.info("Reading vertices");

    DataSet<Tuple3<Integer, String, String>> input
        = env.createInput(JDBCInputFormat.buildJDBCInputFormat()
            .setDrivername("com.mysql.jdbc.Driver")
            .setDBUrl(prop.getString("dbURLfull"))
            .setUsername(prop.getString("user"))
            .setPassword(prop.getString("pw"))
            .setQuery("select id, url, ontID_fk from concept")
            .finish(),
        new TupleTypeInfo(Tuple3.class, BasicTypeInfo.INT_TYPE_INFO, BasicTypeInfo.STRING_TYPE_INFO, BasicTypeInfo.STRING_TYPE_INFO)
    );

    DataSet<FlinkVertex> vertices = input.map(new BasicVertexCreator());

    vertices = vertices
        .coGroup(getProperties())
        .where(0)
        .equalTo(0)
        .with(new PropertyCoGroupFunction());

    return vertices;
  }

  /**
   * Parses and transforms the properties to {@link FlinkProperty} tuples.
   *
   * @return DataSet containing all properties from database.
   */
  @SuppressWarnings("unchecked")
  public DataSet<FlinkProperty> getProperties() {
    LOG.info("Reading properties");
    return env.createInput(JDBCInputFormat.buildJDBCInputFormat()
            .setDrivername("com.mysql.jdbc.Driver")
            .setDBUrl(prop.getString("dbURLfull"))
            .setUsername(prop.getString("user"))
            .setPassword(prop.getString("pw"))
            .setQuery("select id, attName, attValue, attValueType from concept_attributes")
            .finish(),
        new TupleTypeInfo(Tuple4.class, BasicTypeInfo.INT_TYPE_INFO,
            BasicTypeInfo.STRING_TYPE_INFO, BasicTypeInfo.STRING_TYPE_INFO,
            BasicTypeInfo.STRING_TYPE_INFO)
    ).map(new MapFunction<Tuple4<Integer, String, String, String>, FlinkProperty>() {
      @Override
      public FlinkProperty map(Tuple4<Integer, String, String, String> in) throws Exception {
        FlinkProperty property = new FlinkProperty();
        property.f0 = (long) in.f0;
        property.f1 = in.f1;
        property.f2 = in.f2;
        property.f3 = in.f3;
        return property;
      }
    }).withForwardedFields("f1;f2;f3");
  }

  /**
   * Parses and transforms the db edges to {@link Edge} tuples.
   *
   * @return DataSet containing all edges from the database table.
   */
  @SuppressWarnings("unchecked")
  public DataSet<Edge<Long, NullValue>> getEdges() {
    LOG.info("Reading edges");
    DataSet<Tuple2<Integer, Integer>> input
        = env.createInput(JDBCInputFormat.buildJDBCInputFormat()
            .setDrivername("com.mysql.jdbc.Driver")
            .setDBUrl(prop.getString("dbURLfull"))
            .setUsername(prop.getString("user"))
            .setPassword(prop.getString("pw"))
            .setQuery("select srcID, trgID from linksWithIDs")
            .finish(),
        new TupleTypeInfo(Tuple2.class, BasicTypeInfo.INT_TYPE_INFO, BasicTypeInfo.INT_TYPE_INFO)
    );

    return input.map(new FlinkEdgeCreator());
  }

  private static class PropertyCoGroupFunction implements CoGroupFunction<FlinkVertex,
      FlinkProperty, FlinkVertex> {
    public void coGroup(Iterable<FlinkVertex> vertices, Iterable<FlinkProperty> properties,
        Collector<FlinkVertex> out) throws Exception {
      FlinkVertex vertex = Iterables.get(vertices, 0);
      Map<String, Object> vertexProperties = vertex.getProperties();

      for (FlinkProperty property : properties) {
        Object value = property.getPropertyValue();
        String key = property.getPropertyKey();
        if (property.getPropertyType().equals("double")) {
          value = Doubles.tryParse(value.toString());
        } else if (property.getPropertyType().equals("string")){
          value = value.toString();
        }
        if (vertexProperties.containsKey(key)) {
          // handle multi valued properties
          Object oldValue = vertexProperties.get(key);
          if (oldValue instanceof List) {
            // add to existing list, either value as list or single value
            List<Object> values = Lists.newArrayList((List<Object>) oldValue);
            values.add(value);
            vertexProperties.put(key, values);
          } else {
            vertexProperties.put(key, Lists.newArrayList(oldValue, value));
          }
        } else {
          vertexProperties.put(key, value);
        }
      }
      vertex.setProperties(vertexProperties);
      out.collect(vertex);
    }
  }

  private static class FlinkEdgeCreator implements MapFunction<Tuple2<Integer, Integer>, Edge<Long, NullValue>> {

    private final Edge<Long, NullValue> reuseEdge;

    public FlinkEdgeCreator() {
      reuseEdge = new Edge<>();
    }

    public Edge<Long, NullValue> map(Tuple2<Integer, Integer> tuple) throws Exception {
      reuseEdge.setSource((long) tuple.f0);
      reuseEdge.setTarget((long) tuple.f1);
      reuseEdge.setValue(NullValue.getInstance());
      return reuseEdge;
    }
  }

  private class VertexPropertyCreator implements MapFunction<Tuple4<Integer, String, String, String>,
      FlinkVertex> {

    private final FlinkVertex reuseVertex;

    private VertexPropertyCreator() {
      reuseVertex = new FlinkVertex();
    }

    @SuppressWarnings("ConstantConditions")
    public FlinkVertex map(Tuple4<Integer, String, String, String> tuple) throws Exception {
      reuseVertex.setId((long) tuple.f0);
      Map<String, Object> propMap = Maps.newHashMap();
      switch (tuple.f3) {
        case "string":
          propMap.put(tuple.f1, tuple.f2);
          break;
        case "double":
          propMap.put(tuple.f1, Doubles.tryParse(tuple.f2));
          break;
      }
      reuseVertex.setProperties(propMap);
      return reuseVertex;
    }
  }

  private class BasicVertexCreator implements MapFunction<Tuple3<Integer, String, String>, FlinkVertex> {

    private final FlinkVertex reuseVertex;

    private BasicVertexCreator() {
      reuseVertex = new FlinkVertex();
    }

    public FlinkVertex map(Tuple3<Integer, String, String> tuple) throws Exception {
      reuseVertex.setId((long) tuple.f0);
      Map<String, Object> propMap = Maps.newHashMap();
      propMap.put("url", tuple.f1);
      propMap.put("ontology", tuple.f2);
      reuseVertex.setValue(propMap);
      return reuseVertex;
    }
  }
}
