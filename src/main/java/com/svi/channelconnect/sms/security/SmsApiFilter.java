package com.svi.channelconnect.sms.security;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.svi.channelconnect.sms.config.SmsProperties;

@Component
@Profile({"local", "test"})
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
@Order(-90)
public class SmsApiFilter extends OncePerRequestFilter {
	private static final int MAX_BODY_BYTES = 16384;
	private final byte[] expected;
	public SmsApiFilter(SmsProperties properties) {
		if (properties.apiToken() == null || properties.apiToken().length() < 16) throw new IllegalArgumentException("SMS API requires a token of at least 16 characters");
		expected = ("Bearer " + properties.apiToken()).getBytes(StandardCharsets.UTF_8);
	}
	@Override protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = path(request);
		return !path.startsWith("/api/v1/sms/") && !path.startsWith("/api/admin/sms/");
	}
	@Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
		String path = path(request);
		if (path.startsWith("/api/v1/sms/")) {
			String supplied = request.getHeader("Authorization");
			if (supplied == null || !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) { error(response, 401); return; }
		}
		if (!request.getMethod().equals("POST")) { chain.doFilter(request, response); return; }
		if (request.getContentLengthLong() > MAX_BODY_BYTES) { error(response, 413); return; }
		byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) { error(response, 413); return; }
		chain.doFilter(new CachedRequest(request, body), response);
	}
	private String path(HttpServletRequest request) {
		String servletPath = request.getServletPath();
		if (servletPath != null && !servletPath.isEmpty()) return servletPath;
		return org.springframework.web.util.UriUtils.decode(request.getRequestURI().substring(request.getContextPath().length()), StandardCharsets.UTF_8);
	}
	private void error(HttpServletResponse response, int status) throws IOException {
		response.setStatus(status); response.setContentType("application/json");
		response.getWriter().write("{\"status\":" + status + ",\"message\":\"SMS request rejected\"}");
	}
	private static final class CachedRequest extends HttpServletRequestWrapper {
		private final byte[] bytes;
		private CachedRequest(HttpServletRequest request, byte[] bytes) { super(request); this.bytes = bytes; }
		@Override public ServletInputStream getInputStream() {
			ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
			return new ServletInputStream() {
				@Override public int read() { return stream.read(); }
				@Override public boolean isFinished() { return stream.available() == 0; }
				@Override public boolean isReady() { return true; }
				@Override public void setReadListener(ReadListener listener) { /* Synchronous Spring MVC input. */ }
			};
		}
		@Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
		@Override public int getContentLength() { return bytes.length; }
		@Override public long getContentLengthLong() { return bytes.length; }
	}
}
