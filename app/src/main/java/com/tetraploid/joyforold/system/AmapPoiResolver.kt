package com.tetraploid.joyforold.system

import android.content.Context
import com.tetraploid.joyforold.BuildConfig
import com.tetraploid.joyforold.caregiver.CaregiverSupportStore
import com.tetraploid.joyforold.network.JoyHttpClients
import com.tetraploid.joyforold.network.getText
import com.tetraploid.joyforold.privacy.SafeLog
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 高德 Web 地点检索：把「桂阳一中 / 肯德基」解析成坐标，供 androidamap://navi 直达导航。
 *
 * 需要在 local.properties 配置 `amap.web.key=`（开放平台「Web服务」Key）。
 */
object AmapPoiResolver {
    data class Poi(
        val name: String,
        val lat: Double,
        val lon: Double,
        val address: String = "",
        val distanceMeters: Int? = null,
    )

    private const val DEFAULT_CANDIDATE_LIMIT = 5

    private val httpClient: HttpClient by lazy { JoyHttpClients.quick() }
    private val json = Json { ignoreUnknownKeys = true }

    fun webKey(): String = BuildConfig.AMAP_WEB_KEY.trim()

    fun isConfigured(): Boolean = webKey().isNotBlank()

    /**
     * 优先周边检索（最近）；无定位时按城市关键词检索取第一条。
     * [regionOrLandmark] 非空时：在指定行政区/地标周边搜，**不用**设备定位、也不用家里城市去限流。
     */
    fun resolveNearest(context: Context, keyword: String, regionOrLandmark: String? = null): Poi? =
        searchCandidates(context, keyword, limit = 1, regionOrLandmark = regionOrLandmark).firstOrNull()

    /** 返回多条候选，供 Cortana 列表挑选。 */
    fun searchCandidates(
        context: Context,
        keyword: String,
        limit: Int = DEFAULT_CANDIDATE_LIMIT,
        regionOrLandmark: String? = null,
    ): List<Poi> {
        val key = webKey()
        if (key.isBlank() || keyword.isBlank()) return emptyList()
        val capped = limit.coerceIn(1, 10)
        val scope = regionOrLandmark?.trim().orEmpty()
        return try {
            if (scope.isNotBlank()) {
                searchNearLandmark(context, scope, keyword, capped)
            } else {
                val location = DeviceLocation.lastKnown(context)
                if (location != null) {
                    searchAroundList(key, keyword, location.latitude, location.longitude, capped).ifEmpty {
                        searchTextList(key, keyword, inferCity(context), capped)
                    }
                } else {
                    searchTextList(key, keyword, inferCity(context), capped)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 「地标/行政区附近的品类」：
     * 信任高德相关度——先 place/text 取最相关地标，再 around 搜品类；
     * 不行就整句「地标+品类」文本检索取第一条。
     */
    fun searchNearLandmark(
        context: Context,
        landmark: String,
        keyword: String,
        limit: Int = DEFAULT_CANDIDATE_LIMIT,
    ): List<Poi> {
        val key = webKey()
        if (key.isBlank() || landmark.isBlank() || keyword.isBlank()) return emptyList()
        val capped = limit.coerceIn(1, 10)
        val city = extractCityHintFromPlace(landmark)
        return try {
            val anchor = resolveLandmark(context, landmark)
            if (anchor != null) {
                val around = searchAroundList(key, keyword, anchor.lat, anchor.lon, capped, radiusMeters = 10_000)
                if (around.isNotEmpty()) {
                    SafeLog.i("POI周边命中：${anchor.name} 附近 → ${around.first().name}")
                    around
                } else {
                    val combined = searchTextBest(key, "$landmark$keyword", city, capped)
                    SafeLog.i(
                        if (combined.isNotEmpty()) {
                            "POI整句命中：$landmark$keyword → ${combined.first().name}"
                        } else {
                            "POI周边/整句均未命中：landmark=$landmark keyword=$keyword"
                        },
                    )
                    combined
                }
            } else {
                val combined = searchTextBest(key, "$landmark$keyword", city, capped)
                    .ifEmpty { searchTextBest(key, keyword, city, capped) }
                SafeLog.i(
                    if (combined.isNotEmpty()) {
                        "POI整句命中（无地标坐标）：$landmark$keyword → ${combined.first().name}"
                    } else {
                        "POI未命中：landmark=$landmark keyword=$keyword"
                    },
                )
                combined
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 解析地标：关键词交给高德 place/text，取相关度最高的一条。
     * city 只作偏好，默认不用 citylimit 强限（强限容易把相关结果滤空）。
     */
    fun resolveLandmark(context: Context, landmark: String): Poi? {
        val key = webKey()
        if (key.isBlank() || landmark.isBlank()) return null
        return try {
            val cityHint = extractCityHintFromPlace(landmark)
                ?: inferCity(context)?.takeIf { !looksLikeAdminRegion(landmark) }
            val hit = searchTextBest(key, landmark, cityHint, limit = 1).firstOrNull()
                ?: searchTextBest(key, landmark, city = null, limit = 1).firstOrNull()
                ?: landmarkQueryVariants(landmark).asSequence()
                    .filter { it != landmark }
                    .mapNotNull { q ->
                        searchTextBest(key, q, cityHint, limit = 1).firstOrNull()
                            ?: searchTextBest(key, q, city = null, limit = 1).firstOrNull()
                    }
                    .firstOrNull()
                ?: geocodeAddress(landmark, cityHint = cityHint)
            if (hit != null) {
                SafeLog.i("地标相关度首选：「$landmark」→ ${hit.name}")
            } else {
                SafeLog.i("地标解析失败：$landmark")
            }
            hit
        } catch (_: Exception) {
            null
        }
    }

    /** 「郴州市一中」轻量别名，仅作 place/text 召回兜底。 */
    internal fun landmarkQueryVariants(landmark: String): List<String> {
        val t = landmark.trim()
        if (t.isBlank()) return emptyList()
        val out = linkedSetOf(t)
        out += t.replace("市一中", "一中").replace("县一中", "一中")
        Regex("""^(.+?)市(.+)$""").find(t)?.let { m ->
            val city = m.groupValues[1]
            val rest = m.groupValues[2]
            out += "$city$rest"
            if (rest == "一中" || rest == "二中" || rest == "三中") {
                out += "${city}市第${rest.first()}中学"
                out += "${city}第${rest.first()}中学"
            }
        }
        if (t.endsWith("一中") && !t.contains("第一")) {
            out += t.replace("一中", "第一中学")
        }
        return out.toList()
    }

    /** 从「郴州市北湖区」提取高德 city 参数倾向：优先到「市」。 */
    internal fun extractCityHintFromPlace(place: String): String? {
        val t = place.trim()
        if (t.isBlank()) return null
        Regex("""([\u4e00-\u9fa5]{2,12}(?:省|市|自治州|地区|盟))""").find(t)?.groupValues?.get(1)?.let { return it }
        Regex("""([\u4e00-\u9fa5]{2,12}(?:区|县|旗|镇))""").find(t)?.groupValues?.get(1)?.let { return it }
        return null
    }

    /**
     * 纯行政区（郴州市北湖区），不含学校/车站等地标。
     * 「郴州市一中」含「市」但不是行政区。
     */
    internal fun looksLikeAdminRegion(place: String): Boolean {
        val t = place.trim()
        if (t.isBlank()) return false
        if (t.contains(Regex("""一中|二中|三中|中学|小学|大学|学院|学校|高铁站|火车站|机场|医院|公园|广场|商场"""))) {
            return false
        }
        return t.matches(Regex("""[\u4e00-\u9fa5]{2,20}(?:省|市|自治州|地区|盟|区|县|旗|镇|乡|街道)+""")) ||
            t.contains(Regex("""(?:省|市|自治州|地区|盟).*(?:区|县|旗|镇|乡|街道)$"""))
    }

    /** 具体地址 → 地理编码取坐标。 */
    fun geocodeAddress(address: String, cityHint: String? = null): Poi? {
        val key = webKey()
        if (key.isBlank() || address.isBlank()) return null
        return try {
            val encoded = URLEncoder.encode(address, StandardCharsets.UTF_8.name())
            val cityParam = cityHint?.takeIf { it.isNotBlank() }?.let {
                "&city=${URLEncoder.encode(it, StandardCharsets.UTF_8.name())}"
            }.orEmpty()
            val body = runBlocking {
                httpClient.getText(
                    "https://restapi.amap.com/v3/geocode/geo?key=$key&address=$encoded$cityParam",
                )
            }
            parseGeocode(body, fallbackName = address)
        } catch (_: Exception) {
            null
        }
    }

    internal fun parsePlaceList(raw: String): List<Poi> {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        if (root.string("status") != "1") return emptyList()
        val pois = root.array("pois") ?: return emptyList()
        return buildList {
            for (i in 0 until pois.size) {
                val item = pois[i].jsonObject
                val name = item.string("name") ?: continue
                val location = item.string("location") ?: continue
                val parts = location.split(",")
                if (parts.size < 2) continue
                val lon = parts[0].toDoubleOrNull() ?: continue
                val lat = parts[1].toDoubleOrNull() ?: continue
                val address = listOfNotNull(
                    item.string("pname"),
                    item.string("cityname"),
                    item.string("adname"),
                    item.string("address"),
                ).distinct().filter { it.isNotBlank() && it != name }.joinToString("")
                val distance = item.string("distance")?.toIntOrNull()
                add(Poi(name = name, lat = lat, lon = lon, address = address, distanceMeters = distance))
            }
        }
    }

    internal fun parsePlaceFirst(raw: String): Poi? = parsePlaceList(raw).firstOrNull()

    internal fun parseGeocode(raw: String, fallbackName: String): Poi? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        if (root.string("status") != "1") return null
        val geos = root.array("geocodes") ?: return null
        if (geos.isEmpty()) return null
        val item = geos[0].jsonObject
        val name = item.string("formatted_address")?.ifBlank { null } ?: fallbackName
        val location = item.string("location") ?: return null
        val parts = location.split(",")
        if (parts.size < 2) return null
        val lon = parts[0].toDoubleOrNull() ?: return null
        val lat = parts[1].toDoubleOrNull() ?: return null
        return Poi(name = name, lat = lat, lon = lon, address = name)
    }

    private fun searchAroundList(
        key: String,
        keyword: String,
        lat: Double,
        lon: Double,
        limit: Int,
        radiusMeters: Int = 5_000,
    ): List<Poi> {
        val encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8.name())
        val loc = "$lon,$lat"
        val radius = radiusMeters.coerceIn(1_000, 50_000)
        val body = runBlocking {
            httpClient.getText(
                "https://restapi.amap.com/v3/place/around?key=$key&location=$loc&keywords=$encoded" +
                    "&radius=$radius&sortrule=distance&offset=$limit&extensions=base",
            )
        }
        return parsePlaceList(body)
    }

    private fun searchTextList(
        key: String,
        keyword: String,
        city: String?,
        limit: Int,
        cityLimit: Boolean = false,
    ): List<Poi> {
        val encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8.name())
        val cityParam = city?.takeIf { it.isNotBlank() }?.let {
            val enc = URLEncoder.encode(it, StandardCharsets.UTF_8.name())
            // city 作偏好；citylimit=true 才会强限，默认 false 以保留高德相关召回
            if (cityLimit) "&city=$enc&citylimit=true" else "&city=$enc"
        }.orEmpty()
        val body = runBlocking {
            httpClient.getText(
                "https://restapi.amap.com/v3/place/text?key=$key&keywords=$encoded$cityParam" +
                    "&offset=$limit&extensions=base",
            )
        }
        return parsePlaceList(body)
    }

    /** place/text 取相关度最高的结果；先城市偏好，空再全国。 */
    private fun searchTextBest(
        key: String,
        keyword: String,
        city: String?,
        limit: Int,
    ): List<Poi> {
        if (keyword.isBlank()) return emptyList()
        return searchTextList(key, keyword, city, limit, cityLimit = false).ifEmpty {
            if (city.isNullOrBlank()) {
                emptyList()
            } else {
                searchTextList(key, keyword, city = null, limit, cityLimit = false)
            }
        }
    }

    private fun inferCity(context: Context): String? {
        val home = CaregiverSupportStore(context).loadHomeAddress().trim()
        if (home.isBlank()) return null
        Regex("""([\u4e00-\u9fa5]{2,10}(?:市|县|州|区))""").find(home)?.groupValues?.get(1)?.let { return it }
        return home.split(Regex("""[省市区县\s]""")).firstOrNull { it.length >= 2 }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.array(key: String): JsonArray? =
        this[key]?.jsonArray
}
