package com.youzheng.huicui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.DataRange;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.OrgSystemDtos.OrgDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

class OrgSystemM1ControllerTest {

    @AfterEach
    void tearDown() {
        SubjectContext.clear();
    }

    @Test
    void orgDto公开负责人账号名与完整手机号() {
        List<String> components = Arrays.stream(OrgDto.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertThat(components).containsExactly(
                "id", "type", "name", "ownerAccountId", "ownerUsername", "ownerPhone",
                "status", "ownerSetupToken");
    }

    @Test
    void listOrgs关联负责人账号并查询账号名与完整手机号() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SubjectContext.set(new CurrentSubject(
                "1", "平台管理员", "1", "PLATFORM", "平台", "SA", Set.of(),
                DataRange.UNRESTRICTED));
        OrgSystemM1Controller controller = new OrgSystemM1Controller(
                jdbc, mock(OrgSystemAuditService.class), mock(ObjectMapper.class));

        controller.listOrgs(null, null, 1, 50);

        String listSql = mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("query"))
                .map(invocation -> (String) invocation.getArguments()[0])
                .findFirst()
                .orElseThrow();
        assertThat(listSql)
                .contains("FROM org o LEFT JOIN account owner ON owner.id = o.owner_account_id")
                .contains("owner.username AS owner_username")
                .contains("owner.phone AS owner_phone");
    }
}
