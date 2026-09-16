package org.sunbird.dao.userkarmacoinwallet.impl;

import java.util.List;
import java.util.Map;
import org.apache.commons.collections.CollectionUtils;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.dao.userkarmacoinwallet.UserKarmaCoinWalletDao;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.keys.JsonKey;
import org.sunbird.request.RequestContext;
import org.sunbird.response.Response;

public class UserKarmaCoinWalletDaoImpl implements UserKarmaCoinWalletDao {
  private final String TABLE_NAME = "user_karma_coin_wallet";
  private final CassandraOperation cassandraOperation = ServiceFactory.getInstance();

  private static UserKarmaCoinWalletDao walletDao = null;

  public static UserKarmaCoinWalletDao getInstance() {
    if (walletDao == null) {
      walletDao = new UserKarmaCoinWalletDaoImpl();
    }
    return walletDao;
  }

  @Override
  public Map<String, Object> getWallet(String userId, RequestContext context) {
    Response response =
        cassandraOperation.getRecordById(
            JsonKey.SUNBIRD, TABLE_NAME, Map.of(JsonKey.USERID, userId), context);
    List<Map<String, Object>> records = (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
    return CollectionUtils.isNotEmpty(records) ? records.get(0) : null;
  }
}