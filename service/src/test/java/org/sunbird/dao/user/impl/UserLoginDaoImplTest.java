package org.sunbird.dao.user.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.keys.JsonKey;
import org.sunbird.request.RequestContext;

@RunWith(PowerMockRunner.class)
@PrepareForTest(ServiceFactory.class)
public class UserLoginDaoImplTest {

  private CassandraOperation cassandraOperation;

  @Before
  public void setUp() {
    PowerMockito.mockStatic(ServiceFactory.class);
    cassandraOperation = Mockito.mock(CassandraOperation.class);
    when(ServiceFactory.getInstance()).thenReturn(cassandraOperation);
  }

  @Test
  public void shouldSetLastLoginToNullOnFirstInsert() {
    Map<String, Object> userMap = new HashMap<>();
    userMap.put(JsonKey.USER_ID, "user-1");

    UserLoginDaoImpl dao = new UserLoginDaoImpl();
    dao.insertUserLogin(userMap, new RequestContext());

    ArgumentCaptor<Map<String, Object>> userLoginMapCaptor = ArgumentCaptor.forClass(Map.class);
    verify(cassandraOperation)
        .insertRecord(eq(JsonKey.SUNBIRD), eq(JsonKey.USER_LOGIN), userLoginMapCaptor.capture(), isNull());

    Map<String, Object> savedLoginMap = userLoginMapCaptor.getValue();
    assertEquals("user-1", savedLoginMap.get(JsonKey.CONSENT_USER_ID));
    assertNotNull(savedLoginMap.get(JsonKey.FIRST_LOGIN));
    assertNull(savedLoginMap.get(JsonKey.LAST_LOGIN));
  }
}
