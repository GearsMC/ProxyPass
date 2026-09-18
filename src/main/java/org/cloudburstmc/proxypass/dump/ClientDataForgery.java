package org.cloudburstmc.proxypass.dump;

import org.cloudburstmc.proxypass.ProxyPass;
import org.jose4j.json.internal.json_simple.JSONObject;

import java.util.Base64;
import java.util.UUID;

/**
 * GearsMC: istemcisiz döküm için en yalın istemci verisi (deri JWT'sinin gövdesi).
 *
 * <p>Gerçek istemci burada deri pikselleri, cihaz bilgisi ve sürüm gönderir. Sunucu girişte alanların varlığına bakar,
 * içeriğine değil; döküm için tek düze beyaz bir deri yeter. Alan listesi BDS reddederse buraya eklenir.</p>
 */
final class ClientDataForgery {

    private static final int SKIN_WIDTH = 64;
    private static final int SKIN_HEIGHT = 64;

    private ClientDataForgery() {
    }

    @SuppressWarnings("unchecked")
    static JSONObject build(String displayName) {
        var skin = new byte[SKIN_WIDTH * SKIN_HEIGHT * 4];
        for (var i = 0; i < skin.length; i++) {
            skin[i] = (byte) 0xff;
        }
        var encoder = Base64.getEncoder();
        var deviceId = UUID.randomUUID().toString();

        var data = new JSONObject();
        data.put("AnimatedImageData", new java.util.ArrayList<>());
        data.put("ArmSize", "wide");
        data.put("CapeData", "");
        data.put("CapeId", "");
        data.put("CapeImageHeight", 0);
        data.put("CapeImageWidth", 0);
        data.put("CapeOnClassicSkin", false);
        data.put("ClientRandomId", System.currentTimeMillis());
        data.put("CompatibleWithClientSideChunkGen", false);
        data.put("CurrentInputMode", 1);
        data.put("DefaultInputMode", 1);
        data.put("DeviceId", deviceId);
        data.put("DeviceModel", "GearsMC Dumper");
        data.put("DeviceOS", 7);
        data.put("GameVersion", ProxyPass.MINECRAFT_VERSION);
        data.put("GraphicsMode", 0);
        data.put("GuiScale", 0);
        data.put("IsEditorMode", false);
        data.put("LanguageCode", "en_US");
        data.put("MaxViewDistance", 8);
        data.put("MemoryTier", 4);
        data.put("OverrideSkin", false);
        data.put("PersonaPieces", new java.util.ArrayList<>());
        data.put("PersonaSkin", false);
        data.put("PieceTintColors", new java.util.ArrayList<>());
        data.put("PlatformOfflineId", "");
        data.put("PlatformOnlineId", "");
        data.put("PlatformType", 0);
        data.put("PlayFabId", deviceId.replace("-", "").substring(0, 16));
        data.put("PremiumSkin", false);
        data.put("SelfSignedId", UUID.randomUUID().toString());
        data.put("ServerAddress", "127.0.0.1:19132");
        data.put("SkinAnimationData", "");
        data.put("SkinColor", "#0");
        data.put("SkinData", encoder.encodeToString(skin));
        data.put("SkinGeometryData", "");
        data.put("SkinGeometryDataEngineVersion", "");
        data.put("SkinId", UUID.randomUUID() + ".Custom");
        data.put("SkinImageHeight", SKIN_HEIGHT);
        data.put("SkinImageWidth", SKIN_WIDTH);
        data.put("SkinResourcePatch", encoder.encodeToString(
                "{\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        data.put("ThirdPartyName", displayName);
        data.put("ThirdPartyNameOnly", false);
        data.put("TrustedSkin", false);
        data.put("UIProfile", 0);
        return data;
    }
}
