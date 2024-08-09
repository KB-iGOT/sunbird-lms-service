package org.sunbird.notification.sms.providerimpl;

import org.sunbird.notification.sms.provider.ISmsProvider;
import org.sunbird.notification.sms.provider.ISmsProviderFactory;

public class NetCoreGatewaySmsProviderFactory implements ISmsProviderFactory {
    private NetCoreGatewaySmsProvider ncSmsProvider = null;

    @Override
    public ISmsProvider create() {
        if (ncSmsProvider == null) {
            ncSmsProvider = new NetCoreGatewaySmsProvider();
        }
        return ncSmsProvider;
    }
}
