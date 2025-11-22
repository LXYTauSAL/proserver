/*
package jp.assasans.protanki.server.serialization

import java.lang.reflect.Type
import com.squareup.moshi.*
import jp.assasans.protanki.server.client.BattleData
import jp.assasans.protanki.server.client.DmBattleData
import jp.assasans.protanki.server.client.TeamBattleData

class BattleDataJsonAdapterFactory : JsonAdapter.Factory {
  override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
    if(type.rawType == BattleData::class.java) {
      return object : JsonAdapter<BattleData>() {
        override fun fromJson(reader: JsonReader): BattleData? {
          TODO("Not yet implemented")
        }

        override fun toJson(writer: JsonWriter, value: BattleData?) {
          when(value) {
            null              -> writer.nullValue()
            is DmBattleData   -> moshi.adapter(DmBattleData::class.java).toJson(writer, value)
            is TeamBattleData -> moshi.adapter(TeamBattleData::class.java).toJson(writer, value)
            else              -> throw IllegalArgumentException("Unknown battle data type: ${value::class}")
          }
        }
      }
    }
    return null
  }
}
*/
package jp.assasans.protanki.server.serialization

import com.squareup.moshi.*
import java.lang.reflect.Type
import jp.assasans.protanki.server.client.BattleData
import jp.assasans.protanki.server.client.DmBattleData
import jp.assasans.protanki.server.client.TeamBattleData

class BattleDataJsonAdapterFactory : JsonAdapter.Factory {
  override fun create(
    type: Type,
    annotations: MutableSet<out Annotation>,
    moshi: Moshi
  ): JsonAdapter<*>? {
    if(type != BattleData::class.java) return null

    return object : JsonAdapter<BattleData>() {
      override fun fromJson(reader: JsonReader): BattleData? {
        if(reader.peek() == JsonReader.Token.NULL) {
          reader.nextNull<Unit>()
          return null
        }

        // 先窥探一遍字段，判断是 DM 还是 Team
        val peeked = reader.peekJson()
        var hasUsers = false
        var hasUsersRed = false

        peeked.beginObject()
        while(peeked.hasNext()) {
          when(peeked.nextName()) {
            "users" -> {
              hasUsers = true
              peeked.skipValue()
            }

            "usersRed" -> {
              hasUsersRed = true
              peeked.skipValue()
            }

            else -> peeked.skipValue()
          }
        }
        peeked.endObject()
        // peeked.close() 不必显式关，会共享同一 source

        return when {
          hasUsersRed ->
            moshi.adapter(TeamBattleData::class.java).fromJson(reader)
              ?: throw JsonDataException("Failed to parse TeamBattleData")

          hasUsers ->
            moshi.adapter(DmBattleData::class.java).fromJson(reader)
              ?: throw JsonDataException("Failed to parse DmBattleData")

          else -> throw JsonDataException("Unknown BattleData JSON: neither 'users' nor 'usersRed' present")
        }
      }

      override fun toJson(writer: JsonWriter, value: BattleData?) {
        when(value) {
          is DmBattleData   -> moshi.adapter(DmBattleData::class.java).toJson(writer, value)
          is TeamBattleData -> moshi.adapter(TeamBattleData::class.java).toJson(writer, value)
          null              -> writer.nullValue()
        }
      }
    }
  }
}
