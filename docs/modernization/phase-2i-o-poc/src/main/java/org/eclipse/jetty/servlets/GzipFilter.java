package org.eclipse.jetty.servlets;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/** Test-only pass-through compatibility filter; SockJS responses are not application/json. */
public class GzipFilter implements Filter {
    @Override public void init(FilterConfig config) { }
    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException { chain.doFilter(request, response); }
    @Override public void destroy() { }
}
