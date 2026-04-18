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
    fun toStringList(value: String): List<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, type) ?: emptyList()
    }

    @TypeConverter
    fun fromCustomFields(value: List<CustomFieldEntity>): String = gson.toJson(value)

    @TypeConverter
    fun toCustomFields(value: String): List<CustomFieldEntity> {
        val type = object : TypeToken<List<CustomFieldEntity>>() {}.type
        return gson.fromJson(value, type) ?: emptyList()
    }
}
