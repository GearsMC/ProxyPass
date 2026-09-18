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
 * <p>Kullanım: {@code java -jar ProxyPass.jar dump <host> <port> [saniye]} (RakNet) ya da
 * {@code dump-nethernet <host> <keşif portu, BDS'te 7551> [saniye]}.</p>
 */
@Log4j2
public final class HeadlessDump {

    /** Kayıt paketleri geldikten sonra beklenen sessizlik; bu süre boyunca yeni paket gelmezse döküm biter. */
    private static final long IDLE_MILLIS = 3000;
    /** NetherNet el sıkışmasından sonra oturumun kurulması için beklenen süre. */
    private static final long SESSION_WAIT_MILLIS = 15000;

    private HeadlessDump() {
    }

    private static RequestNetworkSettingsPacket networkSettingsRequest() {
        var request = new RequestNetworkSettingsPacket();
        request.setProtocolVersion(ProxyPass.PROTOCOL_VERSION);
        return request;
    }

    public static void run(ProxyPass proxy, InetSocketAddress target, long timeoutSeconds, boolean netherNet) throws InterruptedException {
        var finished = new CountDownLatch(1);
        var handler = new DumpPacketHandler(proxy, finished, IDLE_MILLIS);

        log.info("Döküm için bağlanılıyor: {} (protokol {}, taşıma {})", target, ProxyPass.PROTOCOL_VERSION,
                netherNet ? "NetherNet" : "RakNet");
        var sessionRef = new java.util.concurrent.atomic.AtomicReference<org.cloudburstmc.proxypass.network.bedrock.session.ProxyClientSession>();
        java.util.function.Consumer<org.cloudburstmc.proxypass.network.bedrock.session.ProxyClientSession> setup = session -> {
            sessionRef.set(session);
            // İstemci tarafı oturum kurulumu: kodek, kodlama ayarları ve tanım kayıt defterleri (proxy kipindekiyle aynı).
            session.setCodec(ProxyPass.CODEC);
            session.getPeer().getCodecHelper().setEncodingSettings(EncodingSettings.CLIENT);
            session.getPeer().getCodecHelper().setBlockDefinitions(proxy.getBlockDefinitions());
            session.getPeer().getCodecHelper().setItemDefinitions(ItemDefinitionRegistries.empty());
            handler.attach(session);
            session.setPacketHandler(handler);
            if (!netherNet) {
                // RakNet'te kanal bağlantı kurulduktan sonra hazırlanır; ilk paket burada gider.
                session.sendPacketImmediately(networkSettingsRequest());
            }
        };
        if (netherNet) {
            // NetherNet'te oturum, veri kanalı açılmadan önce hazırlanıyor; istek bağlantı kurulunca gönderilir.
            proxy.newNetherNetClient(target, setup);
            // Oturum kanal etkin olunca kuruluyor; el sıkışma bittikten sonra kısa bir süre beklenir.
            var deadline = System.currentTimeMillis() + SESSION_WAIT_MILLIS;
            while (sessionRef.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            var session = sessionRef.get();
            if (session == null) {
                log.error("NetherNet oturumu kurulamadı.");
                proxy.shutdown();
                System.exit(1);
            }
            session.sendPacketImmediately(networkSettingsRequest());
        } else {
            proxy.newClient(target, setup);
        }

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
