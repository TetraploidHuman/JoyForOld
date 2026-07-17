package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.system.AmapPoiResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavPoiPickCodecTest {
    @Test
    fun encodeDecode_roundTrip() {
        val poi = AmapPoiResolver.Poi(
            name = "桂阳一中",
            lat = 25.7542,
            lon = 112.7401,
            address = "向阳路",
            distanceMeters = 800,
        )
        val option = NavPoiPickCodec.toOption(poi, 0)
        assertTrue(option.label.startsWith("1. 桂阳一中"))
        val parsed = NavPoiPickCodec.parse(option.intentId)
        assertNotNull(parsed)
        assertEquals("桂阳一中", parsed!!.name)
        assertEquals(25.7542, parsed.lat, 0.00001)
        assertEquals(112.7401, parsed.lon, 0.00001)
    }

    @Test
    fun matchReply_firstIndex() {
        val options = listOf(
            NavPoiPickCodec.toOption(AmapPoiResolver.Poi("甲", 1.0, 2.0), 0),
            NavPoiPickCodec.toOption(AmapPoiResolver.Poi("乙", 3.0, 4.0), 1),
        )
        assertEquals("甲", NavPoiPickCodec.parse(NavPoiPickCodec.matchReply("第一个", options)!!.intentId)!!.name)
        assertEquals("乙", NavPoiPickCodec.parse(NavPoiPickCodec.matchReply("2", options)!!.intentId)!!.name)
        assertEquals("乙", NavPoiPickCodec.parse(NavPoiPickCodec.matchReply("去乙", options)!!.intentId)!!.name)
    }

    @Test
    fun matchReply_fuzzyHomophoneGuiyangToGuiyang() {
        val options = listOf(
            NavPoiPickCodec.toOption(
                AmapPoiResolver.Poi("桂阳县第一中学", 25.75, 112.74, address = "向阳路"),
                0,
            ),
            NavPoiPickCodec.toOption(AmapPoiResolver.Poi("桂阳三中", 25.76, 112.75), 1),
        )
        val matched = NavPoiPickCodec.matchReply("贵阳一中", options)
        assertNotNull(matched)
        assertEquals("桂阳县第一中学", NavPoiPickCodec.parse(matched!!.intentId)!!.name)
    }
}
