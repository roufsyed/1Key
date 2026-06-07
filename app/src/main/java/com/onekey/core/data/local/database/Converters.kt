package com.onekey.core.data.local.database

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.onekey.core.data.local.entity.CustomFieldEntity

class Converters {

    private val gson = Gson()

    @TypeConverter
    fun fromStringList(value: List<String>): String = gson.toJson(value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        gson.fromJson(value, STRING_LIST_TYPE) ?: emptyList()

    @TypeConverter
    fun fromCustomFields(value: List<CustomFieldEntity>): String = gson.toJson(value)

    @TypeConverter
    fun toCustomFields(value: String): List<CustomFieldEntity> =
        gson.fromJson(value, CUSTOM_FIELDS_TYPE) ?: emptyList()

    // Hoisted to companion fields so the anonymous-inner-class TypeToken isn't allocated
    // on every JSON round-trip - a 1k-credential read otherwise produces ~2k throwaway
    // TypeToken instances and matching reflective Type lookups.
    private companion object {
        private val STRING_LIST_TYPE = object : TypeToken<List<String>>() {}.type
        private val CUSTOM_FIELDS_TYPE = object : TypeToken<List<CustomFieldEntity>>() {}.type
    }
}
