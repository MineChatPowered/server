package org.winlogon.minechat

import io.objectbox.converter.PropertyConverter

import java.util.UUID

class UuidConverter : PropertyConverter<UUID?, String?> {
    override fun convertToEntityProperty(databaseValue: String?): UUID? {
        return databaseValue?.let { UUID.fromString(it) }
    }

    override fun convertToDatabaseValue(entityProperty: UUID?): String? {
        return entityProperty?.toString()
    }
}
