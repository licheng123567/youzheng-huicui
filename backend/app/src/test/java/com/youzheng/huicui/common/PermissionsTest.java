package com.youzheng.huicui.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公海可见性收敛（BR-M3-29）的权限点分配。
 *
 * 这里钉的是**产品边界**而不是实现细节：物业角色（PL 物业负责人 / PC 物业协调员）
 * 对公海没有概念——不是「能看但是空的」，是「404 这个页面就不该为他们存在」。
 * 谁哪天给 PL/PC 发了 sea.view，这两条会先红。
 */
class PermissionsTest {

    @Test
    void 公海查看_平台与服务商侧四角色都有() {
        assertTrue(Permissions.of("SA").contains("sea.view"), "SA 平台超管管平台公海");
        assertTrue(Permissions.of("SE").contains("sea.view"), "SE 平台运营做撮合派单");
        assertTrue(Permissions.of("VL").contains("sea.view"), "VL 服务商负责人分配本商公海");
        assertTrue(Permissions.of("CO").contains("sea.view"), "CO 催收员抢单");
    }

    @Test
    void 公海查看_物业角色没有_公海概念对物业不存在() {
        assertFalse(Permissions.of("PL").contains("sea.view"),
                "PL 物业负责人不参与派单/抢单，公海对物业不存在（BR-M3-29）");
        assertFalse(Permissions.of("PC").contains("sea.view"),
                "PC 物业协调员同上");
    }

    @Test
    void 抢单与派单的既有分界不被本次收权动到() {
        assertTrue(Permissions.of("CO").contains("case.claim"), "抢单仅 CO");
        assertFalse(Permissions.of("VL").contains("case.claim"), "VL 是分配(case.assign)不是抢");
        assertTrue(Permissions.of("SA").contains("case.dispatch"), "再派/开放抢单归平台");
        assertFalse(Permissions.of("PL").contains("case.dispatch"));
    }
}
