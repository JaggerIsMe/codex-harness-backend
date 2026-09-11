package com.myharness.codex.security;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;

/** One active Login Session per User; only explicit activity advances its idle deadline. */
@Service
public class RedisLoginSessionStore {
    private static final String PREFIX="harness:auth:session:";
    private static final String INDEX="harness:auth:sid:";
    private static final String HELPERS="""
            local function parts(value)
              if not value then return nil end
              local p = {}
              for item in string.gmatch(value .. ':', '(.-):') do p[#p+1] = item end
              if #p == 3 then p[4]=p[3]; p[5]='0'; p[6]=''; p[7]=''; p[8]='0' end
              if #p ~= 8 or not tonumber(p[1]) or not tonumber(p[3]) or not tonumber(p[4])
                or not tonumber(p[5]) or not tonumber(p[8]) or p[2]=='' then error('Invalid session state') end
              return p
            end
            local function matches(a,b)
              return a and b and a[1]==b[1] and a[2]==b[2]
            end
            local function active(p,now)
              return p and tonumber(p[3])>now and tonumber(p[4])>now
            end
            """;
    private static final DefaultRedisScript<Long> REPLACE=new DefaultRedisScript<>(HELPERS+"""
            local current=parts(redis.call('GET',KEYS[1]))
            local expected=ARGV[1]~='' and parts(ARGV[1]) or nil
            if expected then
              if not matches(current,expected) then return 0 end
            elseif current then return 0 end
            local next=parts(ARGV[2])
            local now=tonumber(redis.call('TIME')[1])
            if not active(next,now) then return 0 end
            redis.call('SET',KEYS[1],ARGV[2],'EXAT',next[4])
            redis.call('SET',KEYS[2],ARGV[3],'EXAT',next[4])
            if KEYS[3]~=KEYS[2] and redis.call('GET',KEYS[3])==ARGV[3] then redis.call('DEL',KEYS[3]) end
            return 1
            """,Long.class);
    private static final DefaultRedisScript<Long> REVOKE=new DefaultRedisScript<>(HELPERS+"""
            local current=parts(redis.call('GET',KEYS[1]))
            if not matches(current,parts(ARGV[1])) then return 0 end
            redis.call('DEL',KEYS[1])
            if redis.call('GET',KEYS[2])==ARGV[2] then redis.call('DEL',KEYS[2]) end
            return 1
            """,Long.class);
    private static final DefaultRedisScript<String> TOUCH=new DefaultRedisScript<>(HELPERS+"""
            local current=parts(redis.call('GET',KEYS[1]))
            local now=tonumber(redis.call('TIME')[1])
            if not matches(current,parts(ARGV[1])) or not active(current,now) then return nil end
            current[4]=tostring(math.min(tonumber(current[3]),math.max(tonumber(current[4]),now+tonumber(ARGV[2]))))
            local value=table.concat(current,':')
            redis.call('SET',KEYS[1],value,'EXAT',current[4])
            redis.call('SET',KEYS[2],ARGV[3],'EXAT',current[4])
            return value
            """,String.class);
    private static final DefaultRedisScript<String> ROTATE=new DefaultRedisScript<>(HELPERS+"""
            local current=parts(redis.call('GET',KEYS[1]))
            local now=tonumber(redis.call('TIME')[1])
            if not matches(current,parts(ARGV[1])) or not active(current,now) then return 'INVALID' end
            if current[6]~='' and current[6]==ARGV[2] then
              current[7]=current[6]; current[8]=tostring(now+tonumber(ARGV[4]))
              current[6]=ARGV[3]; current[5]=tostring(tonumber(current[5])+1)
              local value=table.concat(current,':')
              redis.call('SET',KEYS[1],value,'EXAT',current[4])
              return value
            end
            if current[7]~='' and current[7]==ARGV[2] then
              if now<=tonumber(current[8]) then return 'CONFLICT' end
              redis.call('DEL',KEYS[1])
              if redis.call('GET',KEYS[2])==ARGV[5] then redis.call('DEL',KEYS[2]) end
              return 'REPLAYED'
            end
            return 'INVALID'
            """,String.class);

    private final StringRedisTemplate redis;
    public RedisLoginSessionStore(StringRedisTemplate redis) { this.redis=redis; }

    public record Session(long version,String sid,long expiresAtEpochSeconds,long idleExpiresAtEpochSeconds,
                          long credentialGeneration,String refreshDigest,String previousRefreshDigest,long previousRefreshValidUntilEpochSeconds) {
        public Session(long version,String sid,long expiresAtEpochSeconds) {
            this(version,sid,expiresAtEpochSeconds,expiresAtEpochSeconds,0,"","",0);
        }
        public Session {
            if (version<0 || sid==null || !sid.matches("[A-Za-z0-9_-]{1,128}") || expiresAtEpochSeconds<=0
                    || idleExpiresAtEpochSeconds<=0 || idleExpiresAtEpochSeconds>expiresAtEpochSeconds || credentialGeneration<0
                    || refreshDigest==null || !refreshDigest.matches("(?:[a-f0-9]{64})?")
                    || previousRefreshDigest==null || !previousRefreshDigest.matches("(?:[a-f0-9]{64})?") || previousRefreshValidUntilEpochSeconds<0)
                throw new IllegalArgumentException("Invalid login session");
        }
        public boolean sameLogin(Session other) { return other!=null && version==other.version && sid.equals(other.sid); }
        public boolean active(long now) { return idleExpiresAtEpochSeconds>now && expiresAtEpochSeconds>now; }
        @Override public String toString() { return "Session[version="+version+", credentials=REDACTED]"; }
    }
    public enum RotationStatus { ROTATED, CONFLICT, REPLAYED, INVALID }
    public record Rotation(RotationStatus status,Session session) {}

    public Session read(Long userId) {
        try { return decode(redis.opsForValue().get(key(userId))); }
        catch (RuntimeException exception) { throw unavailable(); }
    }
    public Long userIdForSid(String sid) {
        try {
            String value=redis.opsForValue().get(index(sid));
            if (value==null) return null;
            long id=Long.parseLong(value);
            if (id<=0 || !Long.toString(id).equals(value)) throw unavailable();
            return id;
        } catch (RuntimeException exception) { throw unavailable(); }
    }
    /** Activity/refresh can advance metadata while login waits to replace this stable sid. */
    public boolean replace(Long userId,Session expected,Session next) {
        Objects.requireNonNull(next,"next");
        return booleanResult(execute(REPLACE,List.of(key(userId),index(next.sid()),index(expected==null?next.sid():expected.sid())),
                expected==null?"":encode(expected),encode(next),userId.toString()));
    }
    public boolean revoke(Long userId,Session expected) {
        Objects.requireNonNull(expected,"expected");
        return booleanResult(execute(REVOKE,List.of(key(userId),index(expected.sid())),encode(expected),userId.toString()));
    }
    public Session touch(Long userId,Session expected,long idleSeconds) {
        if (idleSeconds<=0) throw new IllegalArgumentException("Invalid idle interval");
        return decode(execute(TOUCH,List.of(key(userId),index(expected.sid())),encode(expected),Long.toString(idleSeconds),userId.toString()));
    }
    public Rotation rotate(Long userId,Session expected,String digest,String nextDigest,long graceSeconds) {
        if (digest==null || !digest.matches("[a-f0-9]{64}") || nextDigest==null || !nextDigest.matches("[a-f0-9]{64}") || graceSeconds<0)
            throw new IllegalArgumentException("Invalid refresh credential");
        String value=execute(ROTATE,List.of(key(userId),index(expected.sid())),encode(expected),digest,nextDigest,Long.toString(graceSeconds),userId.toString());
        if (value==null) throw unavailable();
        return switch(value) {
            case "CONFLICT" -> new Rotation(RotationStatus.CONFLICT,null);
            case "REPLAYED" -> new Rotation(RotationStatus.REPLAYED,null);
            case "INVALID" -> new Rotation(RotationStatus.INVALID,null);
            default -> new Rotation(RotationStatus.ROTATED,decode(value));
        };
    }
    private <T> T execute(DefaultRedisScript<T> script,List<String> keys,String... arguments) {
        try { return redis.execute(script,keys,(Object[])arguments); }
        catch (RuntimeException exception) { throw unavailable(); }
    }
    private boolean booleanResult(Long result) {
        if (result==null || result!=0 && result!=1) throw unavailable();
        return result==1;
    }
    private String key(Long userId) {
        if (userId==null || userId<=0) throw new IllegalArgumentException("Invalid User identity");
        return PREFIX+userId;
    }
    private String index(String sid) { return INDEX+sid; }
    private String encode(Session s) {
        return s.version()+":"+s.sid()+":"+s.expiresAtEpochSeconds()+":"+s.idleExpiresAtEpochSeconds()+":"+s.credentialGeneration()
                +":"+s.refreshDigest()+":"+s.previousRefreshDigest()+":"+s.previousRefreshValidUntilEpochSeconds();
    }
    private Session decode(String value) {
        if (value==null) return null;
        try {
            String[] p=value.split(":",-1);
            if (p.length==3) {
                var legacy=new Session(Long.parseLong(p[0]),p[1],Long.parseLong(p[2]));
                if (!(legacy.version()+":"+legacy.sid()+":"+legacy.expiresAtEpochSeconds()).equals(value)) throw unavailable();
                return legacy;
            }
            if (p.length!=8) throw unavailable();
            Session session=new Session(Long.parseLong(p[0]),p[1],Long.parseLong(p[2]),Long.parseLong(p[3]),Long.parseLong(p[4]),p[5],p[6],Long.parseLong(p[7]));
            if (!encode(session).equals(value)) throw unavailable();
            return session;
        } catch (RuntimeException exception) { throw unavailable(); }
    }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.SESSION_UNAVAILABLE); }
}
