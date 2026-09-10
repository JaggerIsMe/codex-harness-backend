package com.myharness.codex.service;

import com.myharness.codex.entity.po.ConversationAttachmentPO;
import com.myharness.codex.mapper.ConversationAttachmentMapper;
import com.myharness.codex.mapper.ConversationMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Projects confirmed Workspace results onto associations, inside the caller's Project transaction. */
@Service
public class WorkspaceAttachmentLocationService {
    private final ConversationAttachmentMapper attachments;
    private final ConversationMapper conversations;

    public WorkspaceAttachmentLocationService(ConversationAttachmentMapper attachments, ConversationMapper conversations) {
        this.attachments = attachments;
        this.conversations = conversations;
    }

    public long countAvailable(Long projectId, String sourcePath) {
        return attachments.countAvailableAtLocation(projectId, sourcePath);
    }

    public void claim(Long projectId, String sourcePath, Long operationId) {
        for (var attachment : lockAssociations(projectId, sourcePath)) {
            if (!"AVAILABLE".equals(attachment.getWorkspaceLocationState())
                    || Objects.equals(operationId, attachment.getLastFileOperationId())) continue;
            if (attachments.claimLocation(attachment.getId(), attachment.getLocationRevision(), operationId) != 1)
                throw new IllegalStateException("Attachment location changed while claiming Workspace operation");
        }
    }

    public void apply(Long projectId, String sourcePath, String targetPath, Long operationId, String status,
            List<String> deletedPaths, List<String> uncertainPaths) {
        if (!Set.of("SUCCEEDED", "FAILED", "PARTIAL_FAILED", "UNKNOWN").contains(status))
            throw new IllegalArgumentException("Workspace result is not final or uncertain");
        for (var attachment : lockAssociations(projectId, sourcePath)) {
            if (!Objects.equals(operationId, attachment.getLastFileOperationId())
                    || !Set.of("AVAILABLE", "UNKNOWN").contains(attachment.getWorkspaceLocationState())) continue;
            String previousPath = attachment.getWorkspacePath();
            String nextPath = previousPath;
            String nextState = "AVAILABLE";
            if ("UNKNOWN".equals(status)) {
                nextState = "UNKNOWN";
            } else if ("SUCCEEDED".equals(status)) {
                if (targetPath == null) nextState = "MISSING";
                else nextPath = targetPath + previousPath.substring(sourcePath.length());
            } else if ("PARTIAL_FAILED".equals(status)) {
                if (includes(deletedPaths, previousPath)) nextState = "MISSING";
                else if (includes(uncertainPaths, previousPath)) nextState = "UNKNOWN";
            }
            if (previousPath.equals(nextPath) && nextState.equals(attachment.getWorkspaceLocationState())) continue;
            if (attachments.updateLocation(attachment.getId(), attachment.getLocationRevision(), operationId, nextPath, nextState) != 1)
                throw new IllegalStateException("Attachment location changed while applying Workspace result");
        }
    }

    private List<ConversationAttachmentPO> lockAssociations(Long projectId, String path) {
        var candidates = attachments.atLocation(projectId, path);
        for (Long conversationId : candidates.stream().map(ConversationAttachmentPO::getConversationId).distinct().sorted().toList())
            conversations.lockConversation(conversationId);
        return candidates.stream().map(ConversationAttachmentPO::getId).sorted().map(attachments::lock)
                .filter(Objects::nonNull)
                .filter(value -> projectId.equals(value.getProjectId()) && contains(path, value.getWorkspacePath()))
                .filter(value -> Set.of("PENDING", "ATTACHED").contains(value.getStatus())).toList();
    }

    private static boolean includes(List<String> paths, String candidate) {
        return paths != null && paths.stream().anyMatch(path -> contains(path, candidate));
    }

    private static boolean contains(String parent, String candidate) {
        return parent != null && !parent.isEmpty() && candidate != null
                && (parent.equals(candidate) || candidate.startsWith(parent + "/"));
    }
}
