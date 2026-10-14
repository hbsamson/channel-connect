package com.svi.channelconnect.sms.goip;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class GoipPacketCodecTests {
	private final GoipPacketCodec codec = new GoipPacketCodec();
	private GoipPacket decode(String wire) { return codec.decode(wire.getBytes(StandardCharsets.UTF_8)); }
	@Test void serializesThePdfLayoutsAndUtf8ByteLength() {
		assertThat(new String(codec.message(11, "hello"), StandardCharsets.UTF_8)).isEqualTo("MSG 11 5 hello\n");
		assertThat(new String(codec.message(12, "é漢\n:x;"), StandardCharsets.UTF_8)).isEqualTo("MSG 12 9 é漢\n:x;\n");
		assertThat(codec.password(11, "test-password")).isEqualTo("PASSWORD 11 test-password\n".getBytes(StandardCharsets.UTF_8));
		assertThat(codec.send(11, 2, "+639000000001")).isEqualTo("SEND 11 2 +639000000001\n".getBytes(StandardCharsets.UTF_8));
		assertThat(codec.done(11)).isEqualTo("DONE 11\n".getBytes(StandardCharsets.UTF_8));
		assertThat(codec.registrationAck(10)).isEqualTo("reg:10;status:0;".getBytes(StandardCharsets.UTF_8));
		assertThat(codec.receiveAck(1270197307)).isEqualTo("RECEIVE 1270197307 OK\n".getBytes(StandardCharsets.UTF_8));
	}
	@Test void preservesBodyDelimitersAndTrailingNewlinesForBothDocumentedPasswordSpellings() {
		for (String key : new String[] {"pass", "password"}) {
			var packet = decode("RECEIVE:1270197307;id:test-id;" + key + ":test-password;srcnum:+639000000001;msg: first:second;msg:body\nlast\n");
			assertThat(packet.body()).isEqualTo(" first:second;msg:body\nlast\n");
			assertThat(packet.id()).isEqualTo(1270197307);
			assertThat(packet.toString()).doesNotContain("test-password", "first:second");
		}
	}
	@Test void correlatesAllGatewayResponsesIncludingRecipientErrors() {
		assertThat(decode("PASSWORD 11\n").type()).isEqualTo(GoipPacket.Type.PASSWORD);
		assertThat(decode("SEND 11\n").recipientId()).isNull();
		assertThat(decode("OK 11 2\n").recipientId()).isEqualTo(2L);
		assertThat(decode("WAIT 11 2\n").type()).isEqualTo(GoipPacket.Type.WAIT);
		assertThat(decode("ERROR 11 2 errorstatus:1\n").body()).isEqualTo("1");
		assertThat(decode("ERROR 11 2 errorstatus: 1\n").recipientId()).isEqualTo(2L);
		assertThat(decode("ERROR 11 PASSWORD\n").recipientId()).isNull();
	}
	@Test void rejectsMalformedAmbiguousOversizedAndInvalidUtf8Packets() {
		for (String wire : new String[] {"MSG 1 2 éé\n", "OK 1 2 3\n", "DONE 1 injected\n", "PASSWORD 0\n",
				"RECEIVE:1;id:x;pass:a;password:a;srcnum:+639000000001;msg:hi",
				"RECEIVE:1;id:x;id:y;pass:a;srcnum:+639000000001;msg:hi", "req:1;id:x;pass:a"}) {
			assertThatThrownBy(() -> decode(wire)).isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> codec.decode(new byte[] {(byte) 0xc3, 0x28})).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codec.message(1, "漢".repeat(1001))).isInstanceOf(IllegalArgumentException.class);
		assertThat(codec.decode(codec.message(1, "漢".repeat(1000))).body()).hasSize(1000);
		assertThatThrownBy(() -> codec.decode(new byte[8193])).isInstanceOf(IllegalArgumentException.class);
	}
}
