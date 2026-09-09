package com.myharness.codex.service;

import com.myharness.codex.entity.dto.CreateConversationDTO;
import com.myharness.codex.entity.dto.StartTurnDTO;
import com.myharness.codex.entity.vo.ConversationVO;
import com.myharness.codex.entity.vo.TurnVO;
import com.myharness.codex.entity.vo.ConversationMessageVO;
import com.myharness.codex.entity.vo.ApprovalVO;
import java.util.List;
import com.myharness.codex.entity.vo.MessageStateVO;
import com.myharness.codex.entity.vo.PageVO;

public interface ConversationService {
    ConversationVO createConversation(Long projectId,CreateConversationDTO dto,Long operatorId);
    TurnVO startTurn(Long projectId,Long conversationId,StartTurnDTO dto,Long operatorId);
    void interruptTurn(Long projectId,Long conversationId,Long turnId,Long operatorId);
    PageVO<ConversationVO> getProjectConversations(Long projectId,Long operatorId,int page,int size,String keyword);
    List<ConversationVO> getConversationStatuses(Long projectId,Long operatorId,List<Long> ids);
    ConversationVO getConversation(Long projectId,Long conversationId,Long operatorId);
    TurnVO getActiveTurn(Long projectId,Long conversationId,Long operatorId);
    List<ConversationMessageVO> getMessages(Long projectId,Long conversationId,Long operatorId);
    MessageStateVO getMessageState(Long projectId,Long conversationId,Long operatorId,long before,int limit,Long turnId,long after);
    List<ApprovalVO> getApprovals(Long projectId,Long conversationId,Long operatorId);
}
