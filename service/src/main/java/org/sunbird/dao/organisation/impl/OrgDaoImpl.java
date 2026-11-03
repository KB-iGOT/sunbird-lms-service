package org.sunbird.dao.organisation.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.ElasticSearchHelper;
import org.sunbird.common.factory.EsClientFactory;
import org.sunbird.common.inf.ElasticSearchService;
import org.sunbird.dao.organisation.OrgDao;
import org.sunbird.dto.SearchDTO;
import org.sunbird.exception.ProjectCommonException;
import org.sunbird.exception.ResponseCode;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.keys.JsonKey;
import org.sunbird.logging.LoggerUtil;
import org.sunbird.redis.RedisCacheUtil;
import org.sunbird.request.RequestContext;
import org.sunbird.response.Response;
import org.sunbird.util.PropertiesCache;
import org.sunbird.util.ProjectUtil;
import org.sunbird.util.Util;
import scala.concurrent.Future;

public class OrgDaoImpl implements OrgDao {
  private final ElasticSearchService esService = EsClientFactory.getInstance(JsonKey.REST);
  private final LoggerUtil logger = new LoggerUtil(OrgDaoImpl.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final CassandraOperation cassandraOperation = ServiceFactory.getInstance();
  private static OrgDao orgDao;
  private static final String KEYSPACE_NAME = JsonKey.SUNBIRD;
  private static final String ORG_TABLE_NAME = JsonKey.ORGANISATION;

  /**
   * Cache-aside key prefix for a single org's assembled details (as returned by {@link
   * #getOrgById}). Unlike the user-profile cache, this is safe to share across every caller —
   * the response is a pure function of orgId, with no requester-specific fields (confirmed in
   * OrganisationManagementActor.getOrgDetails). Invalidated in {@link #update} whenever an org's
   * row is written, which covers every org-mutating entry point (org update, status update, key
   * rotation all route through this single method).
   */
  private static final String ORG_DETAILS_CACHE_KEY_PREFIX = "org_details:";

  public static OrgDao getInstance() {
    if (orgDao == null) {
      orgDao = new OrgDaoImpl();
    }
    return orgDao;
  }

  @Override
  public Map<String, Object> getOrgById(String orgId, RequestContext context) {
    if (StringUtils.isBlank(orgId)) {
      return Collections.emptyMap();
    }
    String cacheKey = ORG_DETAILS_CACHE_KEY_PREFIX + orgId;
    Map<String, Object> cached = getCachedOrgDetails(cacheKey, context);
    if (cached != null) {
      return cached;
    }
    Response response =
        cassandraOperation.getRecordById(KEYSPACE_NAME, ORG_TABLE_NAME, orgId, context);
    List<Map<String, Object>> responseList =
        (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
    if (CollectionUtils.isNotEmpty(responseList)) {
      Map<String, Object> orgMap = responseList.get(0);
      enrichOrgDetails(orgMap, context);
      enrichOrgDetailsWithCustomFields(orgMap, context);
      cacheOrgDetails(cacheKey, orgMap, context);
      return orgMap;
    }
    return Collections.emptyMap();
  }

  /**
   * Never throws — a Redis failure just falls back to the Cassandra read as if it were a cache
   * miss, so a Redis outage degrades this read path back to its pre-caching behaviour.
   */
  private Map<String, Object> getCachedOrgDetails(String cacheKey, RequestContext context) {
    try {
      String cached = RedisCacheUtil.get(cacheKey, null, 0);
      if (StringUtils.isBlank(cached)) {
        return null;
      }
      return mapper.readValue(cached, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      logger.error(context, "OrgDaoImpl: cache read failed for key " + cacheKey, e);
      return null;
    }
  }

  /** Best-effort — a write failure here only means the next read re-computes from Cassandra. */
  private void cacheOrgDetails(String cacheKey, Map<String, Object> orgMap, RequestContext context) {
    try {
      int ttl =
          Integer.parseInt(PropertiesCache.getInstance().getProperty(JsonKey.ORG_DETAILS_REDIS_TTL));
      RedisCacheUtil.set(cacheKey, mapper.writeValueAsString(orgMap), ttl);
    } catch (Exception e) {
      logger.error(context, "OrgDaoImpl: cache write failed for key " + cacheKey, e);
    }
  }

  @Override
  public List<Map<String, Object>> getOrgByIds(List<String> orgIds, RequestContext context) {
    return getOrgByIds(orgIds, Collections.emptyList(), context);
  }

  @Override
  public List<Map<String, Object>> getOrgByIds(
      List<String> orgIds, List<String> fields, RequestContext context) {
    if (CollectionUtils.isNotEmpty(orgIds)) {
      Response response =
          cassandraOperation.getPropertiesValueById(
              KEYSPACE_NAME, ORG_TABLE_NAME, orgIds, fields, context);
      List<Map<String, Object>> responseList =
          (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
      if (CollectionUtils.isNotEmpty(responseList)) {
        responseList.stream().forEach(orgMap -> enrichOrgDetails(orgMap, context));
        return responseList;
      }
    }
    return Collections.emptyList();
  }

  @Override
  public String saveOrgToEs(String id, Map<String, Object> data, RequestContext context) {
    String type = ProjectUtil.EsType.organisation.getTypeName();
    Future<String> responseF = esService.save(type, id, data, context);
    return (String) ElasticSearchHelper.getResponseFromFuture(responseF);
  }

  private void enrichOrgDetails(Map<String, Object> orgMap, RequestContext context) {
    String orgLocation = (String) orgMap.get(JsonKey.ORG_LOCATION);
    List orgLocationList = new ArrayList<>();
    if (StringUtils.isNotBlank(orgLocation)) {
      try {
        orgLocationList = mapper.readValue(orgLocation, List.class);
      } catch (Exception e) {
        logger.info(
            context,
            "Exception occurred while converting orgLocation to List<Map<String,String>>.");
      }
    }
    orgMap.put(JsonKey.ORG_LOCATION, orgLocationList);
    orgMap.put(JsonKey.HASHTAGID, orgMap.get(JsonKey.ID));
    orgMap.remove(JsonKey.CONTACT_DETAILS);
    orgMap.putAll(Util.getOrgDefaultValue());
  }

  private void enrichOrgDetailsWithCustomFields(Map<String, Object> orgMap, RequestContext context) {

    String customFields = (String) orgMap.get(JsonKey.CUSTOM_FIELDS_DATA);
    if (StringUtils.isNotBlank(customFields)) {
      try {
        Map<String, Object> customFieldsMap = mapper.readValue(customFields, Map.class);
        orgMap.put(JsonKey.CUSTOM_FIELDS_DATA, customFieldsMap);
      } catch (JsonProcessingException e) {
        logger.error(context, "Error while parsing custom fields", e);
        ProjectCommonException.throwServerErrorException(ResponseCode.SERVER_ERROR);
      }
    } else {
      orgMap.put(JsonKey.CUSTOM_FIELDS_DATA, new HashMap<>());
    }
  }


  @Override
  public Response create(Map<String, Object> orgMap, RequestContext context) {
    try {
      List<Map<String, Object>> orgLocation = (List) orgMap.get(JsonKey.ORG_LOCATION);
      if (CollectionUtils.isNotEmpty(orgLocation)) {
        String orgLoc = mapper.writeValueAsString(orgLocation);
        orgMap.put(JsonKey.ORG_LOCATION, orgLoc);
      }
    } catch (JsonProcessingException e) {
      ProjectCommonException.throwServerErrorException(ResponseCode.SERVER_ERROR);
    }
    return cassandraOperation.insertRecord(KEYSPACE_NAME, ORG_TABLE_NAME, orgMap, context);
  }

  @Override
  public Response update(Map<String, Object> orgMap, RequestContext context) {
    try {
      List<Map<String, Object>> orgLocation = (List) orgMap.get(JsonKey.ORG_LOCATION);
      if (CollectionUtils.isNotEmpty(orgLocation)) {
        String orgLoc = mapper.writeValueAsString(orgLocation);
        orgMap.put(JsonKey.ORG_LOCATION, orgLoc);
      }
    } catch (JsonProcessingException e) {
      ProjectCommonException.throwServerErrorException(ResponseCode.SERVER_ERROR);
    }
    Response response = cassandraOperation.updateRecord(KEYSPACE_NAME, ORG_TABLE_NAME, orgMap, context);
    // Org data just changed — drop the cached details so the next read recomputes instead of
    // serving a stale cached org. This single call site covers every org-mutating entry point
    // (org update, status update, key rotation) since they all route through this method.
    String orgId = (String) orgMap.get(JsonKey.ID);
    if (StringUtils.isNotBlank(orgId)) {
      try {
        RedisCacheUtil.delete(ORG_DETAILS_CACHE_KEY_PREFIX + orgId);
      } catch (Exception e) {
        logger.error(context, "OrgDaoImpl: cache invalidation failed for orgId " + orgId, e);
      }
    }
    return response;
  }

  @Override
  public Response search(Map<String, Object> searchQueryMap, RequestContext context) {
    SearchDTO searchDto = ElasticSearchHelper.createSearchDTO(searchQueryMap);
    Map<String, Object> result =
        (Map<String, Object>) ElasticSearchHelper.getResponseFromFuture(search(searchDto, context));
    Response response = new Response();
    if (result != null) {
      response.put(JsonKey.COUNT, result.get(JsonKey.COUNT));
      response.put(JsonKey.RESPONSE, result.get(JsonKey.CONTENT));
    } else {
      List<Map<String, Object>> list = new ArrayList<>();
      response.put(JsonKey.COUNT, list.size());
      response.put(JsonKey.RESPONSE, list);
    }
    return response;
  }

  @Override
  public Future<Map<String, Object>> search(SearchDTO searchDTO, RequestContext context) {
    String type = ProjectUtil.EsType.organisation.getTypeName();
    return esService.search(searchDTO, type, context);
  }
}
