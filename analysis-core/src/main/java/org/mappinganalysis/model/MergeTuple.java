package org.mappinganalysis.model;

import org.apache.flink.api.java.tuple.Tuple8;
import org.apache.flink.api.java.tuple.Tuple9;
import org.apache.flink.runtime.util.LongArrayList;
import org.mappinganalysis.model.api.*;
import org.mappinganalysis.util.AbstractionUtils;

import java.util.Set;

/**
 * MergeTuple consists of
 * 0. vertex id
 * 1. label
 * 2. lat
 * 3. lon
 * 4. type (as int)
 * 5. sources (as int)
 * 6. clustered elements list
 * 7. blocking label
 * 8. activity flag
 */
public class MergeTuple
    extends Tuple9<Long, String, Double, Double, Integer, Integer, LongSet, String, Boolean>
    implements ClusteredEntity {
  public MergeTuple() {
    this.f6 = new LongSet();
    this.f2 = 1000D;
    this.f3 = 1000D;
    this.f8 = true;
  }

  public MergeTuple(MergeTuple tuple) {
    this.f0 = tuple.f0;
    this.f1 = tuple.f1;
    this.f2 = tuple.f2;
    this.f3 = tuple.f3;
    this.f4 = tuple.f4;
    this.f5 = tuple.f5;
    this.f6 = tuple.f6;
    this.f7 = tuple.f7;
  }

  public MergeTuple(Long id, boolean isActive) {
    this.f0 = id;
    this.f1 = "";
    this.f2 = 1000D;
    this.f3 = 1000D;
    this.f4 = 0;
    this.f5 = 0;
    this.f6 = new LongSet(id);
    this.f7 = "";
    this.f8 = isActive;
  }

  @Override
  public Long getId() {
    return f0;
  }

  @Override
  public void setId(Long vertexId) {
    f0 = vertexId;
  }

  @Override
  public String getLabel() {
    return f1;
  }

  @Override
  public void setLabel(String label) {
    f1 = label;
  }

  public void setGeoProperties(MergeTuple input) {
    f2 = input.getLatitude();
    f3 = input.getLongitude();
  }

  @Override
  public Double getLatitude() {
    return f2;
  }

  @Override
  public Double getLongitude() {
    return f3;
  }

  @Override
  public void setLatitude(Double latitude) {
    f2 = latitude;
  }

  @Override
  public void setLongitude(Double longitude) {
    f3 = longitude;
  }

  @Override
  public Integer getIntTypes() {
    return f4;
  }

  @Override
  public void setIntTypes(Integer types) {
    f4 = types;
  }

  @Override
  public Integer getIntSources() {
    return f5;
  }

  @Override
  public void setIntSources(Integer intSources) {
    f5 = intSources;
  }

  public Set<Long> getClusteredElements() {
    return f6;
  }

  public void addClusteredElements(Set<Long> elements) {
    f6.addAll(elements);
  }

  public Integer size() {
    return AbstractionUtils.getSourceCount(f5);
  }

  public void setBlockingLabel(String label) {
    f7 = label;
  }

  public String getBlockingLabel() {
    return f7;
  }

  public boolean isActive() {
    return f8;
  }

  public void setActive(Boolean value) {
    f8 = value;
  }
}
