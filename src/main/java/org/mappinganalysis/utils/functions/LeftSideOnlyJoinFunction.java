package org.mappinganalysis.utils.functions;

import org.apache.flink.api.common.functions.FlatJoinFunction;
import org.apache.flink.graph.Edge;
import org.apache.flink.types.NullValue;
import org.apache.flink.util.Collector;

/**
 * Return all elements which have a match partner on the right dataset side.
 * @param <T>
 * @param <O>
 */
public class LeftSideOnlyJoinFunction<T, O> implements FlatJoinFunction<T, O, T> {
  @Override
  public void join(T left, O right, Collector<T> collector) throws Exception {
    if (right == null) {
      collector.collect(left);
    }
  }
}
