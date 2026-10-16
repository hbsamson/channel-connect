package com.svi.channelconnect.telegram.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.svi.channelconnect.telegram.config.TelegramCampaignProperties;

/** Authenticates before reading or deserializing a bounded outbound request. */
public class TelegramCampaignFilter extends OncePerRequestFilter {
	private static final int MAX_BODY_BYTES = 16384;
	private final byte[] expected;

	public TelegramCampaignFilter(TelegramCampaignProperties properties) {
		this.expected = ("Bearer " + properties.apiToken()).getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
		String authorization = headers.hasMoreElements() ? headers.nextElement() : null;
		if (headers.hasMoreElements() || authorization == null || authorization.length() != expected.length
				|| !MessageDigest.isEqual(expected, authorization.getBytes(StandardCharsets.UTF_8))) {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
			error(response, HttpServletResponse.SC_UNAUTHORIZED, "Outbound authentication failed");
			return;
		}
		if (request.getContentLengthLong() > MAX_BODY_BYTES) {
			error(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Outbound request is too large");
			return;
		}
		byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) {
			error(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Outbound request is too large");
			return;
		}
		chain.doFilter(new TelegramWebhookFilter.CachedBodyRequest(request, body), response);
	}

	private void error(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"status\":" + status + ",\"error\":\"" + message + "\"}");
	}
}
