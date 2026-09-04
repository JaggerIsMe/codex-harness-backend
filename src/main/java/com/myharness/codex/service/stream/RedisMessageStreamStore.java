package com.myharness.codex.service.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.po.ConversationMessagePO;
import com.myharness.codex.entity.vo.MessageUpdateVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.domain.Range;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

@Component
public class RedisMessageStreamStore implements MessageStreamStore {
    private static final String PENDING = "harness:messages:pending";
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final int replayLimit;
    private final int retention;
    private final DefaultRedisScript<Long> appendScript = new DefaultRedisScript<>();
    private final DefaultRedisScript<Long> closeScript = new DefaultRedisScript<>(
            "for i=3,#ARGV,2 do redis.call('HSET',KEYS[1],ARGV[i],ARGV[i+1]) end " +
                    "redis.call('HSETNX',KEYS[2],'terminal',ARGV[2]); redis.call('HSET',KEYS[2],'closed','1'); redis.call('SADD',KEYS[3],ARGV[1]); return 1", Long.class);

    public RedisMessageStreamStore(StringRedisTemplate redis, ObjectMapper json,
                                   @Value("${harness.message-stream.replay-limit:2000}") int replayLimit,
                                   @Value("${harness.message-stream.retention-seconds:3600}") int retention) {
        if (replayLimit < 1 || retention < 60)
            throw new IllegalArgumentException("Invalid stream retention configuration");
        this.redis = redis;
        this.json = json;
        this.replayLimit = replayLimit;
        this.retention = retention;
        appendScript.setLocation(new ClassPathResource("redis/append-message.lua"));
        appendScript.setResultType(Long.class);
    }

    private String key(Long turn, String suffix) {
        return "harness:messages:" + turn + ":" + suffix;
    }

    @Override
    public long cursor(Long turnId) {
        Object value = redis.opsForHash().get(key(turnId, "meta"), "seq");
        return value == null ? 0 : Long.parseLong(value.toString());
    }

    @Override
    public boolean closed(Long turnId) {
        return redis.opsForHash().hasKey(key(turnId, "meta"), "closed");
    }

    @Override
    public ConversationMessagePO message(Long turnId, String messageKey) {
        Object value = redis.opsForHash().get(key(turnId, "snapshot"), messageKey);
        return value == null ? null : decode(value.toString(), ConversationMessagePO.class);
    }

    @Override
    public List<ConversationMessagePO> messages(Long turnId) {
        List<ConversationMessagePO> values = new ArrayList<>();
        for (Object value : redis.opsForHash().values(key(turnId, "snapshot")))
            values.add(decode(value.toString(), ConversationMessagePO.class));
        return values;
    }

    @Override
    public boolean append(MessageUpdateVO update, List<ConversationMessagePO> messages) {
        Long turn = update.getTurnId();
        List<String> args = new ArrayList<>(Arrays.asList(String.valueOf(update.getCursor()), encode(update), String.valueOf(replayLimit), update.getConversationId() + ":" + turn));
        for (ConversationMessagePO message : messages) {
            args.add(message.getMessageKey());
            args.add(encode(message));
        }
        Long result = redis.execute(appendScript, Arrays.asList(key(turn, "snapshot"), key(turn, "meta"), key(turn, "events"), PENDING), args.toArray());
        if (result == null || result < 0)
            throw new IllegalStateException("Stream rejected: sequence gap, closed Turn or capacity exceeded (" + result + ")");
        return result == 1;
    }

    @Override
    public String terminalStatus(Long turnId) {
        Object value = redis.opsForHash().get(key(turnId, "meta"), "terminal");
        return value == null ? "FAILED" : value.toString();
    }

    @Override
    public void close(Long conversationId, Long turnId, List<ConversationMessagePO> messages, String status) {
        List<String> args = new ArrayList<>();
        args.add(conversationId + ":" + turnId);
        args.add(status);
        for (ConversationMessagePO message : messages) {
            args.add(message.getMessageKey());
            args.add(encode(message));
        }
        redis.execute(closeScript, Arrays.asList(key(turnId, "snapshot"), key(turnId, "meta"), PENDING), args.toArray());
    }

    @Override
    public void expire(Long turnId) {
        for (String suffix : Arrays.asList("snapshot", "meta", "events"))
            redis.expire(key(turnId, suffix), Duration.ofSeconds(retention));
    }

    @Override
    public Set<String> pending() {
        Set<String> result = redis.opsForSet().members(PENDING);
        return result == null ? Collections.emptySet() : result;
    }

    @Override
    public void removePending(String member) {
        redis.opsForSet().remove(PENDING, member);
    }

    @Override
    public List<JsonNode> replay(Long turnId, long after) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(key(turnId, "events"), Range.closed((after + 1) + "-0", "+"));
        List<JsonNode> values = new ArrayList<>();
        if (records != null) for (MapRecord<String, Object, Object> record : records)
            values.add(decode(record.getValue().get("data").toString(), JsonNode.class));
        return values;
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encode message state", exception);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid message state", exception);
        }
    }
}
