package com.tetraploid.joyforold.caregiver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class FamilyContact(
    val alias: String,
    val displayName: String = "",
    val phoneNumber: String = "",
    val preferredApp: String = "",
) {
    fun matches(query: String): Boolean {
        val normalized = query.trim()
        if (normalized.isBlank()) return false
        return alias.equals(normalized, ignoreCase = true) ||
            displayName.equals(normalized, ignoreCase = true) ||
            normalized.contains(alias, ignoreCase = true) ||
            normalized.contains(displayName, ignoreCase = true)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("alias", alias)
        put("display_name", displayName)
        put("phone_number", phoneNumber)
        put("preferred_app", preferredApp)
    }

    companion object {
        fun fromJson(json: JSONObject): FamilyContact = FamilyContact(
            alias = json.optString("alias"),
            displayName = json.optString("display_name"),
            phoneNumber = json.optString("phone_number"),
            preferredApp = json.optString("preferred_app"),
        )
    }
}

class CaregiverSupportStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun ensureSeededDefaults() {
        if (prefs.contains(KEY_FAMILY_CONTACTS)) return
        saveFamilyContacts(DEFAULT_CONTACTS)
        saveEmergencyMessage(DEFAULT_EMERGENCY_MESSAGE)
    }

    fun loadFamilyContacts(): List<FamilyContact> {
        val raw = prefs.getString(KEY_FAMILY_CONTACTS, null).orEmpty()
        if (raw.isBlank()) return DEFAULT_CONTACTS
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(FamilyContact.fromJson(arr.getJSONObject(i)))
                }
            }.filter { it.alias.isNotBlank() }
        }.getOrDefault(DEFAULT_CONTACTS)
    }

    fun saveFamilyContacts(contacts: List<FamilyContact>) {
        val arr = JSONArray()
        contacts.filter { it.alias.isNotBlank() }.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_FAMILY_CONTACTS, arr.toString()).apply()
    }

    fun findContact(query: String?): FamilyContact? {
        val normalized = query?.trim().orEmpty()
        if (normalized.isBlank()) return null
        return loadFamilyContacts().firstOrNull { it.matches(normalized) }
    }

    fun loadEmergencyMessage(): String {
        return prefs.getString(KEY_EMERGENCY_MESSAGE, DEFAULT_EMERGENCY_MESSAGE)
            ?.trim()
            .orEmpty()
            .ifBlank { DEFAULT_EMERGENCY_MESSAGE }
    }

    fun saveEmergencyMessage(message: String) {
        prefs.edit().putString(KEY_EMERGENCY_MESSAGE, message.trim()).apply()
    }

    fun loadHomeAddress(): String {
        return prefs.getString(KEY_HOME_ADDRESS, "").orEmpty().trim()
    }

    fun saveHomeAddress(address: String) {
        prefs.edit().putString(KEY_HOME_ADDRESS, address.trim()).apply()
    }

    companion object {
        private const val PREFS_NAME = "joy_for_old_prefs"
        private const val KEY_FAMILY_CONTACTS = "caregiver_family_contacts"
        private const val KEY_EMERGENCY_MESSAGE = "caregiver_emergency_message"
        private const val KEY_HOME_ADDRESS = "caregiver_home_address"

        val DEFAULT_CONTACTS = listOf(
            FamilyContact(alias = "女儿"),
            FamilyContact(alias = "儿子"),
            FamilyContact(alias = "老伴"),
            FamilyContact(alias = "紧急联系人"),
        )

        const val DEFAULT_EMERGENCY_MESSAGE = "我现在需要帮助，请尽快联系我。"
    }
}
