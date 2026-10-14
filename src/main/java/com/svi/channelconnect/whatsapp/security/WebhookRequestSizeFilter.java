package com.svi.channelconnect.whatsapp.security;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.svi.channelconnect.whatsapp.config.WhatsAppProperties;

@Component
public class WebhookRequestSizeFilter extends OncePerRequestFilter {
	private static final String WEBHOOK_PATH = "/webhooks/whatsapp";

	private final int maximumBytes;

	public WebhookRequestSizeFilter(WhatsAppProperties properties) {
		this.maximumBytes = properties.webhookMaxRequestBytes();
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return !"POST".equalsIgnoreCase(request.getMethod())
				|| !WEBHOOK_PATH.equals(path);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		if (request.getContentLengthLong() > maximumBytes) {
			writePayloadTooLarge(response);
			return;
		}

		byte[] body = request.getInputStream().readNBytes(maximumBytes + 1);
		if (body.length > maximumBytes) {
			writePayloadTooLarge(response);
			return;
		}

		filterChain.doFilter(new CachedBodyRequest(request, body), response);
	}

	private void writePayloadTooLarge(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"status\":413,\"error\":\"Webhook payload too large\"}");
	}

	private static final class CachedBodyRequest extends HttpServletRequestWrapper {
		private final byte[] body;

		private CachedBodyRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public ServletInputStream getInputStream() {
			return new ByteArrayServletInputStream(body);
		}

		@Override
		public BufferedReader getReader() {
			String encoding = getCharacterEncoding();
			Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
			return new BufferedReader(new InputStreamReader(getInputStream(), charset));
		}

		@Override
		public int getContentLength() {
			return body.length;
		}

		@Override
		public long getContentLengthLong() {
			return body.length;
		}
	}

	private static final class ByteArrayServletInputStream extends ServletInputStream {
		private final ByteArrayInputStream delegate;

		private ByteArrayServletInputStream(byte[] body) {
			this.delegate = new ByteArrayInputStream(body);
		}

		@Override
		public int read() {
			return delegate.read();
		}

		@Override
		public boolean isFinished() {
			return delegate.available() == 0;
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setReadListener(ReadListener readListener) {
			// Spring MVC consumes this stream synchronously.
		}
	}
}
