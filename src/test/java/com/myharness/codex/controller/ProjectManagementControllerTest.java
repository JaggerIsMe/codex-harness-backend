package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.UpdateConversationDTO;
import com.myharness.codex.entity.dto.UpdateProjectDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.ConversationPO;
import com.myharness.codex.entity.po.ProjectPO;
import com.myharness.codex.entity.vo.ConversationVO;
import com.myharness.codex.entity.vo.ProjectVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.exception.GlobalExceptionHandler;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.security.UserPrincipal;
import com.myharness.codex.service.ProjectManagementService;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectManagementControllerTest {
    private ProjectManagementService service;
    private MockMvc mvc;

    @BeforeEach void setup() {
        service=mock(ProjectManagementService.class);
        mvc=MockMvcBuilders.standaloneSetup(new ProjectManagementController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UserContext.set(new UserPrincipal(3L,"user","User"));
    }
    @AfterEach void cleanup() {UserContext.clear();}

    @Test void putNamesUsesTheCurrentOwnerAndUnifiedResponseEnvelope() throws Exception {
        var project=new ProjectPO();project.setId(7L);project.setProjectName("New project");
        when(service.updateProject(7L,new UpdateProjectDTO("New project"),3L)).thenReturn(new ProjectVO(project));
        mvc.perform(put("/api/v1/projects/7").contentType(MediaType.APPLICATION_JSON).content("{\"projectName\":\"New project\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.projectName").value("New project"));
        var conversation=new ConversationPO();conversation.setId(8L);conversation.setTitle("New conversation");
        when(service.updateConversation(7L,8L,new UpdateConversationDTO("New conversation"),3L)).thenReturn(new ConversationVO(conversation));
        mvc.perform(put("/api/v1/projects/7/conversations/8").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"New conversation\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("New conversation"));
    }

    @Test void blankMissingAndOverlongNamesAreRejectedBeforeServiceCalls() throws Exception {
        for(String body:List.of("{}","{\"projectName\":null}","{\"projectName\":\"  \"}","{\"projectName\":\""+"a".repeat(129)+"\"}"))
            mvc.perform(put("/api/v1/projects/7").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        for(String body:List.of("{}","{\"title\":null}","{\"title\":\"  \"}","{\"title\":\""+"a".repeat(256)+"\"}"))
            mvc.perform(put("/api/v1/projects/7/conversations/8").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void deletesReturnTheUnifiedSuccessResponse() throws Exception {
        for(String path:List.of("/api/v1/projects/7","/api/v1/projects/7/conversations/8"))
            mvc.perform(delete(path)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("success"))
                    .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data").isEmpty());
        verify(service).deleteProject(7L,3L);verify(service).deleteConversation(7L,8L,3L);
    }

    @Test void runningTurnConflictsReachTheClientAs409() throws Exception {
        doThrow(new BusinessException(ErrorCode.CONFLICT,"请先停止任务")).when(service).deleteConversation(7L,8L,3L);
        mvc.perform(delete("/api/v1/projects/7/conversations/8")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409)).andExpect(jsonPath("$.info").value("请先停止任务"));
    }

    @Test void noAuthenticatedUserCannotDeleteRecords() throws Exception {
        UserContext.clear();
        mvc.perform(delete("/api/v1/projects/7")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
