package com.weike.ime.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class UserModelProfile(val id: String, val name: String, val endpoint: ModelEndpointConfig)
enum class UserModelSlot(val title: String) {
    REPLY("帮你回"), VISION("截图识别回复"), SUPER_SPEAK("超会说")
}

/** Model metadata and independent bindings; credentials remain in Keystore-backed storage. */
class UserModelRepository private constructor(context: Context) {
    companion object {
        @Volatile private var instance: UserModelRepository? = null
        fun get(context: Context): UserModelRepository = instance ?: synchronized(this) {
            instance ?: UserModelRepository(context.applicationContext).also { instance = it }
        }
    }
    private val preferences = context.applicationContext.getSharedPreferences("user_model_profiles", Context.MODE_PRIVATE)
    private val secrets = SecureSecretStore(context)
    val changes = MutableStateFlow(0L)
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changes.value++ }
    init { preferences.registerOnSharedPreferenceChangeListener(listener) }
    @Synchronized fun all(): List<UserModelProfile> = runCatching {
        val array = JSONArray(preferences.getString("models", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            UserModelProfile(id, item.getString("name"), ModelEndpointConfig(item.getString("url"),
                secrets.read("user_model_$id").orEmpty(), item.getString("model"),
                runCatching { CloudProvider.valueOf(item.optString("provider")) }.getOrDefault(CloudProvider.CUSTOM), item.optBoolean("thinkingEnabled", false)))
        }
    }.getOrDefault(emptyList())
    fun selectedId(slot: UserModelSlot) = preferences.getString("slot_${slot.name}", "").orEmpty()
    fun endpoint(slot: UserModelSlot) = all().find { it.id == selectedId(slot) }?.endpoint ?: ModelEndpointConfig()
    @Synchronized fun select(slot: UserModelSlot, id: String) {
        require(id.isBlank() || all().any { it.id == id }) { "所选模型已不存在，请重新选择" }
        check(preferences.edit().putString("slot_${slot.name}", id).commit()) { "保存模型选择失败" }
        changes.value++
    }
    @Synchronized fun save(id: String?, name: String, endpoint: ModelEndpointConfig): UserModelProfile {
        require(endpoint.isComplete()) { "请完整填写模型参数" }
        val profile = UserModelProfile(id ?: UUID.randomUUID().toString(), name.trim().ifBlank { endpoint.model }, endpoint)
        val list = all().filterNot { it.id == profile.id } + profile
        secrets.write("user_model_${profile.id}", endpoint.apiKey.trim())
        val array = JSONArray()
        list.forEach { item -> array.put(JSONObject().put("id", item.id).put("name", item.name)
            .put("url", item.endpoint.url).put("model", item.endpoint.model).put("provider", item.endpoint.provider.name).put("thinkingEnabled", item.endpoint.thinkingEnabled)) }
        check(preferences.edit().putString("models", array.toString()).commit()) { "保存模型失败" }
        changes.value++
        return profile
    }
    @Synchronized fun remove(id: String) {
        val array = JSONArray()
        all().filterNot { it.id == id }.forEach { item -> array.put(JSONObject().put("id", item.id).put("name", item.name)
            .put("url", item.endpoint.url).put("model", item.endpoint.model).put("provider", item.endpoint.provider.name).put("thinkingEnabled", item.endpoint.thinkingEnabled)) }
        val edit = preferences.edit().putString("models", array.toString())
        UserModelSlot.entries.filter { selectedId(it) == id }.forEach { edit.remove("slot_${it.name}") }
        check(edit.commit()) { "删除模型失败" }
        secrets.write("user_model_$id", "")
        changes.value++
    }
    @Synchronized fun migrateLegacy(text: ModelEndpointConfig, vision: ModelEndpointConfig) {
        if (preferences.getBoolean("legacy_migrated", false)) return
        if (text.isComplete()) {
            val item = save(null, text.model, text)
            select(UserModelSlot.REPLY, item.id); select(UserModelSlot.SUPER_SPEAK, item.id)
        }
        if (vision.isComplete()) select(UserModelSlot.VISION, save(null, vision.model, vision).id)
        preferences.edit().putBoolean("legacy_migrated", true).commit()
    }
}
