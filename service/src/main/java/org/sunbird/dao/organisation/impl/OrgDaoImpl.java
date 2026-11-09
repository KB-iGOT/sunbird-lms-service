package org.sunbird.dao.organisation.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.sunbird.redis.OrgInfoCache;
import org.sunbird.request.RequestContext;
import org.sunbird.response.Response;
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

  public static OrgDao getInstance() {
    if (orgDao == null) {
      orgDao = new OrgDaoImpl();
    }
    return orgDao;
  }

  @Override
  public Map<String, Object> getOrgById(String orgId, RequestContext context) {
    if (StringUtils.isNotBlank(orgId)) {
      // Read-through on the RAW row (org:{orgId}); enrichment always runs afterwards so a cache
      // hit and a Cassandra read produce an identical map.
      Map<String, Object> orgMap = OrgInfoCache.get(orgId);
      if (orgMap == null) {
        Response response =
            cassandraOperation.getRecordById(KEYSPACE_NAME, ORG_TABLE_NAME, orgId, context);
        List<Map<String, Object>> responseList =
            (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
        if (CollectionUtils.isNotEmpty(responseList)) {
          orgMap = responseList.get(0);
          OrgInfoCache.put(orgId, orgMap);
        }
      }
      if (orgMap != null) {
        enrichOrgDetails(orgMap, context);
        enrichOrgDetailsWithCustomFields(orgMap, context);
        return orgMap;
      }
    }
    return Collections.emptyMap();
  }

  @Override
  public List<Map<String, Object>> getOrgByIds(List<String> orgIds, RequestContext context) {
    return getOrgByIds(orgIds, Collections.emptyList(), context);
  }

  @Override
  public List<Map<String, Object>> getOrgByIds(
      List<String> orgIds, List<String> fields, RequestContext context) {
    if (CollectionUtils.isNotEmpty(orgIds)) {
      // Only full-row reads go through the cache; a projected read (fields given) keeps the
      // Cassandra path so its column set is exactly what the caller asked for.
      if (CollectionUtils.isEmpty(fields)) {
        return getFullOrgsByIds(orgIds, context);
      }
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

  private List<Map<String, Object>> getFullOrgsByIds(List<String> orgIds, RequestContext context) {
    Map<String, Map<String, Object>> cached = OrgInfoCache.getAll(orgIds);
    List<String> missing = new ArrayList<>();
    for (String orgId : orgIds) {
      if (!cached.containsKey(orgId)) {
        missing.add(orgId);
      }
    }
    List<Map<String, Object>> result = new ArrayList<>(cached.values());
    if (!missing.isEmpty()) {
      Response response =
          cassandraOperation.getPropertiesValueById(
              KEYSPACE_NAME, ORG_TABLE_NAME, missing, Collections.emptyList(), context);
      List<Map<String, Object>> responseList =
          (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
      if (CollectionUtils.isNotEmpty(responseList)) {
        for (Map<String, Object> orgMap : responseList) {
          OrgInfoCache.put((String) orgMap.get(JsonKey.ID), orgMap);
          result.add(orgMap);
        }
      }
    }
    if (result.isEmpty()) {
      return Collections.emptyList();
    }
    result.forEach(orgMap -> enrichOrgDetails(orgMap, context));
    return result;
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
    Response response =
        cassandraOperation.insertRecord(KEYSPACE_NAME, ORG_TABLE_NAME, orgMap, context);
    OrgInfoCache.evict((String) orgMap.get(JsonKey.ID));
    return response;
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
    Response response =
        cassandraOperation.updateRecord(KEYSPACE_NAME, ORG_TABLE_NAME, orgMap, context);
    OrgInfoCache.evict((String) orgMap.get(JsonKey.ID));
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
