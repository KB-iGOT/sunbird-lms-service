package org.sunbird.helper;

import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.junit.Assert;
import org.junit.Test;
import org.sunbird.common.ElasticSearchHelper;
import org.sunbird.dto.SearchDTO;
import org.sunbird.keys.JsonKey;

public class ElasticSearchMappingTest {

  @Test
  public void testcreateMapping() {
    String mapping = ElasticSearchMapping.createMapping();
    Assert.assertNotNull(mapping);
  }

  @Test
  public void testOrFiltersQueryGeneration() {
    java.util.Map<String, Object> searchQueryMap = new java.util.HashMap<>();
    java.util.Map<String, Object> orFilters = new java.util.HashMap<>();
    orFilters.put("isMdo", true);
    orFilters.put("isAutonomousNgo", false);
    searchQueryMap.put(JsonKey.OR_FILTERS, orFilters);

    SearchDTO searchDto = ElasticSearchHelper.createSearchDTO(searchQueryMap);
    Assert.assertNotNull(searchDto);
    Assert.assertTrue(searchDto.getAdditionalProperties().containsKey(JsonKey.OR_FILTERS));

    BoolQueryBuilder query = QueryBuilders.boolQuery();
    java.util.Map<String, Float> constraintsMap = new java.util.HashMap<>();

    for (java.util.Map.Entry<String, Object> entry : searchDto.getAdditionalProperties().entrySet()) {
      ElasticSearchHelper.addAdditionalProperties(query, entry, constraintsMap);
    }

    String queryStr = query.toString();
    Assert.assertTrue(queryStr.contains("isMdo.raw"));
    Assert.assertTrue(queryStr.contains("isAutonomousNgo.raw"));
    Assert.assertTrue(queryStr.contains("minimum_should_match"));
  }
}
