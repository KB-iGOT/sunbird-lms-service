package org.sunbird.common;

import akka.util.Timeout;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.logstash.logback.encoder.org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.index.query.*;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms.Bucket;
import org.elasticsearch.search.sort.SortOrder;
import org.sunbird.dto.SearchDTO;
import org.sunbird.keys.JsonKey;
import org.sunbird.logging.LoggerUtil;
import org.sunbird.util.ProjectUtil;
import scala.concurrent.Await;
import scala.concurrent.Future;

/**
 * This class will provide all required operation for elastic search.
 *
 * @author arvind
 * @author Manzarul
 * @author mayank:github.com/iostream04
 */
public class ElasticSearchHelper {
  private static final LoggerUtil logger = new LoggerUtil(ElasticSearchHelper.class);
  public static final String LTE = "<=";
  public static final String LT = "<";
  public static final String GTE = ">=";
  public static final String GT = ">";
  public static final String ASC_ORDER = "ASC";
  public static final String STARTS_WITH = "startsWith";
  public static final String ENDS_WITH = "endsWith";
  public static final String RAW_APPEND = ".raw";
  public static final int WAIT_TIME = 5;
  public static Timeout timeout = new Timeout(WAIT_TIME, TimeUnit.SECONDS);
  public static final List<String> upsertResults =
      new ArrayList<>(Arrays.asList("CREATED", "UPDATED", "NOOP"));
  private static final String _DOC = "_doc";
  private static final int allowedQueryStringLength = NumberUtils.toInt(
            ProjectUtil.getConfigValue(JsonKey.ALLOWED_SEARCH_QUERY_STRING),
            JsonKey.ALLOWED_SEARCH_QUERY_STRING_DEFAULT
    );

  private ElasticSearchHelper() {}

  /**
   * This method will return the object after getting complete future.
   *
   * @param future
   * @return Object which future inherits
   */
  @SuppressWarnings("unchecked")
  public static Object getResponseFromFuture(Future future) {
    try {
      Object result = Await.result(future, timeout.duration());
      return result;
    } catch (Exception e) {
      logger.error("getResponseFromFuture: error occured ", e);
    }
    return null;
  }

  /**
   * This method adds aggregations to the incoming SearchRequestBuilder object
   *
   * @param searchRequestBuilder which will be updated with facets if any present
   * @param facets Facets provide aggregated data based on a search query
   * @return SearchRequestBuilder
   */
  public static SearchRequestBuilder addAggregations(
      SearchRequestBuilder searchRequestBuilder, List<Map<String, String>> facets) {
    long startTime = System.currentTimeMillis();
    logger.debug("addAggregations: method started at ==" + startTime);
    if (facets != null && !facets.isEmpty()) {
      Map<String, String> map = facets.get(0);
      if (!MapUtils.isEmpty(map)) {
        for (Map.Entry<String, String> entry : map.entrySet()) {

          String key = entry.getKey();
          String value = entry.getValue();
          if (JsonKey.DATE_HISTOGRAM.equalsIgnoreCase(value)) {
            searchRequestBuilder.addAggregation(
                AggregationBuilders.dateHistogram(key)
                    .field(key + RAW_APPEND)
                    .dateHistogramInterval(DateHistogramInterval.days(1)));

          } else if (null == value) {
            searchRequestBuilder.addAggregation(
                AggregationBuilders.terms(key).field(key + RAW_APPEND));
          }
        }
      }
      long elapsedTime = calculateEndTime(startTime);
      logger.debug(
          "ElasticSearchHelper:addAggregations method end =="
              + " ,Total time elapsed = "
              + elapsedTime);
    }

    return searchRequestBuilder;
  }

  /**
   * This method returns any constraints defined in searchDto object
   *
   * @param searchDTO with constraints
   * @return Map for constraints present in serachDTO
   */
  public static Map<String, Float> getConstraints(SearchDTO searchDTO) {
    if (null != searchDTO.getSoftConstraints() && !searchDTO.getSoftConstraints().isEmpty()) {
      return searchDTO
          .getSoftConstraints()
          .entrySet()
          .stream()
          .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().floatValue()));
    }
    return Collections.emptyMap();
  }

  /**
   * This method return SearchRequestBuilder for transport client
   *
   * @param client transport client instance
   * @param index to be checkout
   * @return SearchRequestBuilder for a provided request
   */
  public static SearchRequestBuilder getTransportSearchBuilder(
      TransportClient client, String[] index) {
    return client.prepareSearch().setIndices(index).setTypes(_DOC);
  }

  /**
   * Method to add the additional search query like range query , exists - not exist filter etc.
   *
   * @param query query which will be updated
   * @param entry which will have key to be search and respective values
   * @param constraintsMap constraints on key and values
   */
  @SuppressWarnings("unchecked")
  public static void addAdditionalProperties(
      BoolQueryBuilder query, Entry<String, Object> entry, Map<String, Float> constraintsMap) {
    long startTime = System.currentTimeMillis();
    logger.debug("ElasticSearchHelper:addAdditionalProperties: method started at ==" + startTime);
    String key = entry.getKey();
    if (JsonKey.FILTERS.equalsIgnoreCase(key)) {

      Map<String, Object> filters = (Map<String, Object>) entry.getValue();
      for (Map.Entry<String, Object> en : filters.entrySet()) {
        query = createFilterESOpperation(en, query, constraintsMap);
      }
    } else if (JsonKey.OR_FILTERS.equalsIgnoreCase(key)) {
      Map<String, Object> orFilters = (Map<String, Object>) entry.getValue();
      query.must(createEsORFilterQuery(orFilters));
    } else if (JsonKey.EXISTS.equalsIgnoreCase(key) || JsonKey.NOT_EXISTS.equalsIgnoreCase(key)) {
      query = createESOpperation(entry, query, constraintsMap);
    } else if (JsonKey.NESTED_EXISTS.equalsIgnoreCase(key)
        || JsonKey.NESTED_NOT_EXISTS.equalsIgnoreCase(key)) {
      query = createNestedESOpperation(entry, query, constraintsMap);
    } else if (JsonKey.NESTED_KEY_FILTER.equalsIgnoreCase(key)) {
      Map<String, Object> nestedFilters = (Map<String, Object>) entry.getValue();
      for (Map.Entry<String, Object> en : nestedFilters.entrySet()) {
        query = createNestedFilterESOpperation(en, query, constraintsMap);
      }
    } else if (JsonKey.WILDCARD_KEY.equalsIgnoreCase(key)) {
        query = createWildcardQuery(entry, query);
    } else if (JsonKey.ADDITIONAL_FILTER.equalsIgnoreCase(key)) {
        Map<String, Object> additionalFilter = (Map<String, Object>) entry.getValue();
        for (Map.Entry<String, Object> e : additionalFilter.entrySet()) {
            query = query.must(createTermsQuery(e.getKey() + RAW_APPEND, Arrays.asList(e.getValue()), constraintsMap.get(key)));
        }
    } else if (JsonKey.ADVANCED_FILTERS.equalsIgnoreCase(key)) {

      Map<String, Object> advancedFilters =
              (Map<String, Object>) entry.getValue();

      List<String> hierarchyLevels =
              (List<String>) advancedFilters.get("includeHierarchyLevels");

      if (hierarchyLevels != null && !hierarchyLevels.isEmpty()) {

        BoolQueryBuilder hierarchyOrQuery = QueryBuilders.boolQuery();

        for (String level : hierarchyLevels) {

          BoolQueryBuilder perLevelAndQuery = QueryBuilders.boolQuery();

          //IMPORTANT: hierarchyLevel.raw is lowercased in index
          perLevelAndQuery.must(
                  createTermsQuery(
                          "hierarchyLevel" + RAW_APPEND,
                          Arrays.asList(level.toLowerCase()),
                          constraintsMap.get(key)
                  )
          );

          if ("levelZero".equalsIgnoreCase(level)) {

            Object sbOrgType = advancedFilters.get("ministryOrStateType");

            if (sbOrgType != null) {
              perLevelAndQuery.must(
                      createTermsQuery(
                              "sbOrgType" + RAW_APPEND,
                              Arrays.asList(sbOrgType),
                              constraintsMap.get(key)
                      )
              );
            }

          } else {
            Object ministryOrStateType = advancedFilters.get("ministryOrStateType");

            if (ministryOrStateType != null) {
              perLevelAndQuery.must(
                      createTermsQuery(
                              "ministryOrStateType" + RAW_APPEND,
                              Arrays.asList(ministryOrStateType),
                              constraintsMap.get(key)
                      )
              );
            }
          }

          hierarchyOrQuery.should(perLevelAndQuery);
        }

        hierarchyOrQuery.minimumShouldMatch(1);
        query = query.must(hierarchyOrQuery);
      }
    } else if (JsonKey.NESTED_KEY_FILTER_GROUPED.equalsIgnoreCase(key)) {
      List<Map<String, Object>> groupedNestedFilters = (List<Map<String, Object>>) entry.getValue();
      for (Map<String, Object> groupedFilter : groupedNestedFilters) {
        String path = (String) groupedFilter.get(JsonKey.PATH);
        Map<String, Object> filters = (Map<String, Object>) groupedFilter.get(JsonKey.FILTERS);
        query = createGroupedNestedFilterQuery(path, filters, query, constraintsMap);
      }
    }
    long elapsedTime = calculateEndTime(startTime);
    logger.debug(
        "ElasticSearchHelper:addAdditionalProperties: method end =="
            + " ,Total time elapsed = "
            + elapsedTime);
  }

  /**
   * Method to create CommonTermQuery , multimatch and Range Query.
   *
   * @param entry which contains key for search and respective values
   * @param query Object which will be updated
   * @param constraintsMap constraints for key and values
   * @return BoolQueryBuilder
   */
  @SuppressWarnings("unchecked")
  private static BoolQueryBuilder createFilterESOpperation(
      Entry<String, Object> entry, BoolQueryBuilder query, Map<String, Float> constraintsMap) {
    String key = entry.getKey();
    Object val = entry.getValue();
    if (val instanceof List && val != null) {
      query = getTermQueryFromList(val, key, query, constraintsMap);
    } else if (val instanceof Map) {
      if (key.equalsIgnoreCase(JsonKey.ES_OR_OPERATION)) {
        query.must(createEsORFilterQuery((Map<String, Object>) val));
      } else {
        query = getTermQueryFromMap(val, key, query, constraintsMap);
      }
    } else if (val instanceof String) {
      query.must(
          createTermQuery(key + RAW_APPEND, ((String) val).toLowerCase(), constraintsMap.get(key)));
    } else {
      query.must(createTermQuery(key + RAW_APPEND, val, constraintsMap.get(key)));
    }
    return query;
  }

  /**
   * Method to create CommonTermQuery , multimatch and Range Query.
   *
   * @param entry which contains key for search and respective values
   * @param query Object which will be updated
   * @param constraintsMap constraints for key and values
   * @return BoolQueryBuilder
   */
  @SuppressWarnings("unchecked")
  private static BoolQueryBuilder createNestedFilterESOpperation(
      Entry<String, Object> entry, BoolQueryBuilder query, Map<String, Float> constraintsMap) {
    String key = entry.getKey();
    Object val = entry.getValue();
    String path = key.split("\\.")[0];
    if (val instanceof List && CollectionUtils.isNotEmpty((List) val)) {
      if (((List) val).get(0) instanceof String) {
        ((List<String>) val).replaceAll(String::toLowerCase);
        query.must(
            QueryBuilders.nestedQuery(
                path,
                createTermsQuery(key + RAW_APPEND, (List<String>) val, constraintsMap.get(key)),
                ScoreMode.None));
      } else {
        query.must(
            QueryBuilders.nestedQuery(
                path, createTermsQuery(key, (List) val, constraintsMap.get(key)), ScoreMode.None));
      }
    } else if (val instanceof Map) {
      query = getNestedTermQueryFromMap(val, key, path, query, constraintsMap);
    } else if (val instanceof String) {
      query.must(
          QueryBuilders.nestedQuery(
              path,
              createTermQuery(
                  key + RAW_APPEND, ((String) val).toLowerCase(), constraintsMap.get(key)),
              ScoreMode.None));
    } else {
      query.must(
          QueryBuilders.nestedQuery(
              path,
              createTermQuery(key + RAW_APPEND, val, constraintsMap.get(key)),
              ScoreMode.None));
    }
    return query;
  }

  /**
   * Method to create grouped nested filter query where all filter conditions must match
   * within the same nested document
   *
   * @param path the nested path
   * @param filters map of filters to apply within the nested context
   * @param query Object which will be updated
   * @param constraintsMap constraints for key and values
   * @return BoolQueryBuilder
   */
  @SuppressWarnings("unchecked")
  private static BoolQueryBuilder createGroupedNestedFilterQuery(
      String path,
      Map<String, Object> filters,
      BoolQueryBuilder query,
      Map<String, Float> constraintsMap) {

    // Create a nested bool query to group all conditions
    BoolQueryBuilder nestedBoolQuery = QueryBuilders.boolQuery();

    for (Map.Entry<String, Object> filterEntry : filters.entrySet()) {
      String key = filterEntry.getKey();
      Object val = filterEntry.getValue();

      // Check if key already ends with .keyword (exact match field) or is a nested path field
      boolean isKeywordField = key.endsWith(".keyword");
      // Don't append .raw for nested path fields (e.g., orgCustomFields.orgId)
      // Only append .raw for top-level fields that don't have .keyword
      String finalKey;
      if (isKeywordField || key.startsWith(path + ".")) {
        // For .keyword fields or nested path fields, use as-is
        finalKey = key;
      } else {
        // For other fields, append .raw
        finalKey = key + RAW_APPEND;
      }

      if (val instanceof List && CollectionUtils.isNotEmpty((List) val)) {
        if (((List) val).get(0) instanceof String) {
          List<String> listVal = (List<String>) val;
          if (!isKeywordField) {
            listVal.replaceAll(String::toLowerCase);
          }
          nestedBoolQuery.must(createTermsQuery(finalKey, listVal, constraintsMap.get(key)));
        } else {
          nestedBoolQuery.must(createTermsQuery(finalKey, (List) val, constraintsMap.get(key)));
        }
      } else if (val instanceof Map) {
        Map<String, Object> value = (Map<String, Object>) val;
        Map<String, Object> rangeOperation = new HashMap<>();
        Map<String, Object> lexicalOperation = new HashMap<>();
        for (Map.Entry<String, Object> it : value.entrySet()) {
          String operation = it.getKey();
          if (operation.startsWith(LT) || operation.startsWith(GT)) {
            rangeOperation.put(operation, it.getValue());
          } else if (operation.startsWith(STARTS_WITH) || operation.startsWith(ENDS_WITH)) {
            lexicalOperation.put(operation, it.getValue());
          }
        }
        if (!rangeOperation.isEmpty()) {
          nestedBoolQuery.must(createRangeQuery(finalKey, rangeOperation, constraintsMap.get(key)));
        }
        if (!lexicalOperation.isEmpty()) {
          nestedBoolQuery.must(createLexicalQuery(finalKey, lexicalOperation, constraintsMap.get(key)));
        }
      } else if (val instanceof String) {
        String stringVal = (String) val;
        // Don't lowercase for .keyword fields (exact match)
        if (!isKeywordField) {
          stringVal = stringVal.toLowerCase();
        }
        nestedBoolQuery.must(createTermQuery(finalKey, stringVal, constraintsMap.get(key)));
      } else {
        nestedBoolQuery.must(createTermQuery(finalKey, val, constraintsMap.get(key)));
      }
    }

    // Wrap all conditions in a single nested query
    query.must(QueryBuilders.nestedQuery(path, nestedBoolQuery, ScoreMode.None));

    return query;
  }

  /**
   * This method returns termQuery if any present in map provided
   *
   * @param key for search in termquery
   * @param val value of the key to be searched
   * @param query which will be updated according to key , value and constraints
   * @param constraintsMap for setting any constraints on values for the specified key
   * @return BoolQueryBuilder
   */
  private static BoolQueryBuilder getTermQueryFromMap(
      Object val, String key, BoolQueryBuilder query, Map<String, Float> constraintsMap) {
    Map<String, Object> value = (Map<String, Object>) val;
    Map<String, Object> rangeOperation = new HashMap<>();
    Map<String, Object> lexicalOperation = new HashMap<>();
    for (Map.Entry<String, Object> it : value.entrySet()) {
      String operation = it.getKey();
      if (operation.startsWith(LT) || operation.startsWith(GT)) {
        rangeOperation.put(operation, it.getValue());
      } else if (operation.startsWith(STARTS_WITH) || operation.startsWith(ENDS_WITH)) {
        lexicalOperation.put(operation, it.getValue());
      }
    }
    if (!(rangeOperation.isEmpty())) {
      query.must(createRangeQuery(key, rangeOperation, constraintsMap.get(key)));
    }
    if (!(lexicalOperation.isEmpty())) {
      query.must(createLexicalQuery(key, lexicalOperation, constraintsMap.get(key)));
    }

    return query;
  }

  private static BoolQueryBuilder createEsORFilterQuery(Map<String, Object> orFilters) {
    BoolQueryBuilder query = new BoolQueryBuilder();
    for (Map.Entry<String, Object> mp : orFilters.entrySet()) {
      Object valObj = mp.getValue();
      String valStr = (valObj == null) ? "" : String.valueOf(valObj).toLowerCase();
      query.should(
          QueryBuilders.termQuery(
              mp.getKey() + RAW_APPEND, valStr));
    }
    query.minimumShouldMatch(1);
    return query;
  }

  /**
   * This method returns termQuery if any present in map provided
   *
   * @param key for search in termquery
   * @param val value of the key to be searched
   * @param query which will be updated according to key , value and constraints
   * @param constraintsMap for setting any constraints on values for the specified key
   * @return BoolQueryBuilder
   */
  private static BoolQueryBuilder getNestedTermQueryFromMap(
      Object val,
      String key,
      String path,
      BoolQueryBuilder query,
      Map<String, Float> constraintsMap) {
    Map<String, Object> value = (Map<String, Object>) val;
    Map<String, Object> rangeOperation = new HashMap<>();
    Map<String, Object> lexicalOperation = new HashMap<>();
    for (Map.Entry<String, Object> it : value.entrySet()) {
      String operation = it.getKey();
      if (operation.startsWith(LT) || operation.startsWith(GT)) {
        rangeOperation.put(operation, it.getValue());
      } else if (operation.startsWith(STARTS_WITH) || operation.startsWith(ENDS_WITH)) {
        lexicalOperation.put(operation, it.getValue());
      }
    }
    if (!(rangeOperation.isEmpty())) {
      query.must(
          QueryBuilders.nestedQuery(
              path,
              createRangeQuery(key, rangeOperation, constraintsMap.get(key)),
              ScoreMode.None));
    }
    if (!(lexicalOperation.isEmpty())) {
      query.must(
          QueryBuilders.nestedQuery(
              path,
              createLexicalQuery(key, lexicalOperation, constraintsMap.get(key)),
              ScoreMode.None));
    }
    return query;
  }

  /**
   * This method returns termQuery if any present in List provided
   *
   * @param key for search in termquery
   * @param val value of the key to be searched
   * @param query which will be updated according to key , value and constraints
   * @param constraintsMap for setting any constraints on values for the specified key
   * @return BoolQueryBuilder
   */
  private static BoolQueryBuilder getTermQueryFromList(
      Object val, String key, BoolQueryBuilder query, Map<String, Float> constraintsMap) {
    if (!((List) val).isEmpty()) {
      if (((List) val).get(0) instanceof String) {
        ((List<String>) val).replaceAll(String::toLowerCase);
        query.must(createTermsQuery(key + RAW_APPEND, (List<String>) val, constraintsMap.get(key)));
      } else {
        query.must(createTermsQuery(key, (List) val, constraintsMap.get(key)));
      }
    }
    return query;
  }

  /** Method to create EXISTS and NOT EXIST FILTER QUERY . */
  /**
   * @param entry contains operations and keys for filter
   * @param query do get updated with provided operations
   * @param constraintsMap to set ant constraints on keys for filter
   * @return
   */
  @SuppressWarnings("unchecked")
  private static BoolQueryBuilder createESOpperation(
      Entry<String, Object> entry, BoolQueryBuilder query, Map<String, Float> constraintsMap) {

    String operation = entry.getKey();
    if (entry.getValue() != null && entry.getValue() instanceof List) {
      List<String> existsList = (List<String>) entry.getValue();

      if (JsonKey.EXISTS.equalsIgnoreCase(operation)) {
        for (String name : existsList) {
          query.must(createExistQuery(name, constraintsMap.get(name)));
        }
      } else if (JsonKey.NOT_EXISTS.equalsIgnoreCase(operation)) {
        for (String name : existsList) {
          query.mustNot(createExistQuery(name, constraintsMap.get(name)));
        }
      }
    }
    return query;
  }

  /** Method to create EXISTS and NOT EXIST FILTER QUERY . */
  /**
   * @param entry contains operations and keys for filter
   * @param query do get updated with provided operations
   * @param constraintsMap to set ant constraints on keys for filter
   * @return
   */
  @SuppressWarnings("unchecked")
  private static BoolQueryBuilder createNestedESOpperation(
      Entry<String, Object> entry, BoolQueryBuilder query, Map<String, Float> constraintsMap) {

    String operation = entry.getKey();
    if (entry.getValue() != null && entry.getValue() instanceof Map) {
      Map<String, String> existsMap = (Map<String, String>) entry.getValue();

      if (JsonKey.NESTED_EXISTS.equalsIgnoreCase(operation)) {
        for (Map.Entry<String, String> nameByPath : existsMap.entrySet()) {
          query.must(
              QueryBuilders.nestedQuery(
                  nameByPath.getValue(),
                  createExistQuery(nameByPath.getKey(), constraintsMap.get(nameByPath.getKey())),
                  ScoreMode.None));
        }
      } else if (JsonKey.NESTED_NOT_EXISTS.equalsIgnoreCase(operation)) {
        for (Map.Entry<String, String> nameByPath : existsMap.entrySet()) {
          query.mustNot(
              QueryBuilders.nestedQuery(
                  nameByPath.getValue(),
                  createExistQuery(nameByPath.getKey(), constraintsMap.get(nameByPath.getKey())),
                  ScoreMode.None));
        }
      }
    }
    return query;
  }

  /** Method to return the sorting order on basis of string param . */
  public static SortOrder getSortOrder(String value) {
    return ASC_ORDER.equalsIgnoreCase(value) ? SortOrder.ASC : SortOrder.DESC;
  }

  /**
   * This method return MatchQueryBuilder Object with boosts if any provided
   *
   * @param name of the attribute
   * @param value of the attribute
   * @param boost for increasing the search parameters priority
   * @return MatchQueryBuilder
   */
  public static MatchQueryBuilder createMatchQuery(String name, Object value, Float boost) {
    if (null != (boost)) {
      return QueryBuilders.matchQuery(name, value).boost(boost);
    } else {
      return QueryBuilders.matchQuery(name, value);
    }
  }

  /**
   * This method returns TermsQueryBuilder with boosts if any provided
   *
   * @param key : field name
   * @param values : values for the field value
   * @param boost for increasing the search parameters priority
   * @return TermsQueryBuilder
   */
  private static TermsQueryBuilder createTermsQuery(String key, List values, Float boost) {
    if (null != (boost)) {
      return QueryBuilders.termsQuery(key, (values).stream().toArray(Object[]::new)).boost(boost);
    } else {
      return QueryBuilders.termsQuery(key, (values).stream().toArray(Object[]::new));
    }
  }

  /**
   * This method returns RangeQueryBuilder with boosts if any provided
   *
   * @param name for the field
   * @param rangeOperation: keys and value related to range
   * @param boost for increasing the search parameters priority
   * @return RangeQueryBuilder
   */
  private static RangeQueryBuilder createRangeQuery(
      String name, Map<String, Object> rangeOperation, Float boost) {

    RangeQueryBuilder rangeQueryBuilder = QueryBuilders.rangeQuery(name).includeLower(false);
    for (Map.Entry<String, Object> it : rangeOperation.entrySet()) {
      switch (it.getKey()) {
        case LTE:
          rangeQueryBuilder.lte(it.getValue());
          break;
        case LT:
          rangeQueryBuilder.lt(it.getValue());
          break;
        case GTE:
          rangeQueryBuilder.gte(it.getValue());
          break;
        case GT:
          rangeQueryBuilder.gt(it.getValue());
          break;
      }
    }
    if (null != (boost)) {
      return rangeQueryBuilder.boost(boost);
    }
    return rangeQueryBuilder;
  }

  /**
   * This method returns TermQueryBuilder with boosts if any provided
   *
   * @param name of the field for termquery
   * @param value of the field for termquery
   * @param boost for increasing the search parameters priority
   * @return TermQueryBuilder
   */
  private static TermQueryBuilder createTermQuery(String name, Object value, Float boost) {
    if (null != (boost)) {
      return QueryBuilders.termQuery(name, value).boost(boost);
    } else {
      return QueryBuilders.termQuery(name, value);
    }
  }

  /**
   * this method return ExistsQueryBuilder with boosts if any provided
   *
   * @param name of the field which required for exists operation
   * @param boost for increasing the search parameters priority
   * @return ExistsQueryBuilder
   */
  private static ExistsQueryBuilder createExistQuery(String name, Float boost) {
    if (null != (boost)) {
      return QueryBuilders.existsQuery(name).boost(boost);
    } else {
      return QueryBuilders.existsQuery(name);
    }
  }

  /**
   * This method create lexical query with boosts if any provided
   *
   * @param key for search
   * @param rangeOperation to search or match in a particular way
   * @param boost for increasing the search parameters priority
   * @return QueryBuilder
   */
  public static QueryBuilder createLexicalQuery(
      String key, Map<String, Object> rangeOperation, Float boost) {
    QueryBuilder queryBuilder = null;
    for (Map.Entry<String, Object> it : rangeOperation.entrySet()) {
      switch (it.getKey()) {
        case STARTS_WITH:
          {
            String startsWithVal = (String) it.getValue();
            if (StringUtils.isNotBlank(startsWithVal)) {
              startsWithVal = startsWithVal.toLowerCase();
            }
            if (null != (boost)) {
              queryBuilder =
                  QueryBuilders.prefixQuery(key + RAW_APPEND, startsWithVal).boost(boost);
            }
            queryBuilder = QueryBuilders.prefixQuery(key + RAW_APPEND, startsWithVal);
            break;
          }
        case ENDS_WITH:
          {
            String endsWithRegex = "~" + it.getValue();
            if (null != (boost)) {
              queryBuilder =
                  QueryBuilders.regexpQuery(key + RAW_APPEND, endsWithRegex).boost(boost);
            }
            queryBuilder = QueryBuilders.regexpQuery(key + RAW_APPEND, endsWithRegex);
            break;
          }
      }
    }
    return queryBuilder;
  }

  /**
   * this method will take start time and subtract with current time to get the time spent in
   * millis.
   *
   * @param startTime long
   * @return long
   */
  public static long calculateEndTime(long startTime) {
    return System.currentTimeMillis() - startTime;
  }

  /**
   * This method will create searchdto on this of searchquery provided
   *
   * @param searchQueryMap Map<String,Object> contains query
   * @return SearchDto for search data in elastic search
   */
  public static SearchDTO createSearchDTO(Map<String, Object> searchQueryMap) {
    SearchDTO search = new SearchDTO();
    search = getBasicBuiders(search, searchQueryMap);
    search = setOffset(search, searchQueryMap);
    search = getLimits(search, searchQueryMap);
    addParentMapIdWildcardIfPresent(search, searchQueryMap);
    if (searchQueryMap.containsKey(JsonKey.GROUP_QUERY)) {
      search
          .getGroupQuery()
          .addAll(
              (Collection<? extends Map<String, Object>>) searchQueryMap.get(JsonKey.GROUP_QUERY));
    }
    search = getSoftConstraints(search, searchQueryMap);
    return search;
  }

  /**
   * This method add any softconstraints present in seach query to search DTo
   *
   * @param search search which contains the search parameters for elastic search.
   * @param searchQueryMap searchQueryMap which contains soft_constraints
   * @return SearchDTO updated searchDTO which contains soft_constraits
   */
  private static SearchDTO getSoftConstraints(
      SearchDTO search, Map<String, Object> searchQueryMap) {
    if (searchQueryMap.containsKey(JsonKey.SOFT_CONSTRAINTS)) {
      search.setSoftConstraints(
          (Map<String, Integer>) searchQueryMap.get(JsonKey.SOFT_CONSTRAINTS));
    }
    return search;
  }

  /**
   * This method adds any limits present in the search query
   *
   * @param search search which contains the search parameters for elastic search.
   * @param searchQueryMap searchQueryMap which contain limit
   * @return SearchDTO updated searchDTO which contains limit
   */
  private static SearchDTO getLimits(SearchDTO search, Map<String, Object> searchQueryMap) {
    if (searchQueryMap.containsKey(JsonKey.LIMIT)) {
      if ((searchQueryMap.get(JsonKey.LIMIT)) instanceof Integer) {
        search.setLimit((int) searchQueryMap.get(JsonKey.LIMIT));
      } else {
        search.setLimit(((BigInteger) searchQueryMap.get(JsonKey.LIMIT)).intValue());
      }
    }
    return search;
  }

  /**
   * This method adds offset if any present in the searchQuery
   *
   * @param search search which contains the search parameters for elastic search.
   * @param searchQueryMap searchQueryMap which contains offset
   * @return SearchDTO updated searchDTO which contain offset
   */
  private static SearchDTO setOffset(SearchDTO search, Map<String, Object> searchQueryMap) {
    if (searchQueryMap.containsKey(JsonKey.OFFSET)) {
      if ((searchQueryMap.get(JsonKey.OFFSET)) instanceof Integer) {
        search.setOffset((int) searchQueryMap.get(JsonKey.OFFSET));
      } else {
        search.setOffset(((BigInteger) searchQueryMap.get(JsonKey.OFFSET)).intValue());
      }
    }
    return search;
  }

  /**
   * This method adds basic query parameter to SearchDTO if any provided
   *
   * @param search search
   * @param searchQueryMap searchQueryMap
   * @return SearchDTO
   */
  private static SearchDTO getBasicBuiders(SearchDTO search, Map<String, Object> searchQueryMap) {
      if (searchQueryMap.containsKey(JsonKey.QUERY)) {
          String queryString = (String) searchQueryMap.get(JsonKey.QUERY);
          if (StringUtils.isNotBlank(queryString) && queryString.length() > allowedQueryStringLength) {
              queryString = queryString.substring(0, allowedQueryStringLength);
              logger.info("trimmed user search query string:" + queryString);
          }
          search.setQuery(queryString);
      }
    if (searchQueryMap.containsKey(JsonKey.QUERY_FIELDS)) {
      search.setQueryFields((List<String>) searchQueryMap.get(JsonKey.QUERY_FIELDS));
    }
    if (searchQueryMap.containsKey(JsonKey.FACETS)) {
      List<String> facetsList= (List<String>) searchQueryMap.get(JsonKey.FACETS);
      search.setFacets(facetsList);
    //  search.setFacets((List<Map<String, String>>) searchQueryMap.get(JsonKey.FACETS));
    }
    if (searchQueryMap.containsKey(JsonKey.FIELDS)) {
      search.setFields((List<String>) searchQueryMap.get(JsonKey.FIELDS));
    }
    if(searchQueryMap.containsKey(JsonKey.MULTI_QUERY_SEARCH_FIELDS)) {
      search.setMultiSearchFields((Map<String, List<String>>) searchQueryMap.get(JsonKey.MULTI_QUERY_SEARCH_FIELDS));
    }
    if (searchQueryMap.containsKey(JsonKey.FILTERS)) {
      search.getAdditionalProperties().put(JsonKey.FILTERS, searchQueryMap.get(JsonKey.FILTERS));
    }
    if (searchQueryMap.containsKey(JsonKey.OR_FILTERS)) {
      search.getAdditionalProperties().put(JsonKey.OR_FILTERS, searchQueryMap.get(JsonKey.OR_FILTERS));
    }
    if (searchQueryMap.containsKey(JsonKey.EXISTS)) {
      search.getAdditionalProperties().put(JsonKey.EXISTS, searchQueryMap.get(JsonKey.EXISTS));
    }
    if (searchQueryMap.containsKey(JsonKey.NOT_EXISTS)) {
      search
          .getAdditionalProperties()
          .put(JsonKey.NOT_EXISTS, searchQueryMap.get(JsonKey.NOT_EXISTS));
    }
    if (searchQueryMap.containsKey(JsonKey.NESTED_KEY_FILTER_GROUPED)) {
      search
          .getAdditionalProperties()
          .put(JsonKey.NESTED_KEY_FILTER_GROUPED, searchQueryMap.get(JsonKey.NESTED_KEY_FILTER_GROUPED));
    }
    if (searchQueryMap.containsKey(JsonKey.SORT_BY)) {
      search
          .getSortBy()
          .putAll((Map<? extends String, ? extends String>) searchQueryMap.get(JsonKey.SORT_BY));
    }
    return search;
  }

  /**
   * Method returns map which contains all the request data from elasticsearch
   *
   * @param response response from elastic search
   * @param searchDTO searchDTO which was used to search data
   * @param finalFacetList Facets provide aggregated data based on a search query
   * @return Map which will have all the requested data
   */
  public static Map<String, Object> getSearchResponseMap(
      SearchResponse response, SearchDTO searchDTO, List finalFacetList) {
    Map<String, Object> responseMap = new HashMap<>();
    List<Map<String, Object>> esSource = new ArrayList<>();
    long count = 0;
    if (response != null) {
      SearchHits hits = response.getHits();
      count = hits.getTotalHits();

      for (SearchHit hit : hits) {
        esSource.add(hit.getSourceAsMap());
      }

      // fetch aggregations aggregations
      finalFacetList = getFinalFacetList(response, searchDTO, finalFacetList);
    }
    responseMap.put(JsonKey.CONTENT, esSource);
    if (!(finalFacetList.isEmpty())) {
      responseMap.put(JsonKey.FACETS, finalFacetList);
    }
    responseMap.put(JsonKey.COUNT, count);
    return responseMap;
  }


  private static List<Map<String, Object>> getFinalFacetList(
          SearchResponse response, SearchDTO searchDTO, List<Map<String, Object>> finalFacetList) {

    if (null != searchDTO.getFacets() && !searchDTO.getFacets().isEmpty()) {
      for (String facet : searchDTO.getFacets()) {
        List<Map<String, Object>> aggsList = new ArrayList<>();

        Aggregations aggregations = response.getAggregations();
        if (aggregations != null) {
          Terms aggs = aggregations.get(facet);
          if (aggs != null) {
            for (Terms.Bucket bucket : aggs.getBuckets()) {
              Map<String, Object> internalMap = new HashMap<>();
              internalMap.put(JsonKey.NAME, bucket.getKeyAsString());
              internalMap.put(JsonKey.COUNT, bucket.getDocCount());
              aggsList.add(internalMap);
            }
          }
        }
        Map<String, Object> facetMap = new HashMap<>();
        facetMap.put("values", aggsList);
        facetMap.put(JsonKey.NAME, facet);
        finalFacetList.add(facetMap);
      }
    }
    return finalFacetList;
  }

  /**
   * This method return MultiMatchQueryBuilder Object with boosts if any provided
   *
   * @param query of the attribute
   * @param fields of the attribute
   * @param boost for increasing the search parameters priority
   * @return MultiMatchQueryBuilder
   */
  public static MultiMatchQueryBuilder createMultiMatchQuery(String query, String[] fields, Float boost) {
    if (null != (boost)) {
      return QueryBuilders.multiMatchQuery(query, fields).boost(boost);
    } else {
      return QueryBuilders.multiMatchQuery(query, fields);
    }
  }


  private static void addParentMapIdWildcardIfPresent(SearchDTO search, Map<String, Object> searchQueryMap) {
    if (MapUtils.isEmpty(searchQueryMap)) return;
    Map<String, Object> filterMap =
            (Map<String, Object>) searchQueryMap.get(JsonKey.FILTERS);

    if (MapUtils.isEmpty(filterMap)) return;

    if (search.getAdditionalProperties() == null) {
      search.setAdditionalProperties(new HashMap<>());
    }

    if (filterMap.containsKey(JsonKey.ADVANCED_FILTERS)) {
      search.getAdditionalProperties()
              .put(JsonKey.ADVANCED_FILTERS,
                      filterMap.get(JsonKey.ADVANCED_FILTERS));
      filterMap.remove(JsonKey.ADVANCED_FILTERS);
    }

    Object parentPathIdObj = filterMap.get(JsonKey.PARENT_PATH_ID);
    if (parentPathIdObj == null) return;

    if (CollectionUtils.isEmpty(search.getProperties())) {
      search.setProperties(new ArrayList<>());
    }

    List<String> parentPathIdList = new ArrayList<>();
    if (parentPathIdObj instanceof Collection) {
      for (Object parentPathId : (Collection<?>) parentPathIdObj) {
        if (parentPathId != null) {
          parentPathIdList.add(parentPathId.toString());
        }
      }
    } else {
      parentPathIdList.add(parentPathIdObj.toString());
    }

    List<Map<String, Object>> wildcardList = new ArrayList<>();

    for (String parentPathString : parentPathIdList) {

      String pattern = "*" + parentPathString;

      if ("all".equalsIgnoreCase(
              (String) filterMap.get(JsonKey.HIERARCHY_REQUEST_TYPE))) {
        pattern = pattern + "*";
      }

      Map<String, Object> wildcardClause = new HashMap<>();
      Map<String, Object> inner = new HashMap<>();
      inner.put("parentPathId.raw", pattern);
      wildcardClause.put("wildcard", inner);

      wildcardList.add(wildcardClause);
    }

    // Apply wildcard when single entry
    if (wildcardList.size() == 1) {
      search.getAdditionalProperties().remove(JsonKey.FILTERS);
      search.getAdditionalProperties().putAll(wildcardList.get(0));
      if (filterMap.containsKey(JsonKey.STATUS)) {
        Map<String, Object> statusMap = new HashMap<>();
        statusMap.put(JsonKey.STATUS, filterMap.get(JsonKey.STATUS));
        search.getAdditionalProperties().put(JsonKey.ADDITIONAL_FILTER, statusMap);
      }
    }
  }

    private static BoolQueryBuilder createWildcardQuery(
            Entry<String, Object> entry, BoolQueryBuilder query) {
        Object value = entry.getValue();
        if (value instanceof Map) {
            Map<String, Object> mapVal = (Map<String, Object>) value;
            for (Map.Entry<String, Object> e : mapVal.entrySet()) {
                String field = e.getKey();
                Object valObj = e.getValue();
                if (valObj == null) continue;
                String val = valObj.toString().trim().toLowerCase();
                if (val.isEmpty()) continue;
                // Add proper wildcard query
                query.must(QueryBuilders.wildcardQuery(field, val));
            }
            return query;
        }
        if (value instanceof String) {
            String val = value.toString().trim().toLowerCase();
            if (!val.isEmpty()) {
                // The entry key is the actual field
                String field = entry.getKey();
                query.must(QueryBuilders.wildcardQuery(field, val));
            }
        }
        return query;
    }

}
