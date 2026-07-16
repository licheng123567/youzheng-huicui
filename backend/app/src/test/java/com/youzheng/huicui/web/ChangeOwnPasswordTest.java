package com.youzheng.huicui.web;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.DataRange;
import com.youzheng.huicui.security.SubjectContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POST /me/password 的一处**上线拦路 bug**：验证码登录的用户从没设过密码
 * （password_hash=NULL），却被"改密必须输旧密码"挡住 → 永远设不了密码、也就永远
 * 只能用验证码登录。修复：hash 为空时允许直接设初始密码（此端点本就要求已登录，
 * 身份已由 JWT 确认）；hash 非空时仍必须校验旧密码。
 */
class ChangeOwnPasswordTest {

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    @AfterEach
    void tearDown() { SubjectContext.clear(); }

    private ProfileSearchController controllerWithHash(JdbcTemplate jdbc, String hash) {
        // password_hash 可能为 null（验证码用户从没设过密码）→ 用 HashMap（Map.of 不接受 null value）
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("password_hash", hash);
        when(jdbc.queryForMap(anyString(), eq(7L))).thenReturn(row);
        SubjectContext.set(new CurrentSubject("7", "张三", "1", "PROVIDER", "商",
                "CO", Set.of(), DataRange.UNRESTRICTED));
        return new ProfileSearchController(jdbc);
    }

    @Test
    void 从没设过密码_留空旧密码_直接设初始密码成功() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProfileSearchController c = controllerWithHash(jdbc, null);

        Map<String, Object> r = c.changeOwnPassword(Map.of("newPassword", "newpass123"));

        assertThat(r).containsEntry("ok", true);
        // 落库一次：设新 hash + 清 must_change_password
        verify(jdbc, times(1)).update(anyString(), anyString(), eq(7L));
    }

    @Test
    void 已有密码_旧密码正确_改密成功() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProfileSearchController c = controllerWithHash(jdbc, bcrypt.encode("oldpass1"));

        Map<String, Object> r = c.changeOwnPassword(
                Map.of("oldPassword", "oldpass1", "newPassword", "newpass123"));

        assertThat(r).containsEntry("ok", true);
        verify(jdbc, times(1)).update(anyString(), anyString(), eq(7L));
    }

    @Test
    void 已有密码_旧密码错误_拒绝且不落库() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProfileSearchController c = controllerWithHash(jdbc, bcrypt.encode("oldpass1"));

        assertThatThrownBy(() -> c.changeOwnPassword(
                Map.of("oldPassword", "wrongpass", "newPassword", "newpass123")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("旧密码错误");
        verify(jdbc, never()).update(anyString(), any(), any());
    }

    @Test
    void 已有密码_不给旧密码_仍被拒绝() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProfileSearchController c = controllerWithHash(jdbc, bcrypt.encode("oldpass1"));

        assertThatThrownBy(() -> c.changeOwnPassword(Map.of("newPassword", "newpass123")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("旧密码错误");
        verify(jdbc, never()).update(anyString(), any(), any());
    }
}
