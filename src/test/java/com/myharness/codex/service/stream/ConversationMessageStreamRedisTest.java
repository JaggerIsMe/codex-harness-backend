package com.myharness.codex.service.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.MessageStateVO;
import com.myharness.codex.mapper.ConversationMapper;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.*;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Starts its own Redis in a JUnit temporary directory; never connects to application Redis. */
@EnabledIfSystemProperty(named="redis.integration",matches="true")
class ConversationMessageStreamRedisTest {
    @TempDir static Path temp;
    private static Process process;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final Map<Long,ConversationMessagePO> rows=new LinkedHashMap<>();
    private ConversationMapper mapper;
    private RedisMessageStreamStore store;
    private ConversationMessageStream module;
    private ConversationTurnPO turn;
    private boolean failWrites;

    @BeforeAll static void startRedis() throws Exception {
        int port;try(ServerSocket socket=new ServerSocket(0)){port=socket.getLocalPort();}
        process=new ProcessBuilder(System.getProperty("redis.executable","redis-server"),"--bind","127.0.0.1","--port",String.valueOf(port),"--save","","--appendonly","no")
                .directory(temp.toFile()).redirectErrorStream(true).redirectOutput(temp.resolve("redis.log").toFile()).start();
        factory=new LettuceConnectionFactory("127.0.0.1",port);factory.afterPropertiesSet();
        redis=new StringRedisTemplate(factory);redis.afterPropertiesSet();
        boolean ready=false;
        for(int i=0;i<100;i++) {
            try(org.springframework.data.redis.connection.RedisConnection connection=factory.getConnection()) {connection.ping();ready=true;break;}
            catch(Exception ignored){Thread.sleep(50);}
        }
        assertTrue(ready,"Disposable Redis did not start; inspect redis.log");
    }
    @AfterAll static void stopRedis() throws Exception {
        if(factory!=null) factory.destroy();
        if(process!=null){
            process.destroy();
            if(!process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)) {process.destroyForcibly();process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}
            process.getInputStream().close();process.getErrorStream().close();process.getOutputStream().close();
            // Windows can release the redirected log handle just after process termination.
            for(int i=0;i<20;i++) try {java.nio.file.Files.deleteIfExists(temp.resolve("redis.log"));break;}
                catch(java.nio.file.FileSystemException exception){Thread.sleep(50);}
        }
    }
    @BeforeEach void setup() {
        // This connection exclusively belongs to the process launched above.
        try(org.springframework.data.redis.connection.RedisConnection connection=factory.getConnection()){connection.serverCommands().flushDb();}
        mapper=mock(ConversationMapper.class);
        ConversationPO conversation=new ConversationPO();conversation.setId(5L);conversation.setDeviceId(3L);conversation.setUserId(9L);
        turn=new ConversationTurnPO();turn.setId(7L);turn.setConversationId(5L);turn.setStatus("RUNNING");
        when(mapper.selectConversation(5L)).thenReturn(conversation);
        when(mapper.selectTurn(7L)).thenReturn(turn);when(mapper.selectLatestTurn(5L)).thenReturn(turn);
        AtomicLong ids=new AtomicLong();
        when(mapper.nextSequence(5L)).thenAnswer(call -> ids.get()+1);
        when(mapper.selectLogicalMessage(eq(5L),anyString())).thenAnswer(call -> rows.values().stream().filter(m -> m.getMessageKey().equals(call.getArgument(1))).findFirst().map(this::copy).orElse(null));
        when(mapper.insertLogicalMessage(any())).thenAnswer(call -> {ConversationMessagePO m=call.getArgument(0);m.setId(ids.incrementAndGet());rows.put(m.getId(),copy(m));return 1;});
        when(mapper.saveLogicalMessage(any())).thenAnswer(call -> {
            if(failWrites) throw new org.springframework.dao.DataAccessResourceFailureException("SQL unavailable");
            ConversationMessagePO m=call.getArgument(0);ConversationMessagePO old=rows.get(m.getId());
            if(old==null || m.getRevision()>=old.getRevision()) rows.put(m.getId(),copy(m));return 1;
        });
        when(mapper.selectTurnMessages(7L)).thenAnswer(call -> rows.values().stream().map(this::copy).collect(Collectors.toList()));
        when(mapper.selectMessagePage(eq(5L),anyLong(),anyInt())).thenAnswer(call -> rows.values().stream()
                .filter(m -> (long)call.getArgument(1)==0 || m.getSequenceNo()<(long)call.getArgument(1))
                .sorted(Comparator.comparing(ConversationMessagePO::getSequenceNo).reversed()).limit((int)call.getArgument(2)).map(this::copy).collect(Collectors.toList()));
        TransactionTemplate tx=new TransactionTemplate(){@Override public <T>T execute(TransactionCallback<T> action){return action.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));}};
        store=new RedisMessageStreamStore(redis,json,3,60);
        module=new ConversationMessageStream(mapper,store,new MessageReducer(),tx,mock(ClientEventWebSocketHandler.class));
    }
    private ConversationMessagePO copy(ConversationMessagePO m){return json.convertValue(m,ConversationMessagePO.class);}
    private ObjectNode event(long seq,String item,String type,String text){ObjectNode value=json.createObjectNode().put("conversationId",5).put("turnId",7).put("eventSeq",seq).put("itemId",item).put("eventType",type);if(text!=null)value.put("content",text);return value;}
    private ObjectNode complete(long seq,String item,String text){ObjectNode value=event(seq,item,"ITEM_COMPLETED",null);value.putObject("details").put("type","agentMessage").put("text",text);return value;}

    @Test void manyDeltasProduceOneRowAndFinalSnapshotDoesNotDuplicateContent() {
        for(int i=1;i<=30;i++) assertTrue(module.accept(3L,event(i,"answer","AGENT_MESSAGE_DELTA","x")));
        assertEquals(1,rows.size());verify(mapper,never()).saveLogicalMessage(any());
        assertTrue(module.accept(3L,complete(31,"answer",String.join("",Collections.nCopies(30,"x")))));
        assertEquals(30,rows.get(1L).getContent().length());assertEquals("COMPLETED",rows.get(1L).getStatus());
        assertFalse(module.accept(3L,complete(31,"answer","ignored")));
        verify(mapper,times(1)).insertLogicalMessage(any());verify(mapper,times(1)).saveLogicalMessage(any());
    }
    @Test void replayWindowGapRequestsResetAndCurrentSnapshotIsComplete() {
        for(int i=1;i<=5;i++) module.accept(3L,event(i,"answer","AGENT_MESSAGE_DELTA","x"));
        MessageStateVO old=module.state(5L,0,200,7L,0);
        assertTrue(old.isResetRequired());assertEquals("xxxxx",old.getMessages().get(0).getContent());
        MessageStateVO recent=module.state(5L,0,200,7L,3);
        assertFalse(recent.isResetRequired());assertEquals(2,recent.getUpdates().size());
    }
    @Test void interleavedItemsKeepFirstAppearanceOrder() {
        module.accept(3L,event(1,"a","AGENT_MESSAGE_DELTA","a"));
        module.accept(3L,event(2,"b","AGENT_MESSAGE_DELTA","b"));
        module.accept(3L,event(3,"a","AGENT_MESSAGE_DELTA","c"));
        MessageStateVO state=module.state(5L,0,200,null,-1);
        assertEquals("ac",state.getMessages().get(0).getContent());assertEquals("b",state.getMessages().get(1).getContent());
    }
    @Test void sequenceGapDoesNotMutateSnapshot() {
        module.accept(3L,event(1,"a","AGENT_MESSAGE_DELTA","a"));
        assertThrows(IllegalArgumentException.class,() -> module.accept(3L,event(3,"a","AGENT_MESSAGE_DELTA","c")));
        assertEquals(1,store.cursor(7L));
    }
    @Test void interruptedTurnSavesPartialContentAndExpiresOnlyAfterSuccessfulPersistence() {
        module.accept(3L,event(1,"a","AGENT_MESSAGE_DELTA","partial"));
        failWrites=true;
        assertThrows(org.springframework.dao.DataAccessException.class,() -> module.finish(3L,5L,7L,"INTERRUPTED",1L));
        assertTrue(store.pending().contains("5:7"));assertEquals(-1,redis.getExpire("harness:messages:7:snapshot"));
        failWrites=false;turn.setStatus("INTERRUPTED");module.checkpoint();
        assertEquals("partial",rows.get(1L).getContent());assertEquals("INTERRUPTED",rows.get(1L).getStatus());
        assertFalse(store.pending().contains("5:7"));assertTrue(redis.getExpire("harness:messages:7:snapshot")>0);
    }
    @Test void completedItemSqlFailureIsRetriedFromRedis() {
        module.accept(3L,event(1,"a","AGENT_MESSAGE_DELTA","draft"));failWrites=true;
        assertThrows(org.springframework.dao.DataAccessException.class,() -> module.accept(3L,complete(2,"a","final")));
        failWrites=false;module.checkpoint();
        assertEquals("final",rows.get(1L).getContent());assertEquals("COMPLETED",rows.get(1L).getStatus());
    }
    @Test void periodicCheckpointUpdatesSameRowAndDoesNotRepeatUnchangedWrites() {
        module.accept(3L,event(1,"a","AGENT_MESSAGE_DELTA","draft"));module.checkpoint();module.checkpoint();
        verify(mapper,times(1)).saveLogicalMessage(any());assertEquals(1,rows.size());
    }
    @Test void rejectsForeignDeviceAndWrongTurn() {
        assertThrows(IllegalArgumentException.class,() -> module.accept(4L,event(1,"a","AGENT_MESSAGE_DELTA","private")));
        assertEquals(0,rows.size());
    }
    @Test void commandCompletionClosesItsOutputWithoutAppendingFullOutputTwice() {
        module.accept(3L,event(1,"cmd","COMMAND_OUTPUT_DELTA","abc"));
        ObjectNode complete=event(2,"cmd","COMMAND_COMPLETED",null);
        complete.putObject("details").put("type","commandExecution").put("aggregatedOutput","abcdef").put("exitCode",0);
        module.accept(3L,complete);
        assertEquals("abcdef",rows.get(1L).getContent());assertEquals("COMPLETED",rows.get(1L).getStatus());
    }
    @Test void activeItemBeforeTheHistoryPageStillHasARecoveryBase() {
        module.accept(3L,event(1,"early","AGENT_MESSAGE_DELTA","early"));
        module.accept(3L,event(2,"late","AGENT_MESSAGE_DELTA","late"));
        MessageStateVO state=module.state(5L,0,1,null,-1);
        assertEquals(2,state.getMessages().size());assertEquals("early",state.getMessages().get(0).getContent());
    }
    @Test void terminalMissingWatermarkMarksContentIncomplete() {
        module.accept(3L,event(1,"a","AGENT_MESSAGE_DELTA","a"));
        module.finish(3L,5L,7L,"COMPLETED",2L);
        assertEquals("INCOMPLETE",rows.get(1L).getStatus());
    }
    @Test void terminalCacheDoesNotExpireBeforeOuterTransactionCommits() {
        module.accept(3L,event(1,"a","AGENT_MESSAGE_DELTA","a"));
        TransactionSynchronizationManager.initSynchronization();
        try {
            module.finish(3L,5L,7L,"FAILED",1L);
            assertEquals(-1,redis.getExpire("harness:messages:7:snapshot"));
            assertTrue(store.pending().contains("5:7"));
            for(TransactionSynchronization sync:TransactionSynchronizationManager.getSynchronizations()) sync.afterCommit();
            assertTrue(redis.getExpire("harness:messages:7:snapshot")>0);
        } finally {TransactionSynchronizationManager.clearSynchronization();}
    }
    @Test void redisOutageReturnsDegradedCheckpointAndCanSaveAnAbnormalEnding() {
        module.accept(3L,event(1,"a","AGENT_MESSAGE_DELTA","checkpoint"));module.checkpoint();
        MessageStreamStore unavailable=mock(MessageStreamStore.class);
        when(unavailable.cursor(7L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Redis unavailable"));
        when(unavailable.messages(7L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Redis unavailable"));
        TransactionTemplate tx=new TransactionTemplate(){@Override public <T>T execute(TransactionCallback<T> action){return action.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));}};
        ConversationMessageStream degraded=new ConversationMessageStream(mapper,unavailable,new MessageReducer(),tx,mock(ClientEventWebSocketHandler.class));
        assertTrue(degraded.state(5L,0,200,null,-1).isDegraded());
        degraded.finish(3L,5L,7L,"FAILED",null);
        assertEquals("checkpoint",rows.get(1L).getContent());assertEquals("INCOMPLETE",rows.get(1L).getStatus());
    }
    @Test void redisReplayIsBoundedByBytesAsWellAsCount() {
        char[] chars=new char[1048576];Arrays.fill(chars,'x');String body=new String(chars);
        for(int i=1;i<=10;i++) module.accept(3L,event(i,"item-"+i,"AGENT_MESSAGE_DELTA",body));
        assertTrue(Long.parseLong(redis.opsForHash().get("harness:messages:7:meta","eventBytes").toString())<=8388608);
        assertTrue(store.replay(7L,0).size()<10);
        assertEquals(10,store.messages(7L).size());
    }
}
