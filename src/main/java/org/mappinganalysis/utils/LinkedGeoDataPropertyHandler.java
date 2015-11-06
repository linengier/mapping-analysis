package org.mappinganalysis.utils;

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.hp.hpl.jena.rdf.model.*;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RiotNotFoundException;

import java.io.*;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Retrieve HTTP response with RDF content and handle LinkedGeoData properties.
 */
public class LinkedGeoDataPropertyHandler {
  private static final String GEO = "http://www.w3.org/2003/01/geo/wgs84_pos#";
  private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
  private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
  private static final String LGDO = "http://linkedgeodata.org/ontology/";
  private static final String LGD_ONTOLOGY = "http://linkedgeodata.org/ontology";
  private static final String[] LGD_ONT_VOCAB = {"AerialwayThing", "AerowayThing", "Amenity", "BarrierThing",
      "Boundary", "EmergencyThing", "HistoricThing", "Leisure", "LockThing", "ManMadeThing", "MilitaryThing",
      "Office", "Place", "PowerThing", "PublicTransportThing", "RailwayThing", "RouteThing", "Shop", "SportThing",
      "TourismThing", "NaturalThing"};


  /**
   * Method to retrieve properties for an URI, special case geo location can be triggered
   * @param uri URL to be parsed
   * @return HashMap with all propertiesMap
   * @throws IOException
   */
  public HashSet<String[]> getPropertiesForURI(String uri) throws Exception {
    HashSet<String[]> properties = new HashSet<>();

    try {
      Model model = RDFDataMgr.loadModel(uri);
      Resource resource = model.getResource(uri);

      Statement lat = resource.getProperty(model.getProperty(GEO + "lat"));
      Statement lon = resource.getProperty(model.getProperty(GEO + "long"));
      Statement ele = resource.getProperty(model.getProperty(LGDO + "ele"));

      // extra type management
      Property typeProp = model.getProperty(RDF + "type");
      StmtIterator typeIt = resource.listProperties(typeProp);
      Statement type = null;
      while( typeIt.hasNext() ) {
        Statement temp = typeIt.nextStatement();
        String value = temp.getResource().toString();
        if (value.startsWith(LGD_ONTOLOGY)) {
          String local = value.substring(LGD_ONTOLOGY.length() + 1);
          if (!Arrays.asList(LGD_ONT_VOCAB).contains(local)) {
            type = temp;
          }
        }
      }

      // extra label management
      Property labelProp = model.getProperty(RDFS + "label");
      StmtIterator it = resource.listProperties(labelProp);
      Statement label = null;
      Statement backup = null;
      while( it.hasNext() ) {
        Statement temp = it.nextStatement();
        if (temp.getLanguage().equals("")) {
          backup = temp;
        }
        if (temp.getLanguage().equals("en")) {
          label = temp;
        }
      }
      if (label == null && backup != null) {
        label = backup;
      }

//      label = resource.getProperty(model.getProperty(RDFS + "label"));
      System.out.println(label);

      if (lat != null) {
        properties.add(new String[]{lat.getPredicate().getURI(), lat.getLiteral().getValue().toString()});
      }
      if (lon != null) {
        properties.add(new String[]{lon.getPredicate().getURI(), lon.getLiteral().getValue().toString()});
      }
      if (ele != null) {
        properties.add(new String[]{ele.getPredicate().getURI(), ele.getLiteral().getValue().toString()});
      }
      if (label != null) {
        properties.add(new String[]{label.getPredicate().getURI(), label.getLiteral().getValue().toString()});
      }
      if (type != null) {
        properties.add(new String[]{type.getPredicate().getURI(), type.getResource().toString()});
      }
    } catch (RiotNotFoundException e) {
      // dont produce anything for not existing entries
      properties.add(new String[]{"", ""});
      return properties;
    } catch (HttpException ignored) {
      // ignore here, resource will hopefully be retried
    }

    return properties;
  }
}
