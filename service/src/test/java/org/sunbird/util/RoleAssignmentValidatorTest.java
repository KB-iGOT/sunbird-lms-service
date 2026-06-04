package org.sunbird.util;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.core.classloader.annotations.SuppressStaticInitializationFor;
import org.powermock.modules.junit4.PowerMockRunner;
import org.sunbird.exception.ProjectCommonException;
import org.sunbird.keys.JsonKey;
import org.sunbird.model.organisation.Organisation;
import org.sunbird.model.systemsettings.SystemSetting;
import org.sunbird.request.RequestContext;
import org.sunbird.service.organisation.OrgService;
import org.sunbird.service.organisation.impl.OrgServiceImpl;
import org.sunbird.service.user.UserRoleService;
import org.sunbird.service.user.impl.UserRoleServiceImpl;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Test class for RoleAssignmentValidator
 * Tests the new functionality: validation of only NEW roles, not existing ones
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({
    UserRoleServiceImpl.class,
    OrgServiceImpl.class
})
@SuppressStaticInitializationFor({
    "org.sunbird.service.systemsettings.SystemSettingsService"
})
@PowerMockIgnore({
    "javax.management.*",
    "javax.net.ssl.*",
    "javax.security.*",
    "jdk.internal.reflect.*",
    "javax.crypto.*",
    "javax.script.*",
    "javax.xml.*",
    "com.sun.org.apache.xerces.*",
    "org.xml.*"
})
public class RoleAssignmentValidatorTest {

    private RoleAssignmentValidator validator;
    private UserRoleService userRoleService;
    private OrgService orgService;
    private RequestContext context;

    @Before
    public void setUp() throws Exception {
        PowerMockito.mockStatic(UserRoleServiceImpl.class);
        PowerMockito.mockStatic(OrgServiceImpl.class);

        userRoleService = Mockito.mock(UserRoleService.class);
        orgService = Mockito.mock(OrgService.class);

        when(UserRoleServiceImpl.getInstance()).thenReturn(userRoleService);
        when(OrgServiceImpl.getInstance()).thenReturn(orgService);

        validator = new RoleAssignmentValidator();
        context = new RequestContext();
    }

    /**
     * Test: Existing user keeps invalid role (not in request)
     * Expected: No validation error since existing roles are not validated
     */
    @Test
    public void testValidateRoleAssignment_ExistingUser_AllRolesAlreadyExist() {
        String requestingUserId = "admin123";
        String requestingUserOrgId = "org123";
        String targetOrgId = "org123";
        String targetUserId = "user456";
        List<String> rolesToAssign = Arrays.asList("PUBLIC");

        mockRequestingUserAsAdmin(requestingUserId);
        mockExistingUserRoles(targetUserId, Arrays.asList("PUBLIC")); // Same as request

        // Should not throw exception (no new roles to validate)
        try {
            validator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId, targetUserId, rolesToAssign, context);
        } catch (Exception e) {
            fail("Should not throw exception when no new roles: " + e.getMessage());
        }
    }

    /**
     * Test: Requesting user has no admin role
     * Expected: Should throw unauthorized exception
     */
    @Test
    public void testValidateRoleAssignment_RequestingUserNotAdmin_ThrowsException() {
        String requestingUserId = "user123";
        String requestingUserOrgId = "org123";
        String targetOrgId = "org123";
        String targetUserId = "user456";
        List<String> rolesToAssign = Arrays.asList("PUBLIC");

        mockRequestingUserAsNonAdmin(requestingUserId);

        try {
            validator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId, targetUserId, rolesToAssign, context);
            fail("Should throw exception for non-admin user");
        } catch (ProjectCommonException e) {
            // Expected exception
            assertTrue("Should be unauthorized error", e.getMessage().contains("ADMIN"));
        }
    }

    /**
     * Test: Requesting user has no roles
     * Expected: Should throw unauthorized exception
     */
    @Test
    public void testValidateRoleAssignment_RequestingUserNoRoles_ThrowsException() {
        String requestingUserId = "user123";
        String requestingUserOrgId = "org123";
        String targetOrgId = "org123";
        String targetUserId = "user456";
        List<String> rolesToAssign = Arrays.asList("PUBLIC");

        when(userRoleService.getUserRoles(requestingUserId, null, context)).thenReturn(Collections.emptyList());

        try {
            validator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId, targetUserId, rolesToAssign, context);
            fail("Should throw exception for user with no roles");
        } catch (ProjectCommonException e) {
            // Expected exception
            assertTrue("Should be unauthorized error", e.getMessage().contains("no roles"));
        }
    }

    // Helper methods

    private void mockRequestingUserAsAdmin(String userId) {
        List<Map<String, Object>> roles = new ArrayList<>();
        Map<String, Object> roleMap = new HashMap<>();
        roleMap.put(JsonKey.ROLE, "ORG_ADMIN");
        roles.add(roleMap);

        when(userRoleService.getUserRoles(userId, null, context)).thenReturn(roles);
    }

    private void mockRequestingUserAsNonAdmin(String userId) {
        List<Map<String, Object>> roles = new ArrayList<>();
        Map<String, Object> roleMap = new HashMap<>();
        roleMap.put(JsonKey.ROLE, "PUBLIC"); // Not an admin role
        roles.add(roleMap);

        when(userRoleService.getUserRoles(userId, null, context)).thenReturn(roles);
    }

    private void mockExistingUserRoles(String userId, List<String> roleNames) {
        List<Map<String, Object>> roles = new ArrayList<>();
        for (String roleName : roleNames) {
            Map<String, Object> roleMap = new HashMap<>();
            roleMap.put(JsonKey.ROLE, roleName);
            roles.add(roleMap);
        }

        when(userRoleService.getUserRoles(userId, null, context)).thenReturn(roles);
    }

    private void mockValidOrgAndRoles(String orgId, List<String> allowedRoles) {
        Organisation org = new Organisation();
        org.setId(orgId);
        org.setMinistryOrStateType("state");
        org.setMinistryOrStateId(orgId);

        when(orgService.getOrgObjById(anyString(), Mockito.any())).thenReturn(org);
    }
}

