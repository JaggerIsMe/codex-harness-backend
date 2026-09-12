package com.myharness.codex.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.dto.SkillExpertAssignmentDTO;
import com.myharness.codex.entity.dto.SkillExpertBatchAssignmentDTO;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.SkillExpertAssignmentVO.*;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.AuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;

/** Selected Skill Versions are patched atomically per expert; published snapshots remain immutable. */
@Service
public class ExpertSkillAssignmentService {
    private final SkillMapper skills;
    private final ExpertMapper experts;
    private final SkillExpertAssignmentMapper assignments;
    private final AuthorizationService access;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public ExpertSkillAssignmentService(SkillMapper skills,ExpertMapper experts,SkillExpertAssignmentMapper assignments,
            AuthorizationService access,TransactionTemplate tx,ObjectMapper json) {
        this.skills=skills;this.experts=experts;this.assignments=assignments;this.access=access;this.tx=tx;this.json=json;
    }
    public Page candidates(Long skillId,Long versionId,String keyword,int page,int size,Long user) {
        return candidates(List.of(new SkillExpertBatchAssignmentDTO.Target(skillId,versionId)),keyword,page,size,user);
    }
    public Page candidates(List<SkillExpertBatchAssignmentDTO.Target> input,String keyword,int page,int size,Long user) {
        authorize(user); pagination(page,size);
        List<Target> targets=targets(input);
        String search=keyword==null ? "" : keyword.trim();
        if(search.length()>128) throw conflict("搜索内容过长");
        String versionIds=write(targets.stream().map(Target::versionId).toList());
        return new Page(assignments.candidates(versionIds,search,size,(page-1)*size).stream()
                .map(e -> candidate(e,targets)).toList(),assignments.count(versionIds,search),page,size);
    }
    public Preview preview(SkillExpertAssignmentDTO input,Long user) {
        if(input==null) throw conflict("请选择 Skill 版本");
        return preview(new SkillExpertBatchAssignmentDTO(List.of(new SkillExpertBatchAssignmentDTO.Target(input.skillId(),input.versionId())),input.expertIds()),user);
    }
    public Preview preview(SkillExpertBatchAssignmentDTO input,Long user) {
        authorize(user);
        if(input==null || input.expertIds()==null || input.expertIds().isEmpty() || input.expertIds().size()>50
                || input.expertIds().stream().anyMatch(Objects::isNull) || new HashSet<>(input.expertIds()).size()!=input.expertIds().size())
            throw conflict("请选择 1～50 位不重复的专家");
        return tx.execute(status -> {
            skills.lockCatalog(); List<Target> targets=targets(input.targets());
            List<Candidate> items=input.expertIds().stream().sorted().map(id -> candidate(required(id),targets)).toList();
            String id=UUID.randomUUID().toString();LocalDateTime expires=LocalDateTime.now().plusHours(1);
            Target first=targets.getFirst();
            Preview view=new Preview(id,first.skillName(),first.version(),items,expires,targets);
            // Keep the original columns as the first target anchor; the complete immutable selection is in payload.
            assignments.insert(new SkillExpertAssignmentBatchPO(id,user,first.skillId(),first.versionId(),write(view),false,expires,null));
            return view;
        });
    }
    public Submission commit(String id,Long user) {
        authorize(user);
        SkillExpertAssignmentBatchPO batch=tx.execute(status -> {
            skills.lockCatalog();var current=owned(id,user);
            if(!current.started()) {
                if(current.expiresAt().isBefore(LocalDateTime.now())) throw conflict("预览已过期，请重新预览");
                var preview=read(current.payload(),Preview.class);
                if(preview.items().stream().anyMatch(i -> i.action().equals("BLOCKED"))) throw conflict("请移除不可分配的专家并重新预览");
                validateTargets(batchTargets(current));
                assignments.start(id);
            } else if(current.expiresAt().isBefore(LocalDateTime.now())) throw conflict("提交恢复期限已过，请查询分配记录");
            return current;
        });
        List<Target> targets=batchTargets(batch);
        for(Candidate expected:read(batch.payload(),Preview.class).items()) {
            try {
                tx.executeWithoutResult(status -> {
                    skills.lockCatalog();
                    if(assignments.processed(id,expected.expertId())>0) return;
                    validateTargets(targets);
                    ExpertPO expert=experts.lock(expected.expertId());
                    if(expert==null) throw conflict("专家不存在");
                    Candidate actual=candidate(expert,targets);
                    if(actual.action().equals("BLOCKED")) throw conflict(actual.reason());
                    if(actual.action().equals("SKIP")) {
                        record(id,new Result(expert.getId(),expert.getName(),"SKIP",actual.draftVersionId(),batch.versionId(),"SKIPPED","草稿已绑定全部目标版本",expert.getRevision(),actual.changes()));
                        return;
                    }
                    if(!Objects.equals(expert.getRevision(),expected.revision())) throw conflict("专家配置已变化，请重新预览");
                    List<Long> next=new ArrayList<>(ids(expert.getSkillVersionIds()));
                    for(Change change:actual.changes()) {
                        if(change.action().equals("SKIP")) continue;
                        if(change.draftVersionId()!=null) next.remove(change.draftVersionId());
                        next.add(change.versionId());
                    }
                    assignments.assign(expert.getId(),write(next));
                    record(id,new Result(expert.getId(),expert.getName(),actual.action(),actual.draftVersionId(),batch.versionId(),
                            "SUCCESS","已更新草稿，发布后生效",expert.getRevision()+1,actual.changes()));
                });
            } catch(RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Expert Skill assignment failed for {}",expected.expertId(),error);
                tx.executeWithoutResult(status -> {
                    skills.lockCatalog();
                    if(assignments.processed(id,expected.expertId())==0)
                        record(id,new Result(expected.expertId(),expected.name(),expected.action(),expected.draftVersionId(),batch.versionId(),
                                "FAILED",error instanceof BusinessException ? error.getMessage() : "保存失败，请重新预览后重试",null,expected.changes()));
                });
            }
        }
        return submission(id,user);
    }
    public Submission submission(String id,Long user) {
        authorize(user);var batch=assignments.get(id);
        // Submitted operations are shared audit records; unsubmitted previews remain private.
        if(batch==null || (!batch.started() && !Objects.equals(batch.ownerId(),user)))
            throw new BusinessException(ErrorCode.NOT_FOUND,"分配记录不存在");
        return submission(batch,user);
    }
    private Submission submission(SkillExpertAssignmentBatchPO batch,Long user) {
        var preview=read(batch.payload(),Preview.class);
        var targets=batchTargets(batch);
        String id=batch.id();
        List<Result> items=assignments.results(id).stream().map(r -> read(r.payload(),Result.class)).toList();
        Map<Long,Result> byExpert=new HashMap<>();
        for(var item:items) byExpert.put(item.expertId(),item);
        long success=0,failed=0,skipped=0,bindingSuccess=0,bindingFailed=0,bindingSkipped=0;
        for(var expected:preview.items()) {
            var item=byExpert.get(expected.expertId());
            if(item==null) continue;
            switch(item.status()) {
                case "SUCCESS" -> {
                    success++;
                    for(var target:targets) {
                        String action=item.action();
                        if(item.changes()!=null) action=item.changes().stream()
                                .filter(change -> Objects.equals(change.skillId(),target.skillId()))
                                .map(Change::action).findFirst().orElse(action);
                        if("SKIP".equals(action)) bindingSkipped++; else bindingSuccess++;
                    }
                }
                // A failed expert transaction rolls back every selected binding together.
                case "FAILED" -> {failed++;bindingFailed+=targets.size();}
                case "SKIPPED" -> {skipped++;bindingSkipped+=targets.size();}
                default -> { /* Unrecognized or missing outcomes remain pending, never successful. */ }
            }
        }
        long total=preview.items().size(),bindingTotal=total*targets.size();
        var expertResults=new Counts(total,success,failed,skipped,total-success-failed-skipped);
        var bindingResults=new Counts(bindingTotal,bindingSuccess,bindingFailed,bindingSkipped,
                bindingTotal-bindingSuccess-bindingFailed-bindingSkipped);
        boolean complete=expertResults.pendingCount()==0;
        String ownerName=assignments.ownerName(batch.ownerId());
        return new Submission(id,preview.skillName(),preview.version(),batch.started(),complete,items,targets,
                expertResults,bindingResults,batch.ownerId(),ownerName==null?"用户 #"+batch.ownerId():ownerName,
                !complete && Objects.equals(batch.ownerId(),user) && !batch.expiresAt().isBefore(LocalDateTime.now()));
    }
    public List<History> history(int page,int size,Long user) {
        authorize(user);pagination(page,size);
        return assignments.history(size,(page-1)*size).stream().map(b -> {
            var result=submission(b,user);
            return new History(b.id(),result.skillName(),result.version(),b.createdAt(),
                    result.expertResults().successCount(),result.expertResults().failedCount(),result.expertResults().skippedCount(),result.targets(),
                    result.expertResults(),result.bindingResults(),result.ownerId(),result.ownerName(),result.complete());
        }).toList();
    }
    private Candidate candidate(ExpertPO expert,List<Target> targets) {
        List<Long> ids=ids(expert.getSkillVersionIds());
        ExpertVersionPO published=expert.getPublishedVersionId()==null ? null : experts.version(expert.getPublishedVersionId());
        List<Long> publishedIds=published==null ? List.of() : ids(published.getSkillVersionIds());
        Map<Long,SkillVersionPO> draftBindings=bindings(ids),publishedBindings=bindings(publishedIds);
        List<Change> changes=targets.stream().map(t -> {
            var current=draftBindings.get(t.skillId());var released=publishedBindings.get(t.skillId());
            String action=current==null ? "ADD" : Objects.equals(current.getId(),t.versionId()) ? "SKIP" : "REPLACE";
            return new Change(t.skillId(),t.versionId(),t.skillName(),t.version(),current==null?null:current.getId(),
                    current==null?null:current.getVersion(),released==null?null:released.getVersion(),action);
        }).toList();
        String action=changes.stream().allMatch(c -> c.action().equals("SKIP")) ? "SKIP"
                : changes.stream().anyMatch(c -> c.action().equals("REPLACE")) ? "REPLACE" : "ADD";
        String reason="";
        if("DISABLED".equals(expert.getStatus())) {action="BLOCKED";reason="专家已禁用";}
        else if(ids.size()+changes.stream().filter(c -> c.action().equals("ADD")).count()>30) {action="BLOCKED";reason="专家最多绑定 30 个 Skill";}
        Change first=changes.getFirst();
        return new Candidate(expert.getId(),expert.getName(),expert.getStatus(),expert.getRevision(),first.draftVersionId(),
                first.draftVersion(),first.publishedVersion(),action,reason,changes);
    }
    private Map<Long,SkillVersionPO> bindings(List<Long> ids) {
        Map<Long,SkillVersionPO> result=new HashMap<>();
        for(Long id:ids) {var version=skills.selectVersion(id);if(version!=null) result.put(version.getSkillId(),version);}
        return result;
    }
    private List<Target> targets(List<SkillExpertBatchAssignmentDTO.Target> input) {
        if(input==null || input.isEmpty() || input.size()>30 || input.stream().anyMatch(Objects::isNull)
                || input.stream().map(SkillExpertBatchAssignmentDTO.Target::skillId).distinct().count()!=input.size())
            throw conflict("请选择 1～30 个不同 Skill，每个 Skill 只能选择一个版本");
        return input.stream().map(t -> {
            var version=target(t.skillId(),t.versionId());
            return new Target(t.skillId(),t.versionId(),skills.selectSkill(t.skillId()).getSkillName(),version.getVersion());
        }).toList();
    }
    private void validateTargets(List<Target> targets) {for(var t:targets) target(t.skillId(),t.versionId());}
    private List<Target> batchTargets(SkillExpertAssignmentBatchPO batch) {
        Preview preview=read(batch.payload(),Preview.class);
        return preview.targets()==null || preview.targets().isEmpty()
                ? List.of(new Target(batch.skillId(),batch.versionId(),preview.skillName(),preview.version())) : preview.targets();
    }
    private SkillVersionPO target(Long skillId,Long versionId) {
        if(skillId==null || versionId==null) throw conflict("请选择 Skill 版本");
        var skill=skills.selectSkill(skillId);var version=skills.selectVersion(versionId);
        if(skill==null || !"ENABLED".equals(skill.getStatus()) || version==null || !Objects.equals(version.getSkillId(),skillId) || !"ACTIVE".equals(version.getStatus()))
            throw conflict("Skill 或版本已停用，请重新选择");return version;
    }
    private ExpertPO required(Long id) {var value=experts.get(id);if(value==null) throw conflict("专家不存在");return value;}
    private SkillExpertAssignmentBatchPO owned(String id,Long user) {
        var value=assignments.get(id);
        if(value==null || !Objects.equals(value.ownerId(),user)) throw new BusinessException(ErrorCode.NOT_FOUND,"分配记录不存在");return value;
    }
    private void record(String id,Result result) {assignments.result(new SkillExpertAssignmentItemPO(id,result.expertId(),write(result)));}
    private void authorize(Long user) {access.requirePermission(user,"skill:manage");access.requirePermission(user,"expert:manage");}
    private void pagination(int page,int size) {if(page<1 || page>100000 || size<1 || size>50) throw conflict("分页参数不正确");}
    private List<Long> ids(String value) {try{return json.readValue(value,new TypeReference<List<Long>>(){});}catch(Exception e){throw new IllegalStateException("Invalid expert Skills",e);}}
    private String write(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private <T>T read(String value,Class<T> type) {try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException(e);}}
    private BusinessException conflict(String message) {return new BusinessException(ErrorCode.CONFLICT,message);}
}
