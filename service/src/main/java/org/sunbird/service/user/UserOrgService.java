package org.sunbird.service.user;

import java.util.List;
import java.util.Map;
import org.sunbird.request.RequestContext;

public interface UserOrgService {
  void registerUserToOrg(Map<String, Object> userMap, RequestContext context);

  List<Map<String, Object>> getUserOrgListByUserId(String userId, RequestContext context);

  /** The user_organisation rows for one (userId, organisationId); normally zero or one row. */
  List<Map<String, Object>> getUserOrgDetails(
      String userId, String organisationId, RequestContext context);

  void deleteUserOrgMapping(List<Map<String, Object>> userOrgList, RequestContext context);

  void upsertUserOrgData(Map<String, Object> userMap, RequestContext context);

  void softDeleteOldUserOrgMapping(List<Map<String, Object>> userOrgList, RequestContext context);
}
