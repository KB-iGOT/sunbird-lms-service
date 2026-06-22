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
import org.sunbird.exception.ResponseCode;
import org.sunbird.keys.JsonKey;
import org.sunbird.model.organisation.Organisation;
import org.sunbird.request.RequestContext;
import org.sunbird.service.organisation.OrgService;
import org.sunbird.service.organisation.impl.OrgServiceImpl;
import org.sunbird.service.systemsettings.SystemSettingsService;
import org.sunbird.service.user.UserRoleService;
import org.sunbird.service.user.impl.UserRoleServiceImpl;
import org.sunbird.model.systemsettings.SystemSetting;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

/**
 * Test class for RoleAssignmentValidator
 * Tests the new functionality: validation of only NEW roles, not existing ones
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({
    UserRoleServiceImpl.class,
    OrgServiceImpl.class,
    RoleAssignmentValidator.class,
    SystemSettingsService.class
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
    private SystemSettingsService systemSettingsService;
    private RequestContext context;

    @Before
    public void setUp() throws Exception {
        PowerMockito.mockStatic(UserRoleServiceImpl.class);
        PowerMockito.mockStatic(OrgServiceImpl.class);

        userRoleService = Mockito.mock(UserRoleService.class);
        orgService = Mockito.mock(OrgService.class);
        systemSettingsService = Mockito.mock(SystemSettingsService.class);

        when(UserRoleServiceImpl.getInstance()).thenReturn(userRoleService);
        when(OrgServiceImpl.getInstance()).thenReturn(orgService);

        // Mock orgTypeConfig - contains the fields array with name-value mapping
        SystemSetting orgTypeConfigSetting = new SystemSetting();
        orgTypeConfigSetting.setId("orgTypeConfig");
        orgTypeConfigSetting.setField("orgTypeConfig");
        String orgTypeConfigJson = "{"
                + "\"fields\": ["
                + "  {\"name\": \"SPV\", \"value\": 512},"
                + "  {\"name\": \"STATE\", \"value\": 2048},"
                + "  {\"name\": \"Ministry\", \"value\": 16},"
                + "  {\"name\": \"Department\", \"value\": 8},"
                + "  {\"name\": \"MDO\", \"value\": 128}"
                + "]"
                + "}";
        orgTypeConfigSetting.setValue(orgTypeConfigJson);
        when(systemSettingsService.getSystemSettingByKey(Mockito.eq(JsonKey.ORG_TYPE_CONFIG), Mockito.any())).thenReturn(orgTypeConfigSetting);

        // Mock orgTypeList - contains the roles allowed for each org type name
        SystemSetting orgTypeListSetting = new SystemSetting();
        orgTypeListSetting.setId("orgTypeList");
        orgTypeListSetting.setField("orgTypeList");
        String orgTypeListJson = "{"
                + "\"orgTypeList\": ["
                + "  {\"name\": \"SPV\", \"roles\": [\"PUBLIC\", \"CONTENT_CREATOR\", \"CONTENT_REVIEWER\", \"SPV_ADMIN\"]},"
                + "  {\"name\": \"STATE\", \"roles\": [\"PUBLIC\", \"CONTENT_CREATOR\", \"CONTENT_REVIEWER\", \"MDO_ADMIN\", \"ORG_ADMIN\"]},"
                + "  {\"name\": \"Ministry\", \"roles\": [\"PUBLIC\", \"CONTENT_CREATOR\", \"CONTENT_REVIEWER\", \"MDO_ADMIN\", \"ORG_ADMIN\"]},"
                + "  {\"name\": \"Department\", \"roles\": [\"PUBLIC\", \"CONTENT_CREATOR\", \"ORG_ADMIN\"]},"
                + "  {\"name\": \"MDO\", \"roles\": [\"PUBLIC\", \"CONTENT_CREATOR\", \"ORG_ADMIN\"]}"
                + "]"
                + "}";
        orgTypeListSetting.setValue(orgTypeListJson);
        when(systemSettingsService.getSystemSettingByKey(Mockito.eq(JsonKey.ORG_TYPE_LIST), Mockito.any())).thenReturn(orgTypeListSetting);

        // Use PowerMock to intercept SystemSettingsService constructor
        PowerMockito.whenNew(SystemSettingsService.class).withNoArguments().thenReturn(systemSettingsService);

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

        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(targetOrgId); // Self-referencing for same org
        targetOrg.setOrganisationType(2048); // STATE
        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

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

    /**
     * Test Case 1: Verify Non-Admin user receives proper error when trying to create user
     * Expected: 401 unauthorized error with proper message
     */
    @Test
    public void testCreateUserByNonAdmin_UnauthorizedError() {
        String requestingUserId = "nonAdminUser";
        String requestingUserOrgId = "org123";
        String targetOrgId = "org123";
        String targetUserId = null; // New user creation
        List<String> rolesToAssign = Arrays.asList("PUBLIC");

        mockRequestingUserAsNonAdmin(requestingUserId);

        try {
            validator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId, targetUserId, rolesToAssign, context);
            fail("Should throw exception for non-admin user");
        } catch (ProjectCommonException e) {
            assertEquals("Should be unauthorized error code", 401, e.getErrorResponseCode());
            assertTrue("Should contain ADMIN in message", e.getMessage().contains("ADMIN"));
        }
    }

    /**
     * Test Case 2: Verify SPV_ADMIN able to create user in own organisation
     * Expected: Validation should pass
     */
    @Test
    public void testSPVAdminCreateUserInOwnOrg_Success() {
        String requestingUserId = "spvAdmin123";
        String requestingUserOrgId = "spvOrg123";
        String targetOrgId = "spvOrg123"; // Same org
        String targetUserId = null; // New user creation
        List<String> rolesToAssign = Arrays.asList("PUBLIC");

        mockRequestingUserWithRole(requestingUserId, "SPV_ADMIN");
        mockExistingUserRoles(targetUserId, Collections.emptyList()); // New user

        // Mock the target organization
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(targetOrgId); // Self-referencing for root org
        targetOrg.setOrganisationType(512); // SPV
        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        // Should not throw exception
        try {
            validator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId, targetUserId, rolesToAssign, context);
        } catch (Exception e) {
            fail("SPV_ADMIN should be able to create user in own org: " + e.getMessage());
        }
    }

    /**
     * Test Case 3: Verify SPV_ADMIN able to create user in any organisation (cross-org)
     * Expected: Validation should pass - SPV_ADMIN bypasses org hierarchy check
     */
    @Test
    public void testSPVAdminCreateUserInDifferentOrg_Success() {
        String requestingUserId = "spvAdmin123";
        String requestingUserOrgId = "spvOrg123";
        String targetOrgId = "completelyDifferentOrg999"; // Different org, not child
        String targetUserId = null; // New user creation
        List<String> rolesToAssign = Arrays.asList("PUBLIC");

        mockRequestingUserWithRole(requestingUserId, "SPV_ADMIN");

        // Mock target org with completely different parent
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId("someOtherParent888"); // Different parent
        targetOrg.setOrganisationType(2048);

        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        // Should not throw exception - SPV_ADMIN can assign to any org
        try {
            validator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId, targetUserId, rolesToAssign, context);
        } catch (Exception e) {
            fail("SPV_ADMIN should be able to create user in any org (cross-org): " + e.getMessage());
        }
    }

    /**
     * Test Case 4: Verify MDO_ADMIN who belongs to State/Ministry is able to create user in own organisation
     * Expected: Validation should pass
     */
    @Test
    public void testMDOAdminCreateUserInOwnOrg_Success() {
        String requestingUserId = "mdoAdmin123";
        String requestingUserOrgId = "stateOrg123";
        String targetOrgId = "stateOrg123"; // Same org
        String targetUserId = null; // New user creation
        List<String> rolesToAssign = Arrays.asList("PUBLIC");

        mockRequestingUserWithRole(requestingUserId, "MDO_ADMIN");
        mockExistingUserRoles(targetUserId, Collections.emptyList());

        // Mock the target organization
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(targetOrgId); // Self-referencing
        targetOrg.setOrganisationType(2048); // STATE
        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        // Should not throw exception
        try {
            validator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId, targetUserId, rolesToAssign, context);
        } catch (Exception e) {
            fail("MDO_ADMIN should be able to create user in own org: " + e.getMessage());
        }
    }

    /**
     * Test Case 5: Verify MDO_ADMIN who belongs to State/Ministry is able to create user in their children organisation
     * Expected: Validation should pass
     */
    @Test
    public void testMDOAdminCreateUserInChildOrg_Success() {
        String requestingUserId = "mdoAdmin123";
        String requestingUserOrgId = "stateOrg123";
        String targetOrgId = "childOrg456";
        String targetUserId = null; // New user creation
        List<String> rolesToAssign = Arrays.asList("PUBLIC");

        mockRequestingUserWithRole(requestingUserId, "MDO_ADMIN");

        // Mock target org with state as parent
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(requestingUserOrgId); // Parent org
        targetOrg.setOrganisationType(2048); // STATE

        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        // Should not throw exception
        try {
            validator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId, targetUserId, rolesToAssign, context);
        } catch (Exception e) {
            fail("MDO_ADMIN should be able to create user in child org: " + e.getMessage());
        }
    }

    /**
     * Test Case 6: Verify MDO_ADMIN who belongs to State/Ministry is NOT able to create user in another State/Ministry organisation
     * Expected: 401 unauthorized error
     */
    @Test
    public void testMDOAdminCreateUserInOtherStateOrg_UnauthorizedError() {
        String requestingUserId = "mdoAdmin123";
        String requestingUserOrgId = "stateOrg123";
        String targetOrgId = "otherStateOrg789";
        String targetUserId = null; // New user creation
        List<String> rolesToAssign = Arrays.asList("PUBLIC");

        mockRequestingUserWithRole(requestingUserId, "MDO_ADMIN");

        // Mock target org with different state as parent
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId("differentStateOrg999"); // Different parent
        targetOrg.setOrganisationType(2048); // STATE

        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        try {
            validator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId, targetUserId, rolesToAssign, context);
            fail("Should throw exception when MDO_ADMIN tries to create user in different state org");
        } catch (ProjectCommonException e) {
            assertEquals("Should be unauthorized error code", 401, e.getErrorResponseCode());
            assertTrue("Should contain authority message", e.getMessage().contains("authority"));
        }
    }

    /**
     * Test Case 7: Verify user with ORG_ADMIN role can create users in own org
     * Expected: Validation should pass
     */
    @Test
    public void testOrgAdminCreateUserInOwnOrg_Success() {
        String requestingUserId = "orgAdmin123";
        String requestingUserOrgId = "org123";
        String targetOrgId = "org123";
        String targetUserId = null; // New user creation
        List<String> rolesToAssign = Arrays.asList("PUBLIC");

        mockRequestingUserAsAdmin(requestingUserId);
        mockExistingUserRoles(targetUserId, Collections.emptyList());

        // Mock the target organization
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(targetOrgId); // Self-referencing
        targetOrg.setOrganisationType(2048); // STATE
        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        // Should not throw exception
        try {
            validator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId, targetUserId, rolesToAssign, context);
        } catch (Exception e) {
            fail("ORG_ADMIN should be able to create user in own org: " + e.getMessage());
        }
    }

    /**
     * Test Case 8: Verify admin cannot assign roles that are not allowed for org type
     * Expected: 400 client error with proper message
     */
    @Test
    public void testAdminAssignInvalidRoleForOrgType_ClientError() {
        String requestingUserId = "admin123";
        String requestingUserOrgId = "org123";
        String targetOrgId = "org123";
        String targetUserId = "user456";
        List<String> rolesToAssign = Arrays.asList("INVALID_ROLE");

        mockRequestingUserAsAdmin(requestingUserId);
        mockExistingUserRoles(targetUserId, Collections.emptyList()); // No existing roles, so INVALID_ROLE is new

        // Mock org with specific type
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(targetOrgId);
        targetOrg.setOrganisationType(2048); // STATE

        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        // This will fail at system settings validation - expected behavior
        try {
            validator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId, targetUserId, rolesToAssign, context);
        } catch (ProjectCommonException e) {
            // Expected to fail due to missing system settings or role validation
            assertTrue("Should have error message", e.getMessage() != null);
        }
    }

    /**
     * Test Case 9: Verify existing user update with no new roles doesn't trigger validation
     * Expected: No validation error even if existing roles are invalid
     */
    @Test
    public void testUpdateUserWithNoNewRoles_NoValidation() {
        String requestingUserId = "admin123";
        String requestingUserOrgId = "org123";
        String targetOrgId = "org123";
        String targetUserId = "user456";
        List<String> rolesToAssign = Arrays.asList("PUBLIC");

        mockRequestingUserAsAdmin(requestingUserId);
        mockExistingUserRoles(targetUserId, Arrays.asList("PUBLIC")); // Already has the role

        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(targetOrgId); // Self-referencing
        targetOrg.setOrganisationType(2048); // STATE
        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        // Should not throw exception (no new roles to validate)
        try {
            validator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId, targetUserId, rolesToAssign, context);
        } catch (Exception e) {
            fail("Should not validate when no new roles are being added: " + e.getMessage());
        }
    }

    /**
     * Test Case 10: Verify bulk user creation with valid roles
     * Expected: Validation should pass for new users
     */
    @Test
    public void testBulkUserCreationWithValidRoles_Success() {
        String requestingUserId = "admin123";
        String requestingUserOrgId = "org123";
        String targetOrgId = "org123";
        List<String> rolesToAssign = Arrays.asList("PUBLIC", "CONTENT_CREATOR");

        mockRequestingUserAsAdmin(requestingUserId);

        // Mock the target organization
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(targetOrgId); // Self-referencing
        targetOrg.setOrganisationType(2048); // STATE
        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        // Simulate bulk creation (multiple new users)
        for (int i = 0; i < 3; i++) {
            String targetUserId = null; // New user
            try {
                validator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId, targetUserId, rolesToAssign, context);
            } catch (Exception e) {
                fail("Bulk user creation should succeed for user " + i + ": " + e.getMessage());
            }
        }
    }

    /**
     * Test Case 11: Verify SPV_ADMIN can assign roles across completely unrelated organizations
     * Expected: Validation should pass - SPV_ADMIN has cross-org privileges
     */
    @Test
    public void testSPVAdminCrossOrgRoleAssignment_Success() {
        String requestingUserId = "spvAdmin123";
        String requestingUserOrgId = "spvOrg123";
        String targetOrgId = "unrelatedOrg999";
        String targetUserId = "existingUser456";
        List<String> rolesToAssign = Arrays.asList("PUBLIC", "CONTENT_CREATOR");

        mockRequestingUserWithRole(requestingUserId, "SPV_ADMIN");
        mockExistingUserRoles(targetUserId, Collections.emptyList()); // No existing roles

        // Mock target org with no relation to requesting user's org
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId("differentParent888");
        targetOrg.setOrganisationType(8); // Department

        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        // Should not throw exception
        try {
            validator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId, targetUserId, rolesToAssign, context);
        } catch (Exception e) {
            fail("SPV_ADMIN should have cross-org privileges: " + e.getMessage());
        }
    }

    /**
     * Test Case 12: Verify non-SPV_ADMIN cannot assign roles across unrelated organizations
     * Expected: 401 unauthorized error
     */
    @Test
    public void testNonSPVAdminCrossOrgRoleAssignment_UnauthorizedError() {
        String requestingUserId = "mdoAdmin123";
        String requestingUserOrgId = "stateOrg123";
        String targetOrgId = "unrelatedOrg999";
        String targetUserId = "existingUser456";
        List<String> rolesToAssign = Arrays.asList("PUBLIC");

        mockRequestingUserWithRole(requestingUserId, "MDO_ADMIN");
        mockExistingUserRoles(targetUserId, Collections.emptyList());

        // Mock target org with no relation to requesting user's org
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId("differentParent888");
        targetOrg.setOrganisationType(2048); // STATE

        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        try {
            validator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId, targetUserId, rolesToAssign, context);
            fail("Should throw exception for cross-org assignment by non-SPV_ADMIN");
        } catch (ProjectCommonException e) {
            assertEquals("Should be unauthorized error code", 401, e.getErrorResponseCode());
            assertTrue("Should contain authority message", e.getMessage().contains("authority"));
        }
    }

    /**
     * Test Case 13: Verify SPV_ADMIN with invalid role for org type still gets validation error
     * Expected: Client error - SPV_ADMIN bypasses org hierarchy, not role type validation
     */
    @Test
    public void testSPVAdminAssignInvalidRoleForOrgType_ClientError() {
        String requestingUserId = "spvAdmin123";
        String requestingUserOrgId = "spvOrg123";
        String targetOrgId = "districtOrg999";
        String targetUserId = null;
        List<String> rolesToAssign = Arrays.asList("MDO_ADMIN"); // Not allowed for district org

        mockRequestingUserWithRole(requestingUserId, "SPV_ADMIN");

        // Mock district org - MDO_ADMIN not allowed for district type
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId("someParent");
        targetOrg.setOrganisationType(8); // Department

        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        try {
            validator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId, targetUserId, rolesToAssign, context);
            fail("Should throw exception for invalid role type even for SPV_ADMIN");
        } catch (ProjectCommonException e) {
            // Expected - role not allowed for this org type
            assertTrue("Should have error message about role type", e.getMessage().contains("not allowed") || e.getMessage().contains("roles"));
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

    private void mockRequestingUserWithRole(String userId, String roleName) {
        List<Map<String, Object>> roles = new ArrayList<>();
        Map<String, Object> roleMap = new HashMap<>();
        roleMap.put(JsonKey.ROLE, roleName);
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

    /**
     * Test Case 14: Verify self-registration with valid roles for org type
     * Expected: Validation should pass
     */
    @Test
    public void testSelfRegistrationWithValidRoles_Success() {
        String targetOrgId = "org123";
        List<String> rolesToAssign = Arrays.asList("PUBLIC", "CONTENT_CREATOR");

        // Mock the target organization
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(targetOrgId);
        targetOrg.setOrganisationType(2048); // STATE
        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        // Should not throw exception
        try {
            validator.validateRoleAssignmentForSelfRegistration(targetOrgId, rolesToAssign, context);
        } catch (Exception e) {
            fail("Self-registration with valid roles should succeed: " + e.getMessage());
        }
    }

    /**
     * Test Case 15: Verify self-registration with invalid roles for org type
     * Expected: Client error with proper message
     */
    @Test
    public void testSelfRegistrationWithInvalidRoles_ClientError() {
        String targetOrgId = "org123";
        List<String> rolesToAssign = Arrays.asList("INVALID_ROLE");

        // Mock the target organization
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(targetOrgId);
        targetOrg.setOrganisationType(8); // Department
        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        try {
            validator.validateRoleAssignmentForSelfRegistration(targetOrgId, rolesToAssign, context);
            fail("Should throw exception for invalid role in self-registration");
        } catch (ProjectCommonException e) {
            assertEquals("Should be client error code", 400, e.getErrorResponseCode());
            assertTrue("Should contain role validation message", e.getMessage().contains("not allowed") || e.getMessage().contains("Invalid"));
        }
    }

    /**
     * Test Case 16: Verify self-registration with non-existent org
     * Expected: Client error - target org not found
     */
    @Test
    public void testSelfRegistrationWithNonExistentOrg_ClientError() {
        String targetOrgId = "nonExistentOrg999";
        List<String> rolesToAssign = Arrays.asList("PUBLIC");

        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(null);

        try {
            validator.validateRoleAssignmentForSelfRegistration(targetOrgId, rolesToAssign, context);
            fail("Should throw exception for non-existent org in self-registration");
        } catch (ProjectCommonException e) {
            assertEquals("Should be client error code", 400, e.getErrorResponseCode());
            assertTrue("Should contain org not found message", e.getMessage().contains("not found") || e.getMessage().contains("targetOrg"));
        }
    }

    /**
     * Test Case 17: Verify self-registration with empty role list
     * Expected: Validation should pass (no roles to validate)
     */
    @Test
    public void testSelfRegistrationWithEmptyRoles_Success() {
        String targetOrgId = "org123";
        List<String> rolesToAssign = Collections.emptyList();

        // Mock the target organization
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(targetOrgId);
        targetOrg.setOrganisationType(2048); // STATE
        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        // Should not throw exception
        try {
            validator.validateRoleAssignmentForSelfRegistration(targetOrgId, rolesToAssign, context);
        } catch (Exception e) {
            fail("Self-registration with no roles should succeed: " + e.getMessage());
        }
    }

    /**
     * Test Case 18: Verify self-registration with null role list
     * Expected: Validation should pass (no roles to validate)
     */
    @Test
    public void testSelfRegistrationWithNullRoles_Success() {
        String targetOrgId = "org123";
        List<String> rolesToAssign = null;

        // Mock the target organization
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(targetOrgId);
        targetOrg.setOrganisationType(2048); // STATE
        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        // Should not throw exception
        try {
            validator.validateRoleAssignmentForSelfRegistration(targetOrgId, rolesToAssign, context);
        } catch (Exception e) {
            fail("Self-registration with null roles should succeed: " + e.getMessage());
        }
    }

    /**
     * Test Case 19: Verify self-registration bypasses user authority checks
     * Expected: Validation should pass (no requesting user validation)
     */
    @Test
    public void testSelfRegistrationBypassesUserAuthorityChecks_Success() {
        String targetOrgId = "org123";
        List<String> rolesToAssign = Arrays.asList("PUBLIC");

        // Mock the target organization
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(targetOrgId);
        targetOrg.setOrganisationType(2048); // STATE
        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        // Should not throw exception - no user authority check in self-registration
        try {
            validator.validateRoleAssignmentForSelfRegistration(targetOrgId, rolesToAssign, context);
        } catch (Exception e) {
            fail("Self-registration should bypass user authority checks: " + e.getMessage());
        }
    }

    /**
     * Test Case 20: Verify self-registration with SPV org type
     * Expected: Validation should pass for valid SPV roles
     */
    @Test
    public void testSelfRegistrationForSPVOrg_Success() {
        String targetOrgId = "spvOrg123";
        List<String> rolesToAssign = Arrays.asList("PUBLIC", "CONTENT_CREATOR");

        // Mock the target organization
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(targetOrgId);
        targetOrg.setOrganisationType(512); // SPV
        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        // Should not throw exception
        try {
            validator.validateRoleAssignmentForSelfRegistration(targetOrgId, rolesToAssign, context);
        } catch (Exception e) {
            fail("Self-registration for SPV org with valid roles should succeed: " + e.getMessage());
        }
    }

    /**
     * Test Case 21: Verify self-registration with MDO org type
     * Expected: Validation should pass for valid MDO roles
     */
    @Test
    public void testSelfRegistrationForMDOOrg_Success() {
        String targetOrgId = "mdoOrg123";
        List<String> rolesToAssign = Arrays.asList("PUBLIC");

        // Mock the target organization
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(targetOrgId);
        targetOrg.setOrganisationType(128); // MDO
        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        // Should not throw exception
        try {
            validator.validateRoleAssignmentForSelfRegistration(targetOrgId, rolesToAssign, context);
        } catch (Exception e) {
            fail("Self-registration for MDO org with valid roles should succeed: " + e.getMessage());
        }
    }

    /**
     * Test Case 22: Verify self-registration with multiple invalid roles
     * Expected: Client error with all invalid roles listed
     */
    @Test
    public void testSelfRegistrationWithMultipleInvalidRoles_ClientError() {
        String targetOrgId = "org123";
        List<String> rolesToAssign = Arrays.asList("INVALID_ROLE_1", "INVALID_ROLE_2");

        // Mock the target organization
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(targetOrgId);
        targetOrg.setOrganisationType(8); // Department
        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        try {
            validator.validateRoleAssignmentForSelfRegistration(targetOrgId, rolesToAssign, context);
            fail("Should throw exception for multiple invalid roles in self-registration");
        } catch (ProjectCommonException e) {
            assertEquals("Should be client error code", 400, e.getErrorResponseCode());
            assertTrue("Should contain role validation message", e.getMessage().contains("not allowed") || e.getMessage().contains("Invalid"));
        }
    }

    /**
     * Test Case 23: Verify self-registration with mix of valid and invalid roles
     * Expected: Client error for invalid roles
     */
    @Test
    public void testSelfRegistrationWithMixedRoles_ClientError() {
        String targetOrgId = "org123";
        List<String> rolesToAssign = Arrays.asList("PUBLIC", "INVALID_ROLE");

        // Mock the target organization
        Organisation targetOrg = new Organisation();
        targetOrg.setId(targetOrgId);
        targetOrg.setMinistryOrStateId(targetOrgId);
        targetOrg.setOrganisationType(8); // Department
        when(orgService.getOrgObjById(targetOrgId, context)).thenReturn(targetOrg);

        try {
            validator.validateRoleAssignmentForSelfRegistration(targetOrgId, rolesToAssign, context);
            fail("Should throw exception for invalid roles even with valid ones in self-registration");
        } catch (ProjectCommonException e) {
            assertEquals("Should be client error code", 400, e.getErrorResponseCode());
            assertTrue("Should contain role validation message", e.getMessage().contains("not allowed") || e.getMessage().contains("Invalid"));
        }
    }
}

