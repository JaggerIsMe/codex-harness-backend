package com.myharness.codex.security;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
@Service
public class ClientSocketTickets {
    private record Ticket(String token,long expires) {}
    private final Map<String,Ticket> tickets=new HashMap<>();
    public synchronized String issue(String token) {
        long now=System.currentTimeMillis();
        tickets.entrySet().removeIf(e->e.getValue().expires()<now);
        if(tickets.size()>=4096) throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        String id=UUID.randomUUID().toString()+UUID.randomUUID();
        tickets.put(id,new Ticket(token,now+30_000)); return id;
    }
    public synchronized String consume(String id) {
        Ticket ticket=tickets.remove(id);
        if(ticket==null || ticket.expires()<System.currentTimeMillis()) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return ticket.token();
    }
}

