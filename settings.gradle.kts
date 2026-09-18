rootProject.name = "ProxyPass"

// GearsMC: protokol kütüphanesi olarak kendi fork'umuz (GearsMC/Protocol) kullanılır; kardeş klasör olarak beklenir.
// Böylece yeni Bedrock sürümünde kodeği kendimiz ekleyip aynı gün döküm alabiliriz.
val protocolDir = file("../Protocol")
if (protocolDir.isDirectory) {
    includeBuild(protocolDir)
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version ("0.8.0")
}
