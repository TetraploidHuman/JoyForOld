package com.tetraploid.joyforold.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.tetraploid.joyforold.caregiver.CaregiverSupportStore

data class ResolvedContact(
    val query: String,
    val displayName: String,
    val phoneNumber: String,
    val source: String,
)

object ContactResolver {
    fun resolve(
        context: Context,
        query: String?,
        caregiverStore: CaregiverSupportStore? = null,
    ): ResolvedContact? {
        val normalized = query?.trim().orEmpty()
        if (normalized.isBlank()) return null

        caregiverStore?.findContact(normalized)?.let { family ->
            if (family.phoneNumber.isNotBlank()) {
                return ResolvedContact(
                    query = normalized,
                    displayName = family.displayName.ifBlank { family.alias },
                    phoneNumber = family.phoneNumber,
                    source = "family_store",
                )
            }
        }

        if (looksLikePhoneNumber(normalized)) {
            return ResolvedContact(
                query = normalized,
                displayName = normalized,
                phoneNumber = normalized.filter { it.isDigit() || it == '+' },
                source = "direct_number",
            )
        }

        if (!hasContactsPermission(context)) return null
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$normalized%")
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            args,
            null,
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                val number = cursor.getString(numberIndex)?.trim().orEmpty()
                if (name.isBlank() || number.isBlank()) continue
                return ResolvedContact(
                    query = normalized,
                    displayName = name,
                    phoneNumber = number,
                    source = "contacts",
                )
            }
        }
        return null
    }

    fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun looksLikePhoneNumber(text: String): Boolean {
        val compact = text.filter { it.isDigit() || it == '+' || it == '-' || it == ' ' }
        val digits = compact.count { it.isDigit() }
        return digits >= 6 && digits >= text.length / 2
    }
}
