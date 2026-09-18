package org.cloudburstmc.proxypass.dump;

import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.proxypass.network.bedrock.util.ItemDefinitionRegistries;
import org.cloudburstmc.proxypass.ProxyPass;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * GearsMC: istemcisiz döküm kipi.
 *
 * <p>ProxyPass normalde gerçek bir Minecraft istemcisiyle sunucu arasına girer; veri almak için elle oyuna girmek
 * gerekir. Bu kip sunucuya kendisi bağlanır (giriş zincirini {@code ForgeryUtils} ile kendisi üretir), kayıt
 * paketlerini bekler, {@code data/} altına yazar ve çıkar. Böylece yeni Bedrock sürümünde veri üretmek tek komut olur
 * ve kimsenin dosya yayınlamasını beklemeyiz.</p>
 *
 * <p>Sunucu tarafında {@code online-mode=false} ve {@code allow-list=false} gerekir (BDS 1.26.51'de doğrulandı).</p>
 *
 * <p>Kullanım: {@code java -jar ProxyPass.jar dump <host> <port> [saniye]}</p>
 */
@Log4j2
public final class HeadlessDump {

    /** Kayıt paketleri geldikten sonra beklenen sessizlik; bu süre boyunca yeni paket gelmezse döküm biter. */
    private static final long IDLE_MILLIS = 3000;

    private HeadlessDump() {
    }

    public static void run(ProxyPass proxy, InetSocketAddress target, long timeoutSeconds) throws InterruptedException {
        var finished = new CountDownLatch(1);
        var handler = new DumpPacketHandler(proxy, finished, IDLE_MILLIS);

        log.info("Döküm için bağlanılıyor: {} (protokol {})", target, ProxyPass.PROTOCOL_VERSION);
        proxy.newClient(target, session -> {
            // İstemci tarafı oturum kurulumu: kodek, kodlama ayarları ve tanım kayıt defterleri (proxy kipindekiyle aynı).
            session.setCodec(ProxyPass.CODEC);
            session.getPeer().getCodecHelper().setEncodingSettings(EncodingSettings.CLIENT);
            session.getPeer().getCodecHelper().setBlockDefinitions(proxy.getBlockDefinitions());
            session.getPeer().getCodecHelper().setItemDefinitions(ItemDefinitionRegistries.empty());
            handler.attach(session);
            session.setPacketHandler(handler);
            var request = new RequestNetworkSettingsPacket();
            request.setProtocolVersion(ProxyPass.PROTOCOL_VERSION);
            session.sendPacketImmediately(request);
        });

        if (!finished.await(timeoutSeconds, TimeUnit.SECONDS)) {
            log.error("Döküm {} saniyede tamamlanmadı. Alınanlar: {}", timeoutSeconds, handler.received());
            proxy.shutdown();
            System.exit(1);
        }
        log.info("Döküm tamam: {}", handler.received());
        proxy.shutdown();
        System.exit(0);
    }
}
