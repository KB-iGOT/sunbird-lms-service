package org.sunbird.notification.sms.providerimpl;

import org.sunbird.notification.sms.provider.ISmsProvider;
import org.sunbird.notification.sms.provider.ISmsProviderFactory;

public class GcpGatewaySmsProviderFactory implements ISmsProviderFactory {
    private GcpGatewaySmsProvider gcpSmsProvider = null;

    @Override
  public ISmsProvider create() {
    if (gcpSmsProvider == null) {
        gcpSmsProvider = new GcpGatewaySmsProvider();
    }
    return gcpSmsProvider;
  }
}
