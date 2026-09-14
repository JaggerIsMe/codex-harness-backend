package com.myharness.codex.service;

import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.*;
import com.myharness.codex.support.IsolatedMysql;
import com.myharness.codex.exception.BusinessException;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="mysql.usage",matches="true")
class ModelUsageMysqlTest {
    static IsolatedMysql mysql;static JdbcTemplate jdbc;static SqlSessionTemplate sessions;static TransactionTemplate tx;
    ModelUsageMapper mapper;ModelUsageService service;ConversationMapper conversations;AuthorizationService access;DeviceAuthenticationService auth;
    ConversationTurnPO turn;ModelRuntimeDTO runtime;Clock clock;
    @BeforeAll static void database() throws Exception {
        mysql=IsolatedMysql.start();mysql.applyResource("db/schema.sql");mysql.applyResource("db/migration-managed-model-usage.sql");
        var cfg=new Configuration();cfg.setMapUnderscoreToCamelCase(true);cfg.addMapper(ModelUsageMapper.class);
        var f=new SqlSessionFactoryBean();f.setDataSource(mysql.dataSource());f.setConfiguration(cfg);
        sessions=new SqlSessionTemplate(f.getObject());tx=new TransactionTemplate(new DataSourceTransactionManager(mysql.dataSource()));
        jdbc=new JdbcTemplate(mysql.dataSource());
        jdbc.update("INSERT INTO sys_user(id,email,display_name,status) VALUES(1,'creator@example.test','Creator','ENABLED'),(5,'usage@example.test','Usage','ENABLED')");
        jdbc.update("INSERT INTO model_configuration(id,configuration_code,name,status,created_by) VALUES(1,'fixture','Fixture','ENABLED',1)");
        jdbc.update("INSERT INTO model_configuration_version(id,model_configuration_id,version_no,configuration_code,name,runtime_spec,encrypted_api_key,config_digest) VALUES(1,1,1,'fixture','Fixture','{\"modelId\":\"fixture-model\"}','unused',?)","a".repeat(64));
    }
    @AfterAll static void stop() throws Exception {if(mysql!=null)mysql.close();}
    @BeforeEach void setup() {
        for(String table:List.of("user_quota_ledger","model_usage_record","user_quota_bucket","user_quota_policy","model_price_version"))jdbc.update("DELETE FROM "+table);
        mapper=sessions.getMapper(ModelUsageMapper.class);conversations=mock(ConversationMapper.class);access=mock(AuthorizationService.class);auth=mock(DeviceAuthenticationService.class);
        var device=new AgentDevicePO();device.setId(3L);when(auth.authenticate("device","Bearer test")).thenReturn(device);
        turn=new ConversationTurnPO();turn.setId(2L);turn.setConversationId(4L);turn.setModelConfigurationVersionId(1L);turn.setStatus("RUNNING");turn.setModelRuntime("snapshot");
        when(conversations.selectTurn(2L)).thenReturn(turn);
        var c=new ConversationPO();c.setId(4L);c.setUserId(5L);c.setDeviceId(3L);c.setStatus("ACTIVE");when(conversations.selectConversation(4L)).thenReturn(c);
        var runtimes=mock(ModelConfigurationService.class);runtime=new ModelRuntimeDTO();runtime.setRuntimeMode("MANAGED_PROVIDER");runtime.setContextWindowTokens(1000);when(runtimes.readSnapshot("snapshot")).thenReturn(runtime);
        var models=mock(ModelConfigurationMapper.class);when(models.version(1L)).thenReturn(new ModelConfigurationVersionPO());
        var users=mock(SysUserMapper.class);when(users.selectById(anyLong())).thenReturn(new SysUserPO());
        clock=mock(Clock.class);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.instant()).thenReturn(Instant.parse("2026-09-30T15:59:59Z"));
        when(clock.withZone(any())).thenAnswer(invocation->Clock.fixed(clock.instant(),invocation.getArgument(0)));
        service=new ModelUsageService(mapper,models,runtimes,conversations,users,access,auth,tx,clock);
        service.savePrice(new UsageDTO.Price(1L,new BigDecimal("10"),new BigDecimal("1"),new BigDecimal("20"),1000),9L);
    }
    String reserve(){String id=UUID.randomUUID().toString();service.reserve(2L,new UsageDTO.Reserve(id),"device","Bearer test");return id;}
    UsageDTO.Settlement usage(String id){return new UsageDTO.Settlement(id,1000L,500L,100L,50L,"r-"+id,"fixture-model","REPORTED");}
    @Test void concurrentReservationsCannotOverspendSameUser() throws Exception {
        service.savePolicy(5L,new UsageDTO.Policy(new BigDecimal("0.03"),new BigDecimal("1"),2),9L);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new CountDownLatch(1);
            Callable<Boolean> action=()->{gate.await();try{reserve();return true;}catch(BusinessException e){return false;}};
            var a=pool.submit(action);var b=pool.submit(action);gate.countDown();
            assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_record",Integer.class));
        assertEquals(0,new BigDecimal("0.03").compareTo(mapper.bucket(5L,"2026-09").reserved));
    }
    @Test void concurrentDuplicateSettlementAndPriceChangesUseFrozenPrice() throws Exception {
        String id=reserve();
        service.savePrice(new UsageDTO.Price(1L,new BigDecimal("100"),new BigDecimal("10"),new BigDecimal("200"),1000),9L);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=pool.submit(()->service.settle(2L,usage(id),"device","Bearer test"));
            var b=pool.submit(()->service.settle(2L,usage(id),"device","Bearer test"));
            a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
        }
        assertEquals("SETTLED",mapper.request(id).state);
        assertEquals(0,new BigDecimal("0.0075").compareTo(mapper.bucket(5L,"2026-09").spent));
        assertEquals(0,BigDecimal.ZERO.compareTo(mapper.bucket(5L,"2026-09").reserved));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_quota_ledger WHERE action='SETTLE'",Integer.class));
        assertThrows(BusinessException.class,()->service.reserve(2L,new UsageDTO.Reserve(id),"device","Bearer test"));
    }
    @Test void missingUsageKeepsReservationAndManualReleaseRequiresTerminalTurnAndReason() {
        String id=reserve();
        service.settle(2L,new UsageDTO.Settlement(id,null,null,null,null,null,null,"INTERRUPTED"),"device","Bearer test");
        assertEquals("PENDING",mapper.request(id).state);
        assertEquals(0,new BigDecimal("0.03").compareTo(mapper.bucket(5L,"2026-09").reserved));
        var resolve=new UsageDTO.Resolve("供应商确证未收费",true,null,null,null,null);
        assertThrows(BusinessException.class,()->service.resolve(id,resolve,9L));
        turn.setStatus("FAILED");service.resolve(id,resolve,9L);
        assertEquals("RELEASED",mapper.request(id).state);
        service.settle(2L,usage(id),"device","Bearer test");
        assertEquals(0,BigDecimal.ZERO.compareTo(mapper.bucket(5L,"2026-09").spent));
    }
    @Test void lateSettlementStaysInOriginalDayAndMonthAndAggregatesExcludeUnknownUsage() {
        String id=reserve();turn.setStatus("COMPLETED");
        when(clock.instant()).thenReturn(Instant.parse("2026-09-30T16:00:01Z"));
        service.settle(2L,usage(id),"device","Bearer test");
        var p=service.records(5L,null,null,null,LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),"",1,30,5L);
        assertEquals(1,p.total());assertEquals("2026-09-30",p.daily().getFirst().dimension());assertEquals(1000,p.daily().getFirst().inputTokens());
        assertEquals("2026-09-30",mapper.request(id).dayPeriod);
        assertNull(mapper.bucket(5L,"2026-10"));
    }
    @Test void partialUsageRetainsKnownCountsWithoutInventingCostAndCanBeReconciled() {
        String id=reserve();
        service.settle(2L,new UsageDTO.Settlement(id,1000L,null,100L,50L,"r","fixture-model","REPORTED"),"device","Bearer test");
        var pending=mapper.request(id);
        assertEquals("PENDING",pending.state);assertEquals(1000L,pending.inputTokens);
        assertEquals(100L,pending.outputTokens);assertNull(pending.cachedTokens);assertNull(pending.cost);
        assertEquals(0,new BigDecimal("0.03").compareTo(mapper.bucket(5L,"2026-09").reserved));
        service.settle(2L,usage(id),"device","Bearer test");
        assertEquals("SETTLED",mapper.request(id).state);
        assertEquals(0,new BigDecimal("0.0075").compareTo(mapper.bucket(5L,"2026-09").spent));
    }
    @Test void wrongDeviceAndLocalCodexCannotEnterLedgerAndQueriesEnforceOwnership() {
        var other=new AgentDevicePO();other.setId(6L);when(auth.authenticate("other","Bearer test")).thenReturn(other);
        assertThrows(BusinessException.class,()->service.reserve(2L,new UsageDTO.Reserve(UUID.randomUUID().toString()),"other","Bearer test"));
        runtime.setRuntimeMode("LOCAL_CODEX");assertThrows(BusinessException.class,this::reserve);
        doThrow(new BusinessException(com.myharness.codex.entity.enums.ErrorCode.FORBIDDEN)).when(access).requirePermission(6L,"system:user:manage");
        assertThrows(BusinessException.class,()->service.summary(5L,6L));
        assertThrows(BusinessException.class,()->service.records(null,null,null,null,null,null,"",1,30,6L));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_record",Integer.class));
    }
    @Test void unknownModelRemainsPendingAndZeroBudgetBlocksRequests() {
        String id=reserve();service.settle(2L,new UsageDTO.Settlement(id,1000L,500L,100L,50L,"r","unexpected","REPORTED"),"device","Bearer test");
        assertEquals("PENDING",mapper.request(id).state);
        service.savePolicy(5L,new UsageDTO.Policy(BigDecimal.ZERO,null,2),9L);
        assertThrows(BusinessException.class,this::reserve);
    }
    @Test void transactionFailureRollsBackReservationAndDailyAndMonthlyBuckets() {
        jdbc.execute("CREATE TRIGGER usage_fixture_reject BEFORE INSERT ON user_quota_ledger FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture rollback'");
        try {assertThrows(RuntimeException.class,this::reserve);}finally{jdbc.execute("DROP TRIGGER usage_fixture_reject");}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_record",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_quota_bucket",Integer.class));
    }
}
