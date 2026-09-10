package com.myharness.codex.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.vo.ApiResponseVO;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bound action JSON before Jackson allocates lists/strings; also covers chunked requests. */
public final class WorkspaceFileRequestLimitFilter extends OncePerRequestFilter {
    private static final int LIMIT=512*1024;
    private final ObjectMapper json;
    public WorkspaceFileRequestLimitFilter(ObjectMapper json) {this.json=json;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !request.getServletPath().matches(
                "/api/v1/projects/[0-9]+/workspace-files/(renames|moves|delete-plans|deletions|archive-downloads|operations/[0-9]+/reconcile)");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        if(request.getContentLengthLong()>LIMIT) {reject(response);return;}
        byte[] body=request.getInputStream().readNBytes(LIMIT+1);
        if(body.length>LIMIT) {reject(response);return;}
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var bytes=new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read(){return bytes.read();}
                    @Override public int read(byte[] target,int offset,int length){return bytes.read(target,offset,length);}
                    @Override public boolean isFinished(){return bytes.available()==0;}
                    @Override public boolean isReady(){return true;}
                    @Override public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous JSON endpoint");}
                };
            }
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        },response);
    }
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(),ApiResponseVO.error(413,"文件操作请求超过 512 KiB 限制"));
    }
}
