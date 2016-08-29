package org.mappinganalysis.io.impl.json;

import org.apache.flink.api.java.DataSet;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.graph.Graph;
import org.apache.flink.graph.Vertex;
import org.mappinganalysis.model.ObjectMap;

/**
 * Write Gelly graphs or vertices to JSON files
 */
public class JSONDataSink {

  private final String vertexPath;
  private final String edgePath;

  public JSONDataSink(String vertexPath, String edgePath) {
    this.vertexPath = vertexPath;
    this.edgePath = edgePath;
  }

  public JSONDataSink(String vertexPath) {
    this.vertexPath = vertexPath;
    this.edgePath = null;
  }

  /**
   * TODO What happens with empty edge collection?
   */
  public <EV> void writeGraph(Graph<Long, ObjectMap, EV> graph) {
    graph.getVertices()
        .writeAsFormattedText(vertexPath,
            FileSystem.WriteMode.OVERWRITE,
            new VertexToJSONFormatter<>());
    graph.getEdges()
        .writeAsFormattedText(edgePath,
            FileSystem.WriteMode.OVERWRITE,
            new EdgeToJSONFormatter<>());
  }

  public void writeVertices(DataSet<Vertex<Long, ObjectMap>> vertices) {
    vertices.writeAsFormattedText(vertexPath,
        FileSystem.WriteMode.OVERWRITE,
        new VertexToJSONFormatter<>());
  }
}