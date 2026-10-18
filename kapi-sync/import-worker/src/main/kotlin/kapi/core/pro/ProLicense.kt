package kapi.core.pro

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.*

data class ProLicenseInfo(val id: String, val holder: String)
/** Only operator-signed permanent software licenses are accepted; no payment callback grants access. */
object ProLicense {
    const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETWQ/vgIUK7zPYZFxdWzY7DxaHw6RUVefPoPlbAFC8Fj7dyuliHrCwu8jn1AdWQ/XriPfN7JCgFZs4xk5eM6vrA=="
    fun verify(code: String, publicKey: String = PUBLIC_KEY): ProLicenseInfo? = runCatching { verifyOrThrow(code,publicKey) }.getOrNull()
    fun verifyOrThrow(code: String, publicKey: String = PUBLIC_KEY): ProLicenseInfo {
        require(code.length in 32..4096)
        val parts = code.trim().split('.')
        require(parts.size == 3 && parts[0] == "GZ1")
        val bytes = Base64.getUrlDecoder().decode(parts[1])
        val signature = Base64.getUrlDecoder().decode(parts[2])
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        require(Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(bytes); verify(signature) })
        val obj = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(obj["product"]?.jsonPrimitive?.content == "guizhang-pro")
        require(obj["permanent"]?.jsonPrimitive?.boolean == true)
        val id = obj.getValue("id").jsonPrimitive.content
        val holder = obj.getValue("holder").jsonPrimitive.content.trim()
        require(id.length in 8..80 && holder.length in 1..80)
        return ProLicenseInfo(id, holder)
    }
}
