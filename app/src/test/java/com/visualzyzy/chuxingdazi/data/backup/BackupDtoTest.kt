package com.visualzyzy.chuxingdazi.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份 DTO 序列化往返（字段模板升级 2026-11-21）：places[].customSchema 可选字段——
 * 新备份带数组、旧备份缺省 null；version 保持 0.5 不做版本分支。
 */
class BackupDtoTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `新备份 customSchema 数组往返保真`() {
        val body = """
            {"format":"chuxingdazi-backup","version":0.5,"exportedAt":0,
             "categories":[],"lists":[],
             "places":[{"id":"p1","name":"A","address":null,"lat":30.0,"lng":120.0,"categoryId":"food",
                        "fieldValues":{},"customSchema":[{"key":"p_a1b2c3d4","label":"奥特曼","type":"TEXT"}],
                        "source":"SEARCH","visited":0}],
             "placeLists":[],"trips":[],"tripPlaces":[],"schedules":[]}
        """.trimIndent()

        val parsed = json.decodeFromString<BackupFile>(body)
        val schema = parsed.places[0].customSchema
        assertTrue(schema is JsonArray)
        assertEquals("p_a1b2c3d4", schema!!.jsonArray[0].jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals("奥特曼", schema.jsonArray[0].jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("TEXT", schema.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)

        val reEncoded = json.encodeToString(BackupFile.serializer(), parsed)
        val roundTrip = json.decodeFromString(BackupFile.serializer(), reEncoded)
        assertEquals(schema, roundTrip.places[0].customSchema)
    }

    @Test
    fun `旧备份缺 customSchema 解析为 null 不崩`() {
        val body = """
            {"format":"chuxingdazi-backup","version":0.5,"exportedAt":0,
             "categories":[],"lists":[],
             "places":[{"id":"p1","name":"A","address":null,"lat":30.0,"lng":120.0,"categoryId":"food",
                        "fieldValues":{},"source":"SEARCH","visited":0}],
             "placeLists":[],"trips":[],"tripPlaces":[],"schedules":[]}
        """.trimIndent()
        val parsed = json.decodeFromString<BackupFile>(body)
        assertNull(parsed.places[0].customSchema)
    }

    @Test
    fun `导出 DTO encode 含 customSchema 数组`() {
        val dto = PlaceDto(
            id = "p1", name = "A", address = null, lat = 30.0, lng = 120.0, categoryId = "food",
            fieldValues = Json.parseToJsonElement("""{"p_a1b2c3d4":"Taro"}"""),
            customSchema = Json.parseToJsonElement("""[{"key":"p_a1b2c3d4","label":"奥特曼","type":"TEXT"}]"""),
        )
        val encoded = json.encodeToString(PlaceDto.serializer(), dto)
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("Taro", obj["fieldValues"]!!.jsonObject["p_a1b2c3d4"]!!.jsonPrimitive.content)
        assertEquals(
            "TEXT",
            obj["customSchema"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content,
        )
    }
}
