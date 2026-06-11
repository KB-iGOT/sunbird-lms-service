package org.sunbird.dao.user.impl;

import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.dao.user.UserLoginDao;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.keys.JsonKey;
import org.sunbird.logging.LoggerUtil;
import org.sunbird.request.RequestContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * This interface will have all methods required for user login service api.
 *
 * @author Ramya Ranganathan
 */
public class UserLoginDaoImpl implements UserLoginDao {
    private final LoggerUtil logger = new LoggerUtil(UserLoginDaoImpl.class);

    private final CassandraOperation cassandraOperation = ServiceFactory.getInstance();
    private static UserLoginDao userLoginDao = null;

    public static UserLoginDao getInstance() {
        if (userLoginDao == null) {
            userLoginDao = new UserLoginDaoImpl();
        }
        return userLoginDao;
    }

    public void insertUserLogin(Map<String, Object> userMap, RequestContext context) {
        try {
            String userId = (String) userMap.get(JsonKey.USER_ID);
            logger.info(context, "UserLoginDaoImpl:insertRecords called for userId: " + userId);
            Map<String, Object> userLoginMap = new HashMap<>();
            Instant currentTimestamp = Instant.now();
            userLoginMap.put(JsonKey.CONSENT_USER_ID, userId);
            userLoginMap.put(JsonKey.FIRST_LOGIN, currentTimestamp);
            userLoginMap.put(JsonKey.LAST_LOGIN, currentTimestamp);

            cassandraOperation.insertRecord(
                    JsonKey.SUNBIRD,
                    JsonKey.USER_LOGIN,
                    userLoginMap,
                    context);
        } catch (Exception ex) {
            logger.error("Exception in UserLoginDaoImpl:insertRecords called for userId: ", ex);
        }
    }
}
