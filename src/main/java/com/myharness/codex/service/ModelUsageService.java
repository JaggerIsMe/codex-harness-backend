package com.myharness.codex.service;

import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.UsageVO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class ModelUsageService {
    private static final BigDecimal ZERO=BigDecimal.ZERO;
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final ModelUsageMapper mapper;
    private final ModelConfigurationMapper models;
    private final ModelConfigurationService runtimes;
    private final ConversationMapper conversations;
    private final SysUserMapper users;
    private final AuthorizationService access;
    private final DeviceAuthenticationService devices;
    private final TransactionTemplate tx;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public ModelUsageService(ModelUsageMapper mapper, ModelConfigurationMapper models, ModelConfigurationService runtimes,
        ConversationMapper conversations, SysUserMapper users, AuthorizationService access, DeviceAuthenticationService devices,
        org.springframework.transaction.PlatformTransactionManager manager) {
        this(mapper,models,runtimes,conversations,users,access,devices,new TransactionTemplate(manager),Clock.systemUTC());
    }
    ModelUsageService(ModelUsageMapper mapper, ModelConfigurationMapper models, ModelConfigurationService runtimes,
        ConversationMapper conversations, SysUserMapper users, AuthorizationService access, DeviceAuthenticationService devices,
        TransactionTemplate tx,Clock clock) {
        this.mapper=mapper;this.models=models;this.runtimes=runtimes;this.conversations=conversations;
        this.users=users;this.access=access;this.devices=devices;this.tx=tx;this.clock=clock;
    }
    public List<UsageVO.Price> prices(Long actor){access.requirePermission(actor,"model:manage");return mapper.prices().stream().map(UsageVO.Price::new).toList();}
    public UsageVO.Price savePrice(UsageDTO.Price dto,Long actor) {
        access.requirePermission(actor,"model:manage");
        return tx.execute(s->{
            if(models.version(dto.modelVersionId())==null)throw missing();
            ModelPricePO p=new ModelPricePO();p.modelVersionId=dto.modelVersionId();p.inputRate=dto.inputRate();
            p.cachedRate=dto.cachedRate();p.outputRate=dto.outputRate();p.maxOutputTokens=dto.maxOutputTokens();p.createdBy=actor;
            mapper.insertPrice(p);return new UsageVO.Price(mapper.price(p.id));
        });
    }
    public UsageVO.Policy policy(Long uid,Long actor){access.requirePermission(actor,"system:user:manage");requireUser(uid);return new UsageVO.Policy(policyOrDefault(uid));}
    public UsageVO.Policy savePolicy(Long uid,UsageDTO.Policy dto,Long actor) {
        access.requirePermission(actor,"system:user:manage");requireUser(uid);
        return tx.execute(s->{UserQuotaPO p=lock(uid);p.dailyBudget=dto.dailyBudget();p.monthlyBudget=dto.monthlyBudget();p.maxConcurrentTurns=dto.maxConcurrentTurns();
            mapper.updatePolicy(p);mapper.ledger(null,uid,"POLICY_CHANGED",ZERO,actor,"daily="+p.dailyBudget+", monthly="+p.monthlyBudget+", concurrency="+p.maxConcurrentTurns);
            return new UsageVO.Policy(p);});
    }
    /** Called inside the existing Turn creation transaction, after client request deduplication. */
    public void acquireUser(Long uid){lock(uid);}
    public void checkStart(Long uid,Long modelVersionId) {
        if(modelVersionId==null)return;
        UserQuotaPO p=lock(uid);
        if(mapper.currentPrice(modelVersionId)==null)throw conflict("托管模型尚未配置价格，请联系管理员");
        if(mapper.activeTurns(uid)>=p.maxConcurrentTurns)throw conflict("已达到托管模型并发 Turn 上限");
        LocalDate day=LocalDate.now(clock.withZone(ZONE));
        check(uid,day.toString(),p.dailyBudget,ZERO,true);check(uid,YearMonth.from(day).toString(),p.monthlyBudget,ZERO,true);
    }
    public UsageVO.Permit reserve(Long tid,UsageDTO.Reserve dto,String code,String auth) {
        Long deviceId=devices.authenticate(code,auth).getId();
        ConversationTurnPO initial=turn(tid,deviceId);
        Long uid=conversations.selectConversation(initial.getConversationId()).getUserId();
        return tx.execute(s->{
            UserQuotaPO policy=lock(uid);
            ConversationTurnPO t=turn(tid,deviceId);ConversationPO c=conversations.selectConversation(t.getConversationId());
            if(!uid.equals(c.getUserId()))throw missing();
            access.requireDevice(c.getUserId(),deviceId);access.requirePermission(c.getUserId(),"turn:start");
            if(!Set.of("CREATED","RUNNING","WAITING_APPROVAL").contains(t.getStatus()) || !"ACTIVE".equals(c.getStatus()))throw conflict("Turn 已结束，不能继续请求模型");
            var runtime=runtimes.readSnapshot(t.getModelRuntime());
            if(runtime==null || !"MANAGED_PROVIDER".equals(runtime.getRuntimeMode()))throw conflict("该 Turn 不属于托管模型");
            UsageRecordPO old=mapper.lockRequest(dto.requestId());
            if(old!=null)throw conflict("请求已授权或已结算，不能重复发送上游请求");
            if(mapper.activeTurns(c.getUserId())>policy.maxConcurrentTurns)throw conflict("已达到托管模型并发 Turn 上限");
            ModelPricePO price=mapper.currentPrice(t.getModelConfigurationVersionId());
            if(price==null)throw conflict("托管模型尚未配置价格，请联系管理员");
            BigDecimal reserve=ModelUsagePricing.reserve(price,runtime.getContextWindowTokens(),dto.compact()?runtime.getContextWindowTokens():price.maxOutputTokens);
            LocalDate day=LocalDate.now(clock.withZone(ZONE));String month=YearMonth.from(day).toString();
            check(c.getUserId(),day.toString(),policy.dailyBudget,reserve,false);check(c.getUserId(),month,policy.monthlyBudget,reserve,false);
            UsageRecordPO r=new UsageRecordPO();r.requestId=dto.requestId();r.userId=c.getUserId();r.turnId=tid;r.deviceId=deviceId;
            r.modelVersionId=t.getModelConfigurationVersionId();r.priceVersionId=price.id;r.reservedAmount=reserve;r.state="RESERVED";
            r.projectId=c.getProjectId();r.conversationId=c.getId();r.expertVersionId=t.getExpertVersionId();
            r.dayPeriod=day.toString();r.monthPeriod=month;r.createdAt=LocalDateTime.now(clock);
            mapper.insertUsage(r);change(r,ZERO,reserve);mapper.ledger(r.requestId,r.userId,"RESERVE",reserve,null,null);
            return new UsageVO.Permit(r.requestId,price.maxOutputTokens,reserve);
        });
    }
    public void settle(Long tid,UsageDTO.Settlement dto,String code,String auth) {
        Long did=devices.authenticate(code,auth).getId();
        UsageRecordPO found=mapper.request(dto.requestId());
        if(found==null || !found.turnId.equals(tid) || !found.deviceId.equals(did))throw missing();
        tx.executeWithoutResult(s->{
            lock(found.userId);UsageRecordPO r=mapper.lockRequest(dto.requestId());
            settleLocked(r,dto,null,null);
        });
    }
    public void resolve(String rid,UsageDTO.Resolve dto,Long actor) {
        access.requirePermission(actor,"system:user:manage");
        tx.executeWithoutResult(s->{
            UsageRecordPO found=mapper.request(rid);if(found==null)throw missing();lock(found.userId);UsageRecordPO r=mapper.lockRequest(rid);
            var t=conversations.selectTurn(r.turnId);
            if(t!=null && Set.of("CREATED","RUNNING","WAITING_APPROVAL").contains(t.getStatus()))throw conflict("请先结束 Turn，再核实未结算请求");
            if(!Set.of("RESERVED","PENDING").contains(r.state))throw conflict("请求已结算");
            if(dto.noCharge()) {
                change(r,ZERO,r.reservedAmount.negate());r.state="RELEASED";r.cost=ZERO;r.settledAt=LocalDateTime.now(clock);
                mapper.updateUsage(r);mapper.ledger(rid,r.userId,"RELEASE",r.reservedAmount,actor,dto.reason());
            } else {
                var u=new UsageDTO.Settlement(rid,dto.inputTokens(),dto.cachedTokens(),dto.outputTokens(),dto.reasoningTokens(),r.responseId,r.actualModel,"VERIFIED");
                if(!ModelUsagePricing.complete(u))throw conflict("请输入完整且一致的 token 用量");
                settleLocked(r,u,actor,dto.reason());
            }
        });
    }
    private void settleLocked(UsageRecordPO r,UsageDTO.Settlement u,Long actor,String reason) {
        if("RELEASED".equals(r.state))return;
        if("SETTLED".equals(r.state)) {
            if(ModelUsagePricing.complete(u) && (!Objects.equals(r.inputTokens,u.inputTokens()) || !Objects.equals(r.cachedTokens,u.cachedTokens())
                || !Objects.equals(r.outputTokens,u.outputTokens()) || !Objects.equals(r.reasoningTokens,u.reasoningTokens())))
                throw conflict("重复结算的 token 数据不一致");
            return;
        }
        ModelPricePO price=mapper.price(r.priceVersionId);
        boolean differentModel=actor==null && u.actualModel()!=null && !u.actualModel().isBlank() && !Objects.equals(u.actualModel(),price.modelId);
        if(!ModelUsagePricing.complete(u) || differentModel) {
            boolean firstPending=!"PENDING".equals(r.state);
            r.state="PENDING";r.outcome=differentModel?"MODEL_MISMATCH":u.outcome();r.responseId=u.responseId();r.actualModel=u.actualModel();
            if(validCount(u.inputTokens()))r.inputTokens=u.inputTokens();
            if(validCount(u.outputTokens()))r.outputTokens=u.outputTokens();
            if(validCount(u.cachedTokens()) && r.inputTokens!=null && u.cachedTokens()<=r.inputTokens)r.cachedTokens=u.cachedTokens();
            if(validCount(u.reasoningTokens()) && r.outputTokens!=null && u.reasoningTokens()<=r.outputTokens)r.reasoningTokens=u.reasoningTokens();
            mapper.updateUsage(r);
            if(firstPending)mapper.ledger(r.requestId,r.userId,"PENDING",ZERO,null,"用量缺失、不一致或实际模型不匹配，保留预占");
            return;
        }
        BigDecimal cost=ModelUsagePricing.cost(price,u);
        r.inputTokens=u.inputTokens();r.cachedTokens=u.cachedTokens();r.outputTokens=u.outputTokens();r.reasoningTokens=u.reasoningTokens();
        r.responseId=u.responseId();r.actualModel=u.actualModel();r.outcome=u.outcome();r.cost=cost;r.state="SETTLED";r.settledAt=LocalDateTime.now(clock);
        change(r,cost,r.reservedAmount.negate());mapper.updateUsage(r);
        mapper.ledger(r.requestId,r.userId,"SETTLE",cost,actor,reason);
        mapper.ledger(r.requestId,r.userId,"UNRESERVE",r.reservedAmount,actor,reason);
    }
    public UsageVO.Summary summary(Long uid,Long actor) {
        ownerOrAdmin(uid,actor);UserQuotaPO p=policyOrDefault(uid);LocalDate day=LocalDate.now(clock.withZone(ZONE));
        return new UsageVO.Summary(new UsageVO.Policy(p),List.of(viewBucket(uid,day.toString(),p.dailyBudget),viewBucket(uid,YearMonth.from(day).toString(),p.monthlyBudget)));
    }
    public UsageVO.Page records(Long uid,Long model,Long turn,Long project,LocalDate start,LocalDate end,String state,int page,int size,Long actor) {
        if(uid==null)access.requirePermission(actor,"system:user:manage");else ownerOrAdmin(uid,actor);
        if(start==null)start=LocalDate.now(clock.withZone(ZONE)).withDayOfMonth(1);
        if(end==null)end=LocalDate.now(clock.withZone(ZONE));
        if(end.isBefore(start) || end.toEpochDay()-start.toEpochDay()>366)throw conflict("查询日期范围最多 367 天");
        if(!Set.of("","RESERVED","PENDING","SETTLED","RELEASED").contains(state))throw conflict("无效的状态");
        if(page<1 || page>10000 || size<1 || size>100)throw conflict("无效的分页");
        LocalDateTime from=start.atStartOfDay(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime to=end.plusDays(1).atStartOfDay(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return new UsageVO.Page(mapper.records(uid,model,turn,project,from,to,state,size,(page-1)*size).stream().map(UsageVO.Record::new).toList(),
            mapper.count(uid,model,turn,project,from,to,state),page,size,
            mapper.daily(uid,model,turn,project,from,to,state).stream().map(UsageVO.Aggregate::new).toList());
    }
    private void ownerOrAdmin(Long uid,Long actor){if(!Objects.equals(uid,actor))access.requirePermission(actor,"system:user:manage");else access.requireEnabled(actor);}
    private void requireUser(Long uid){if(users.selectById(uid)==null)throw missing();}
    private ConversationTurnPO turn(Long tid,Long did) {
        var t=conversations.selectTurn(tid);if(t==null)throw missing();var c=conversations.selectConversation(t.getConversationId());
        if(c==null || !did.equals(c.getDeviceId()))throw missing();return t;
    }
    private UserQuotaPO lock(Long uid){mapper.ensurePolicy(uid);return mapper.lockPolicy(uid);}
    private UserQuotaPO policyOrDefault(Long uid){UserQuotaPO p=mapper.policy(uid);if(p==null){p=new UserQuotaPO();p.userId=uid;p.maxConcurrentTurns=100;}return p;}
    private void check(Long uid,String period,BigDecimal limit,BigDecimal amount,boolean starting) {
        mapper.ensureBucket(uid,period);QuotaBucketPO b=mapper.bucket(uid,period);
        if(limit!=null && (limit.signum()==0 || b.spent.add(b.reserved).add(amount).compareTo(limit)>0
            || starting && b.spent.add(b.reserved).compareTo(limit)>=0))
            throw conflict("用户预算不足（"+period+"），请联系管理员调整额度");
    }
    private void change(UsageRecordPO r,BigDecimal spent,BigDecimal reserved) {
        mapper.changeBucket(r.userId,r.dayPeriod,spent,reserved);mapper.changeBucket(r.userId,r.monthPeriod,spent,reserved);
    }
    private UsageVO.Bucket viewBucket(Long uid,String period,BigDecimal budget) {
        QuotaBucketPO b=mapper.bucket(uid,period);BigDecimal spent=b==null?ZERO:b.spent,reserved=b==null?ZERO:b.reserved;
        BigDecimal remaining=budget==null?null:budget.subtract(spent).subtract(reserved);
        String alert=budget==null?"NONE":remaining.signum()<=0?"EXHAUSTED":spent.add(reserved).compareTo(budget.multiply(new BigDecimal("0.8")))>=0?"WARNING":"NONE";
        return new UsageVO.Bucket(period,budget,spent,reserved,remaining,alert);
    }
    private static BusinessException conflict(String message){return new BusinessException(ErrorCode.CONFLICT,message);}
    private static boolean validCount(Long count){return count!=null && count>=0 && count<=100_000_000;}
    private static BusinessException missing(){return new BusinessException(ErrorCode.NOT_FOUND,"用量记录或关联对象不存在");}
}
