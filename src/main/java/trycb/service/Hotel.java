/**
 * Copyright (C) 2021 Couchbase, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALING
 * IN THE SOFTWARE.
 */

package trycb.service;

import static com.couchbase.client.java.kv.LookupInSpec.get;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.stereotype.Service;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.Scope;
import com.couchbase.client.java.kv.LookupInResult;
import com.couchbase.client.java.search.SearchOptions;
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.queries.ConjunctionQuery;
import com.couchbase.client.java.search.result.SearchResult;
import com.couchbase.client.java.search.result.SearchRow;

import trycb.config.HotelRepository;
import trycb.model.Result;

@Service
public class Hotel {

  private static final Logger LOGGER = LoggerFactory.getLogger(Hotel.class);

  private HotelRepository hotelRepository;
  private Cluster cluster;
  private Bucket bucket;

  @Autowired
  public Hotel(HotelRepository hotelRepository) {
    this.hotelRepository = hotelRepository;
    // use the Java SDK cluster and bucket objects directly.
    this.cluster = hotelRepository.getOperations().getCouchbaseClientFactory().getCluster();
    this.bucket = hotelRepository.getOperations().getCouchbaseClientFactory().getBucket();
  }

  /**
   * Search for a hotel in a particular location.
   */
  public Result<List<Map<String, Object>>> findHotels(final String location, final String description) {
    ConjunctionQuery fts = SearchQuery.conjuncts(SearchQuery.term("hotel").field("type"));

    if (location != null && !location.isEmpty() && !"*".equals(location)) {
      fts.and(SearchQuery.disjuncts(SearchQuery.matchPhrase(location).field("country"),
          SearchQuery.matchPhrase(location).field("city"), SearchQuery.matchPhrase(location).field("state"),
          SearchQuery.matchPhrase(location).field("address")));
    }

    if (description != null && !description.isEmpty() && !"*".equals(description)) {
      fts.and(SearchQuery.disjuncts(SearchQuery.matchPhrase(description).field("description"),
          SearchQuery.matchPhrase(description).field("name")));
    }

    logQuery(fts.export().toString());
    SearchOptions opts = SearchOptions.searchOptions().limit(100);
    SearchResult result = cluster.searchQuery("hotels-index", fts, opts);

    String queryType = "FTS search - scoped to: inventory.hotel within fields country, city, state, address, name, description";
    // logQuery(result.toString());
    return Result.of(extractResultOrThrow(result), queryType);
  }

  /**
   * Search for an hotel.
   */
  public Result<List<Map<String, Object>>> findHotels(final String description) {
    return findHotels("*", description);
  }

  /**
   * Find all hotels.
   */
  public Result<List<Map<String, Object>>> findAllHotels() {
    return findHotels("*", "*");
  }

  /**
   * Extract a FTS result or throw if there is an issue.
   */
  private List<Map<String, Object>> extractResultOrThrow(SearchResult result) {
    if (result.metaData().metrics().errorPartitionCount() > 0) {
      LOGGER.warn("Query returned with errors: " + result.metaData().errors());
      throw new DataRetrievalFailureException("Query error: " + result.metaData().errors());
    }

    List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
    for (SearchRow row : result.rows()) {

      LookupInResult res;
      try {
        Scope scope = bucket.scope("inventory");
        Collection collection = scope.collection("hotel");
        res = collection.lookupIn(row.id(),
            Arrays.asList(get("country"), get("city"), get("state"), get("address"), get("name"), get("description")));
      } catch (DocumentNotFoundException ex) {
        continue;
      }

      Map<String, Object> map = new HashMap<String, Object>();

      String country = res.contentAs(0, String.class);
      String city = res.contentAs(1, String.class);
      String state = res.contentAs(2, String.class);
      String address = res.contentAs(3, String.class);

      StringBuilder fullAddr = new StringBuilder();
      if (address != null)
        fullAddr.append(address).append(", ");
      if (city != null)
        fullAddr.append(city).append(", ");
      if (state != null)
        fullAddr.append(state).append(", ");
      if (country != null)
        fullAddr.append(country);

      if (fullAddr.length() > 2 && fullAddr.charAt(fullAddr.length() - 2) == ',')
        fullAddr.delete(fullAddr.length() - 2, fullAddr.length() - 1);

      map.put("name", res.contentAs(4, String.class));
      map.put("description", res.contentAs(5, String.class));
      map.put("address", fullAddr.toString());

      content.add(map);
    }
    return content;
  }

  /**
   * Helper method to log the executing query.
   */
  private static void logQuery(String query) {
    LOGGER.info("Executing FTS Query: {}", query);
  }

}
