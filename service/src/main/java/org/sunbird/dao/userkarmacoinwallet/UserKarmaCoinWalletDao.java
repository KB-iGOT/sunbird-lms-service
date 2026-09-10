package org.sunbird.dao.userkarmacoinwallet;

import java.util.Map;
import org.sunbird.request.RequestContext;

public interface UserKarmaCoinWalletDao {

  /**
   * Fetches the karma coin wallet row for a user.
   *
   * @param userId user id.
   * @param context
   * @return wallet record (total_earned, total_redeemed) or null if the user has no wallet yet.
   */
  Map<String, Object> getWallet(String userId, RequestContext context);
}