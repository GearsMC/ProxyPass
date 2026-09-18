package org.cloudburstmc.proxypass.dump;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.NoopCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer;
import org.cloudburstmc.proxypass.ProxyPass;
import org.cloudburstmc.proxypass.network.bedrock.session.ProxyClientSession;

import java.util.List;
import java.util.function.Consumer;

/**
 * GearsMC: Bedrock oturumunu NetherNet kanalına bağlar.
 *
 * <p>Varsayılan kurulum RakNet'e göre yazılmış: çerçeve çözücüsü {@code RakMessage} bekliyor ve sıkıştırma seçimi
 * {@code RAK_PROTOCOL_VERSION} kanal seçeneğini okuyor. NetherNet kanalında ikisi de yok, bu yüzden ön kurulum burada
 * değiştirilir: mesajlar düz {@link ByteBuf}, sıkıştırma başta kapalı (sunucu {@code NetworkSettings} ile seçer).</p>
 */
@Log4j2
public class NetherNetBedrockInitializer extends BedrockChannelInitializer<ProxyClientSession> {

    /** RakNet'te olduğu gibi oyun paketi kimliği; NetherNet mesajlarında da başta duruyorsa ayıklanır. */
    private static final int MINECRAFT_FRAME_ID = 0xFE;
    /**
     * NetherNet mesajında RakNet'teki çerçeve kimliği (0xFE) yoktur: mesajın kendisi yığındır. Ölçüldü (2026-09-18,
     * BDS 1.26.51): kimlikle gönderilen giriş isteği yanıtsız kalıyor, kimliksiz gönderilince sunucu
     * {@code NetworkSettings} ile cevap veriyor. {@code -Dgears.nethernet.frameId=true} ile eski davranış denenebilir.
     */
    private static final boolean PREFIX_FRAME_ID = "true".equals(System.getProperty("gears.nethernet.frameId"));

    private final ProxyPass proxy;
    private final Consumer<ProxyClientSession> sessionConsumer;

    public NetherNetBedrockInitializer(ProxyPass proxy, Consumer<ProxyClientSession> sessionConsumer) {
        this.proxy = proxy;
        this.sessionConsumer = sessionConsumer;
    }

    @Override
    protected void preInitChannel(Channel channel) {
        // İstemci tarafıyız: gönderdiğimiz paketler sunucuya gider.
        channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.SERVER_BOUND);
        channel.pipeline().addLast(NetherNetFrameCodec.NAME, new NetherNetFrameCodec());
        channel.pipeline().addLast(CompressionCodec.NAME,
                new CompressionCodec(new SimpleCompressionStrategy(new NoopCompression()), false));
    }

    @Override
    protected void initPacketCodec(Channel channel) {
        // RakNet sürümüne göre seçim yapılamıyor; 1.20.60+ istemcileri v3 paket kodeğini kullanır.
        channel.pipeline().addLast(BedrockPacketCodec.NAME, new BedrockPacketCodec_v3());
    }

    @Override
    protected ProxyClientSession createSession0(BedrockPeer peer, int subClientId) {
        return new ProxyClientSession(peer, subClientId, this.proxy);
    }

    @Override
    protected void initSession(ProxyClientSession session) {
        this.sessionConsumer.accept(session);
    }

    /** RakNet'in {@code FrameIdCodec}'inin NetherNet karşılığı: taşıma katmanı düz bayt tamponu veriyor. */
    @Sharable
    static class NetherNetFrameCodec extends MessageToMessageCodec<ByteBuf, BedrockBatchWrapper> {

        static final String NAME = "nethernet-frame-codec";

        private boolean loggedFirstFrame;

        @Override
        protected void encode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) {
            if (msg.getCompressed() == null) {
                throw new IllegalStateException("Bedrock batch was not compressed");
            }
            if (PREFIX_FRAME_ID) {
                CompositeByteBuf buf = ctx.alloc().compositeDirectBuffer(2);
                try {
                    buf.addComponent(true, ctx.alloc().ioBuffer(1).writeByte(MINECRAFT_FRAME_ID));
                    buf.addComponent(true, msg.getCompressed().retainedSlice());
                    out.add(buf.retain());
                } finally {
                    buf.release();
                }
            } else {
                out.add(msg.getCompressed().retainedSlice());
            }
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (!in.isReadable()) {
                return;
            }
            if (!this.loggedFirstFrame) {
                this.loggedFirstFrame = true;
                log.info("İlk NetherNet çerçevesi: {} bayt, ilk bayt 0x{}", in.readableBytes(),
                        Integer.toHexString(in.getUnsignedByte(in.readerIndex())));
            }
            // Çerçeve kimliği varsa ayıklanır; yoksa mesajın tamamı yığının kendisidir.
            if (in.getUnsignedByte(in.readerIndex()) == MINECRAFT_FRAME_ID) {
                in.readUnsignedByte();
            }
            out.add(BedrockBatchWrapper.newInstance(in.readRetainedSlice(in.readableBytes()), null));
        }
    }
}
