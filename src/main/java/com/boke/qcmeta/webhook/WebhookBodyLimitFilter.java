package com.boke.qcmeta.webhook;

import com.boke.qcmeta.config.WebhookProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class WebhookBodyLimitFilter extends OncePerRequestFilter {
    private final WebhookProperties config;
    public WebhookBodyLimitFilter(WebhookProperties config) {this.config=config;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getServletPath();
        if(path==null || path.isBlank()) path=request.getRequestURI().substring(request.getContextPath().length());
        return !"/webhooks/ycloud".equals(path.replaceAll(";[^/]*",""));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        int limit=config.maxBodyBytes();
        if(limit<=0 || request.getContentLengthLong()>limit) {reject(response);return;}
        byte[] body=request.getInputStream().readNBytes((int)Math.min((long)limit+1,Integer.MAX_VALUE));
        if(body.length>limit) {reject(response);return;}
        // 保留原始字节和签名头，不能先转字符串、解析 JSON 或规范化换行。
        chain.doFilter(new RawBodyRequest(request,body),response);
    }
    private static void reject(HttpServletResponse response)throws IOException {
        response.setStatus(413);response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"请求内容过大\"}");
    }
    private static final class RawBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        RawBodyRequest(HttpServletRequest request,byte[] body) {super(request);this.body=body;}
        @Override public int getContentLength() {return body.length;}
        @Override public long getContentLengthLong() {return body.length;}
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input=new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() {return input.read();}
                @Override public int read(byte[] bytes,int offset,int length) {return input.read(bytes,offset,length);}
                @Override public boolean isFinished() {return input.available()==0;}
                @Override public boolean isReady() {return true;}
                @Override public void setReadListener(ReadListener listener) {
                    if(listener==null) throw new IllegalArgumentException("listener 不能为空");
                    try {if(!isFinished()) listener.onDataAvailable();if(isFinished()) listener.onAllDataRead();}
                    catch(IOException exception) {listener.onError(exception);}
                }
            };
        }
        @Override public BufferedReader getReader() {
            Charset charset=getCharacterEncoding()==null?StandardCharsets.UTF_8:Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(),charset));
        }
    }
}
