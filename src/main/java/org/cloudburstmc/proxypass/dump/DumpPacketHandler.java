package org.cloudburstmc.proxypass.dump;

import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.bedrock.util.JsonUtils;
import org.cloudburstmc.proxypass.ProxyPass;
import org.cloudburstmc.proxypass.network.bedrock.session.DownstreamPacketHandler;
import org.cloudburstmc.proxypass.network.bedrock.session.ProxyClientSession;
import org.cloudburstmc.proxypass.network.bedrock.util.ForgeryUtils;
import org.jose4j.json.JsonUtil;
import org.jose4j.json.internal.json_simple.JSONObject;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwx.HeaderParameterNames;
import org.jose4j.lang.JoseException;

import java.security.KeyPair;
import java.util.Base64;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GearsMC: istemcisiz dökümün paket akışı.
 *
 * <p>Gerçek istemcinin yaptığı girişi kendimiz kurarız: ağ ayarları → sahte giriş → (şifreleme el sıkışması) →
 * kaynak paketi yanıtları → oyun başlangıcı. Kayıt paketlerinin kaydı {@link DownstreamPacketHandler}'a bırakılır;
 * o sınıf ProxyPass'in dosya yazma mantığını zaten taşıyor. Belirli bir süre yeni paket gelmezse döküm biter.</p>
 */
@Log4j2
public class DumpPacketHandler implements BedrockPacketHandler {

    private static final String DISPLAY_NAME = "GearsDumper";
    /** 1.20.60+ istemcilerinin sıkıştırma dalı; NetherNet'te RakNet sürümü okunamadığı için sabit verilir. */
    private static final int RAKNET_COMPRESSION_VERSION = 11;

    /** Döküm bu paketlerin hepsi gelince biter; hepsi kayıt verisi taşır. */
    private static final Set<String> REQUIRED = Set.of(
            "AvailableEntityIdentifiersPacket", "BiomeDefinitionListPacket", "CraftingDataPacket",
            "CreativeContentPacket", "ItemRegistryPacket", "StartGamePacket", "VoxelShapesPacket");

    private final ProxyPass proxy;
    private final CountDownLatch finished;
    private final long idleMillis;
    private final KeyPair keyPair = EncryptionUtils.createKeyPair();
    private final Set<String> received = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastPacket = new AtomicLong();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "dump-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private ProxyClientSession session;
    private DownstreamPacketHandler saver;
    private volatile boolean started;

    public DumpPacketHandler(ProxyPass proxy, CountDownLatch finished, long idleMillis) {
        this.proxy = proxy;
        this.finished = finished;
        this.idleMillis = idleMillis;
    }

    public void attach(ProxyClientSession session) {
        this.session = session;
        this.saver = new DownstreamPacketHandler(session, null, this.proxy);
    }

    public Set<String> received() {
        return Collections.unmodifiableSet(this.received);
    }

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        this.received.add(packet.getClass().getSimpleName());
        this.lastPacket.set(System.currentTimeMillis());
        var signal = BedrockPacketHandler.super.handlePacket(packet);
        // Kayıt paketlerini ProxyPass'in kendi yazıcısı işler.
        this.saver.handlePacket(packet);
        if (this.received.containsAll(REQUIRED)) {
            log.info("Bütün kayıt paketleri alındı.");
            this.finished.countDown();
        }
        return signal;
    }

    @Override
    public PacketSignal handle(NetworkSettingsPacket packet) {
        // NetherNet kanalında RAK_PROTOCOL_VERSION seçeneği yok; sıkıştırma stratejisi doğrudan verilir (v11 karşılığı).
        this.session.getPeer().setCompression(
                BedrockChannelInitializer.getCompression(packet.getCompressionAlgorithm(), RAKNET_COMPRESSION_VERSION, false));
        var login = new LoginPacket();
        login.setAuthPayload(new TokenPayload(forgeIdentityToken(), AuthType.SELF_SIGNED));
        login.setClientJwt(ForgeryUtils.forgeSkinData(this.keyPair, ClientDataForgery.build(DISPLAY_NAME)));
        login.setProtocolVersion(ProxyPass.PROTOCOL_VERSION);
        this.session.sendPacketImmediately(login);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ServerToClientHandshakePacket packet) {
        try {
            var jws = new JsonWebSignature();
            jws.setCompactSerialization(packet.getJwt());
            var saltJwt = new JSONObject(JsonUtil.parseJson(jws.getUnverifiedPayload()));
            var serverKey = EncryptionUtils.parseKey(jws.getHeader(HeaderParameterNames.X509_URL));
            var key = EncryptionUtils.getSecretKey(this.keyPair.getPrivate(), serverKey,
                    Base64.getDecoder().decode(JsonUtils.childAsType(saltJwt, "salt", String.class)));
            this.session.enableEncryption(key);
        } catch (Exception e) {
            throw new RuntimeException("Şifreleme el sıkışması başarısız", e);
        }
        this.session.sendPacketImmediately(new ClientToServerHandshakePacket());
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ResourcePacksInfoPacket packet) {
        var response = new ResourcePackClientResponsePacket();
        response.setStatus(ResourcePackClientResponsePacket.Status.COMPLETED);
        this.session.sendPacket(response);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ResourcePackStackPacket packet) {
        var response = new ResourcePackClientResponsePacket();
        response.setStatus(ResourcePackClientResponsePacket.Status.COMPLETED);
        this.session.sendPacket(response);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(StartGamePacket packet) {
        this.saver.handle(packet);
        // Sunucunun tarif ve yaratıcı menü paketlerini göndermesi için istemcinin hazır olduğunu bildiririz.
        var radius = new RequestChunkRadiusPacket();
        radius.setRadius(1);
        radius.setMaxRadius(1);
        this.session.sendPacket(radius);
        var initialized = new SetLocalPlayerAsInitializedPacket();
        initialized.setRuntimeEntityId(packet.getRuntimeEntityId());
        this.session.sendPacket(initialized);
        startWatchdog();
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(PacketViolationWarningPacket packet) {
        log.error("Sunucu paketi reddetti: tür={} ciddiyet={} paket={} bağlam={}",
                packet.getType(), packet.getSeverity(), packet.getPacketType(), packet.getContext());
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(DisconnectPacket packet) {
        log.warn("Sunucu bağlantıyı kesti: {}", packet.getKickMessage());
        this.finished.countDown();
        return PacketSignal.HANDLED;
    }

    @Override
    public void onDisconnect(String reason) {
        log.warn("Bağlantı kapandı: {}", reason);
        this.finished.countDown();
    }

    private void startWatchdog() {
        if (this.started) {
            return;
        }
        this.started = true;
        this.watchdog.scheduleAtFixedRate(() -> {
            if (System.currentTimeMillis() - this.lastPacket.get() >= this.idleMillis) {
                this.finished.countDown();
            }
        }, this.idleMillis, 500, TimeUnit.MILLISECONDS);
    }

    /**
     * {@code ForgeryUtils.forgeToken} ile aynı; oradaki {@code IdentityData} kurucusu dışarı kapalı olduğu için
     * kimlik alanları burada doğrudan yazılır.
     */
    private String forgeIdentityToken() {
        var publicKey = Base64.getEncoder().encodeToString(this.keyPair.getPublic().getEncoded());
        var identity = UUID.randomUUID();
        var timestamp = System.currentTimeMillis();

        var claims = new JwtClaims();
        claims.setNotBefore(NumericDate.fromMilliseconds(timestamp - TimeUnit.SECONDS.toMillis(1)));
        claims.setExpirationTime(NumericDate.fromMilliseconds(timestamp + TimeUnit.DAYS.toMillis(1)));
        claims.setIssuedAt(NumericDate.fromMilliseconds(timestamp));
        claims.setClaim("cpk", publicKey);
        claims.setClaim("xname", DISPLAY_NAME);
        claims.setClaim("xid", "2535000000000001");
        claims.setClaim("mid", identity.toString());

        var jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(this.keyPair.getPrivate());
        jws.setAlgorithmHeaderValue("ES384");
        jws.setHeader(HeaderParameterNames.X509_URL, publicKey);
        try {
            return jws.getCompactSerialization();
        } catch (JoseException e) {
            throw new RuntimeException("Giriş belirteci üretilemedi", e);
        }
    }
}
