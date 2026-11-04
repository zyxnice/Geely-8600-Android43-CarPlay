package com.shilapi.xcertplay.transport;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLSession;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises recv ownership and underflow/overflow with a deterministic engine, not real TLS crypto. */
public class TlsReadScratchTest {
    @Test public void repeatedIdleReadsReuseWorkingBuffer() throws Exception {
        Engine engine = new Engine();
        Stream stream = new Stream();
        BlockingDuplexByteStream channel = channel(stream, engine);
        assertNull(channel.recv(100, 1000));
        ByteBuffer first = engine.lastOutput;
        assertNull(channel.recv(100, 1000));
        assertSame(first, engine.lastOutput);
    }

    @Test public void laterRecordCannotChangePreviouslyReturnedBytes() throws Exception {
        Engine engine = new Engine();
        Stream stream = new Stream();
        BlockingDuplexByteStream channel = channel(stream, engine);
        stream.incoming.add(new byte[] {1});
        byte[] first = channel.recv(100, 1000);
        ByteBuffer buffer = engine.lastOutput;
        stream.incoming.add(new byte[] {2});
        byte[] second = channel.recv(100, 1000);
        assertArrayEquals(new byte[] {1, 1, 1, 1}, first);
        assertArrayEquals(new byte[] {2}, second);
        assertSame(buffer, engine.lastOutput);
    }

    @Test public void overflowGrowsThenShortRecordUsesOnlyItsOwnBytes() throws Exception {
        Engine engine = new Engine();
        Stream stream = new Stream();
        BlockingDuplexByteStream channel = channel(stream, engine);
        stream.incoming.add(new byte[] {3});
        byte[] large = channel.recv(65536, 1000);
        assertEquals(20000, large.length);
        ByteBuffer grown = engine.lastOutput;
        assertTrue(engine.overflows > 0);
        stream.incoming.add(new byte[] {2});
        assertArrayEquals(new byte[] {2}, channel.recv(100, 1000));
        assertSame(grown, engine.lastOutput);
        assertEquals(engine.getSession().getApplicationBufferSize(), engine.lastLimit);
        for (byte value : large) assertEquals(3, value);
    }

    private static BlockingDuplexByteStream channel(Stream stream, Engine engine) throws Exception {
        Constructor<TlsDuplexChannel> constructor = TlsDuplexChannel.class.getDeclaredConstructor(
            BlockingDuplexByteStream.class, SSLEngine.class);
        constructor.setAccessible(true);
        return constructor.newInstance(stream, engine);
    }

    private static final class Stream implements BlockingDuplexByteStream {
        final ArrayDeque<byte[]> incoming = new ArrayDeque<>();
        @Override public void send(byte[] data) { throw new AssertionError("unexpected outbound record"); }
        @Override public byte[] recv(int maxBytes, long timeoutMillis) { return incoming.poll(); }
        @Override public void close() { }
    }

    private static final class Engine extends SSLEngine {
        final SSLEngine delegate = SSLContext.getDefault().createSSLEngine();
        ByteBuffer lastOutput;
        int lastLimit;
        int overflows;

        Engine() throws Exception { }

        @Override public SSLEngineResult unwrap(ByteBuffer source, ByteBuffer[] targets, int offset, int length) {
            ByteBuffer target = targets[offset];
            lastOutput = target;
            lastLimit = target.limit();
            if (!source.hasRemaining()) return result(SSLEngineResult.Status.BUFFER_UNDERFLOW, 0, 0);
            byte value = source.get(source.position());
            int count = value == 3 ? 20000 : value == 1 ? 4 : 1;
            if (target.remaining() < count) {
                overflows++;
                return result(SSLEngineResult.Status.BUFFER_OVERFLOW, 0, 0);
            }
            source.get();
            for (int index = 0; index < count; index++) target.put(value);
            return result(SSLEngineResult.Status.OK, 1, count);
        }

        private SSLEngineResult result(SSLEngineResult.Status status, int consumed, int produced) {
            return new SSLEngineResult(status, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, consumed, produced);
        }
        @Override public SSLEngineResult wrap(ByteBuffer[] sources, int offset, int length, ByteBuffer target) {
            throw new AssertionError("unexpected wrap");
        }
        @Override public SSLSession getSession() { return delegate.getSession(); }
        @Override public Runnable getDelegatedTask() { return null; }
        @Override public void closeInbound() { }
        @Override public boolean isInboundDone() { return false; }
        @Override public void closeOutbound() { }
        @Override public boolean isOutboundDone() { return false; }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public String[] getEnabledCipherSuites() { return delegate.getEnabledCipherSuites(); }
        @Override public void setEnabledCipherSuites(String[] suites) { delegate.setEnabledCipherSuites(suites); }
        @Override public String[] getSupportedProtocols() { return delegate.getSupportedProtocols(); }
        @Override public String[] getEnabledProtocols() { return delegate.getEnabledProtocols(); }
        @Override public void setEnabledProtocols(String[] protocols) { delegate.setEnabledProtocols(protocols); }
        @Override public void beginHandshake() { }
        @Override public SSLEngineResult.HandshakeStatus getHandshakeStatus() {
            return SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
        }
        @Override public void setUseClientMode(boolean value) { delegate.setUseClientMode(value); }
        @Override public boolean getUseClientMode() { return delegate.getUseClientMode(); }
        @Override public void setNeedClientAuth(boolean value) { delegate.setNeedClientAuth(value); }
        @Override public boolean getNeedClientAuth() { return delegate.getNeedClientAuth(); }
        @Override public void setWantClientAuth(boolean value) { delegate.setWantClientAuth(value); }
        @Override public boolean getWantClientAuth() { return delegate.getWantClientAuth(); }
        @Override public void setEnableSessionCreation(boolean value) { delegate.setEnableSessionCreation(value); }
        @Override public boolean getEnableSessionCreation() { return delegate.getEnableSessionCreation(); }
    }
}
