package org.sunbird.dao.user;

import org.sunbird.request.RequestContext;

import java.util.Map;

/**
 * This interface will have all methods required for user login service api.
 *
 * @author Ramya Ranganathan
 */
public interface UserLoginDao {
  void insertUserLogin(Map<String, Object> userId, RequestContext context);
}
