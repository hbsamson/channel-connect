package com.svi.channelconnect.common.text;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.Objects;

/** Shortens presentation text without changing structured responses or transport payloads. */
public final class MessageTextLimiter {

	private static final String ELLIPSIS = "...";

	private MessageTextLimiter() { }

	public static String shorten(String text, int maximumCharacters) {
		Objects.requireNonNull(text, "Message text is required");
		if (maximumCharacters < 1) throw new IllegalArgumentException("Message length must be positive");
		if (text.length() <= maximumCharacters) return text;

		BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
		sentences.setText(text);
		int sentenceEnd = 0;
		for (int boundary = sentences.next(); boundary != BreakIterator.DONE; boundary = sentences.next()) {
			int end = boundary;
			while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
			if (end > maximumCharacters) break;
			sentenceEnd = end;
		}
		if (sentenceEnd > 0) {
			String complete = text.substring(0, sentenceEnd).stripTrailing();
			if (!complete.isBlank()) return complete;
		}

		if (maximumCharacters <= ELLIPSIS.length()) return ELLIPSIS.substring(0, maximumCharacters);
		int contentLimit = maximumCharacters - ELLIPSIS.length();
		for (int index = contentLimit; index > 0; index--) {
			if (Character.isWhitespace(text.charAt(index))) {
				String words = text.substring(0, index).stripTrailing();
				if (!words.isBlank()) return words + ELLIPSIS;
			}
		}

		// A single token may exceed the entire budget. Keep complete Unicode characters,
		// including combining marks and emoji sequences, when a word boundary cannot fit.
		BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT);
		characters.setText(text);
		int characterEnd = characters.preceding(contentLimit + 1);
		return text.substring(0, Math.max(0, characterEnd)).stripTrailing() + ELLIPSIS;
	}
}
