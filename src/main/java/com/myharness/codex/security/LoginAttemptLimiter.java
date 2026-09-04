package com.myharness.codex.security;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;
/** Bounded limiter for the single-server deployment. Failed attempts expire after 15 minutes. */
@Component
public class LoginAttemptLimiter {
    private record Window(long expires,int attempts) {}
    private final Map<String,Window> windows=new HashMap<>();
    public synchronized void attempt(String username) {
        long now=System.currentTimeMillis();
        windows.entrySet().removeIf(e->e.getValue().expires()<now);
        Window previous=windows.get(username);
        if(previous!=null && previous.attempts()>=10 || previous==null && windows.size()>=4096)
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        windows.put(username,new Window(previous==null ? now+900_000:previous.expires(),previous==null?1:previous.attempts()+1));
    }
    public synchronized void success(String username) { windows.remove(username); }
}

