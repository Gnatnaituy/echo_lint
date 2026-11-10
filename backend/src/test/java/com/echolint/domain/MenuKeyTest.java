package com.echolint.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.echolint.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 菜单目录规则：角色允许集、生效集合（收窄/回退）、写入校验与全选折叠、改角色重绑定。 */
class MenuKeyTest {

    @Test
    void allowedForAdminIsEveryMenuInDeclarationOrder() {
        assertEquals(List.of(MenuKey.values()), MenuKey.allowedFor(UserRole.ADMIN));
        assertEquals(15, MenuKey.allowedFor(UserRole.ADMIN).size());
    }

    @Test
    void allowedForNonAdminExcludesAdminOnlyMenus() {
        for (UserRole role : List.of(UserRole.REVIEWER, UserRole.VIEWER)) {
            List<MenuKey> allowed = MenuKey.allowedFor(role);
            assertEquals(9, allowed.size());
            assertTrue(allowed.stream().noneMatch(MenuKey::isAdminOnly));
            assertFalse(allowed.contains(MenuKey.COSTS));
            assertFalse(allowed.contains(MenuKey.USERS));
            assertEquals(MenuKey.DASHBOARD, allowed.get(0));
            assertEquals(MenuKey.SETTINGS, allowed.get(allowed.size() - 1));
        }
    }

    @Test
    void effectiveFallsBackToAllowedWhenStoredIsBlank() {
        assertEquals(MenuKey.allowedFor(UserRole.VIEWER), MenuKey.effective(UserRole.VIEWER, null));
        assertEquals(
                MenuKey.allowedFor(UserRole.VIEWER), MenuKey.effective(UserRole.VIEWER, "   "));
        assertEquals(MenuKey.allowedFor(UserRole.ADMIN), MenuKey.effective(UserRole.ADMIN, null));
    }

    @Test
    void effectiveIntersectsStoredWithAllowedInEnumOrder() {
        // 大小写宽松 + 枚举顺序（与请求顺序无关）
        assertEquals(
                List.of(MenuKey.DASHBOARD, MenuKey.REPORTS),
                MenuKey.effective(UserRole.REVIEWER, "reports,dashboard"));
        // 越权/未知项在读路径被跳过；仍有合法交集时按交集收窄
        assertEquals(
                List.of(MenuKey.DASHBOARD),
                MenuKey.effective(UserRole.REVIEWER, "DASHBOARD,COSTS"));
    }

    @Test
    void effectiveFallsBackWhenIntersectionIsEmpty() {
        // 存储值全部不可用于该角色：回退角色允许的全部菜单（fail-open 到角色权限）
        assertEquals(
                MenuKey.allowedFor(UserRole.VIEWER), MenuKey.effective(UserRole.VIEWER, "USERS"));
        assertEquals(
                MenuKey.allowedFor(UserRole.VIEWER),
                MenuKey.effective(UserRole.VIEWER, "NOT_A_MENU"));
    }

    @Test
    void effectiveSkipsUnknownStoredKeys() {
        assertEquals(
                List.of(MenuKey.RECORDINGS),
                MenuKey.effective(UserRole.ADMIN, "RECORDINGS,NOT_A_MENU"));
        assertEquals(
                MenuKey.allowedFor(UserRole.ADMIN), MenuKey.effective(UserRole.ADMIN, "GHOST"));
    }

    @Test
    void effectiveNamesExposeEnumNamesInOrder() {
        assertEquals(
                List.of("DASHBOARD", "REPORTS"),
                MenuKey.effectiveNames(UserRole.VIEWER, "REPORTS,DASHBOARD"));
        assertEquals(9, MenuKey.effectiveNames(UserRole.REVIEWER, null).size());
    }

    @Test
    void isRestrictedOnlyForNonBlankStoredValue() {
        assertFalse(MenuKey.isRestricted(null));
        assertFalse(MenuKey.isRestricted(""));
        assertFalse(MenuKey.isRestricted("  "));
        assertTrue(MenuKey.isRestricted("DASHBOARD"));
    }

    @Test
    void parseSelectionRejectsEmptyOrBlankOnlySelection() {
        BizException empty =
                assertThrows(
                        BizException.class,
                        () -> MenuKey.parseSelection(List.of(), UserRole.ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatus());
        assertTrue(empty.getMessage().contains("至少"));

        BizException blankOnly =
                assertThrows(
                        BizException.class,
                        () -> MenuKey.parseSelection(List.of("  "), UserRole.ADMIN));
        assertTrue(blankOnly.getMessage().contains("至少"));
    }

    @Test
    void parseSelectionRejectsUnknownMenu() {
        BizException e =
                assertThrows(
                        BizException.class,
                        () ->
                                MenuKey.parseSelection(
                                        List.of("DASHBOARD", "GHOST"), UserRole.ADMIN));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertEquals("未知菜单: GHOST", e.getMessage());
    }

    @Test
    void parseSelectionRejectsAdminOnlyMenuForNonAdmin() {
        for (UserRole role : List.of(UserRole.REVIEWER, UserRole.VIEWER)) {
            BizException e =
                    assertThrows(
                            BizException.class,
                            () -> MenuKey.parseSelection(List.of("COSTS"), role));
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
            assertTrue(e.getMessage().contains("不可绑定菜单: COSTS"));
        }
    }

    @Test
    void parseSelectionDeduplicatesInEnumOrder() {
        assertEquals(
                List.of(MenuKey.DASHBOARD, MenuKey.REVIEW),
                MenuKey.parseSelection(List.of("review", "DASHBOARD", "review"), UserRole.VIEWER));
        assertEquals(
                List.of(MenuKey.COSTS, MenuKey.USERS),
                MenuKey.parseSelection(List.of("USERS", "COSTS"), UserRole.ADMIN));
    }

    @Test
    void toStoredFoldsFullSelectionToNull() {
        assertNull(MenuKey.toStored(MenuKey.allowedFor(UserRole.ADMIN), UserRole.ADMIN));
        assertNull(MenuKey.toStored(MenuKey.allowedFor(UserRole.VIEWER), UserRole.VIEWER));
    }

    @Test
    void toStoredKeepsSubsetInEnumOrder() {
        assertEquals(
                "DASHBOARD,REPORTS",
                MenuKey.toStored(List.of(MenuKey.REPORTS, MenuKey.DASHBOARD), UserRole.ADMIN));
        assertEquals(
                "DASHBOARD,USERS",
                MenuKey.toStored(List.of(MenuKey.USERS, MenuKey.DASHBOARD), UserRole.ADMIN));
    }

    @Test
    void rebindForRoleFiltersOldBinding() {
        assertEquals(
                "DASHBOARD,REPORTS",
                MenuKey.rebindForRole("DASHBOARD,REPORTS,COSTS", UserRole.REVIEWER));
        assertEquals("DASHBOARD", MenuKey.rebindForRole("DASHBOARD", UserRole.ADMIN));
        assertNull(MenuKey.rebindForRole(null, UserRole.REVIEWER));
    }

    @Test
    void rebindForRoleFoldsWhenFilteredCoversAllOrNothingRemains() {
        String allNonAdmin =
                String.join(
                        ",",
                        MenuKey.allowedFor(UserRole.VIEWER).stream().map(MenuKey::name).toList());
        // 过滤后覆盖新角色全部菜单 → 等价于不限制
        assertNull(MenuKey.rebindForRole(allNonAdmin, UserRole.VIEWER));
        // 过滤后交集为空 → 回退不限制
        assertNull(MenuKey.rebindForRole("COSTS", UserRole.VIEWER));
    }

    @Test
    void describeForAuditUsesRestrictionAndEffectiveCount() {
        assertEquals("菜单=全部", MenuKey.describe(UserRole.ADMIN, null));
        assertEquals("菜单=2 项", MenuKey.describe(UserRole.REVIEWER, "DASHBOARD,REPORTS"));
        // 越权项被收窄掉：按生效项数描述
        assertEquals("菜单=1 项", MenuKey.describe(UserRole.VIEWER, "DASHBOARD,COSTS"));
    }
}
