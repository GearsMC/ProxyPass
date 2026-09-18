# ProxyPass

### Introduction

Proxy pass allows developers to MITM a vanilla client and server without modifying them. This allows for easy testing 
of the Bedrock Edition protocol and observing vanilla network behavior.

__ProxyPass requires  Java 8 u162 or later to function correctly due to the encryption used during login__<br>
`online-mode` __needs to be set to__ `false` __in__ `server.properties` __so that ProxyPass can communicate with your Bedrock Dedicated Server.__

### Building & Running
To produce a jar file, run `./gradlew shadowJar` in the project root directory. This will produce a jar file in the `build/libs` directory.

If you wish to run the project from source, run `./gradlew run` in the project root directory.

### Links

__[Jenkins](https://ci.opencollab.dev/job/NukkitX/job/ProxyPass/job/master/)__

__[Protocol library](https://github.com/CloudburstMC/Protocol) used in this project__

## GearsMC fork'u

Bu fork ProxyPass'i iki noktada değiştirir:

1. **Protokol kütüphanesi GearsMC/Protocol.** Kardeş klasördeki `../Protocol` composite build ile bağlanır
   (`settings.gradle.kts`). Yeni Bedrock sürümünde kodeği kendimiz eklediğimiz için yukarı akışı beklemeyiz.
   Fork'taki adlandırma farkları uyarlandı: `ItemComponentPacket` → `ItemRegistryPacket`, `org.cloudburstmc.protocol.common.*`
   sınıfları `...bedrock.packet`/`...bedrock.definition` altında, tanım erişimcileri record biçiminde (`runtimeId()`).
2. **İstemcisiz döküm kipi** (`org.cloudburstmc.proxypass.dump`). Gerçek bir Minecraft istemcisi olmadan sunucuya
   kendisi bağlanır, kayıt paketlerini `data/` altına yazar ve çıkar:

   ```bash
   java -jar ProxyPass.jar dump <host> <port> [saniye]
   ```

   Üretilen dosyalar: `entity_identifiers.dat`, `recipes.json`, `creative_items.json`, `item_components.nbt`,
   `runtime_item_states.json`, `biome_definitions.json`, `stripped_biome_definitions.json`, `voxel_shapes.json`,
   `data_driven_blocks.nbt`, `entity_properties.nbt`, `jigsaw_structure_data.nbt`.

### NetherNet (deneysel)

`dump-nethernet <host> <keşif portu, BDS'te 7551> [saniye]` kipi CloudburstMC/network'ün `nethernet` dalındaki
taşımayı kullanır (LAN keşfi; Microsoft oturumu gerekmez). Kütüphane `publishToMavenLocal` ile kurulur.

Ölçülenler (2026-09-18, BDS 1.26.51):
- LAN keşfi ve WebRTC veri kanalı kuruluyor ("NetherNet Connection Established").
- **NetherNet mesajında RakNet'teki 0xFE çerçeve kimliği yok**; kimlikle gönderilen istek yanıtsız kalıyor, kimliksiz
  gönderilince sunucu `NetworkSettings` ile cevap veriyor.
- Kanalda RakNet'e özgü seçenek olmadığı için paket kodeği ve sıkıştırma elle kuruluyor
  (`NetherNetBedrockInitializer`).
- **Açık nokta:** `NetworkSettings`'ten sonra gönderilen giriş paketinin ardından sunucu veri kanalını sessizce
  kapatıyor (`disconnect.lost`, BDS günlüğünde kayıt yok). Sıkıştırma anlaşması ya da giriş biçiminde bir ayrıntı
  eksik; döküm bu adımdan sonrasına geçemiyor.

### Bilinen sınır: BDS artık RakNet kabul etmiyor

1.26.51 BDS açılışta "NetherNet is the only supported transport type" diyor; `transport=raknet` ile RakNet portunu
açsa da giriş isteğini `Connection Request invalid` ile reddediyor (2026-09-18'de denendi, aynı giriş Allay tarafından
kabul ediliyor). Yani vanilla veriyi bu araçla BDS'ten çekmek için NetherNet gerekiyor. Araç RakNet konuşan
sunucularda (Allay, eski BDS sürümleri, PocketMine) çalışır.
