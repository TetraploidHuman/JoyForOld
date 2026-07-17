package com.tetraploid.joyforold.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapPoiResolverTest {
    @Test
    fun parsePlaceList_returnsMultiple() {
        val json = """
            {
              "status":"1",
              "pois":[
                {"name":"桂阳县第一中学","location":"112.74,25.75","address":"向阳路","distance":"1200"},
                {"name":"桂阳一中(北校区)","location":"112.75,25.76","adname":"桂阳县","distance":"3500"}
              ]
            }
        """.trimIndent()
        val list = AmapPoiResolver.parsePlaceList(json)
        assertEquals(2, list.size)
        assertEquals("桂阳县第一中学", list[0].name)
        assertEquals(1200, list[0].distanceMeters)
        assertEquals("桂阳一中(北校区)", list[1].name)
    }

    @Test
    fun parsePlaceFirst_readsNearestPoi() {
        val json = """
            {
              "status":"1",
              "pois":[
                {"name":"桂阳一中","location":"112.7401,25.7542"},
                {"name":"其他","location":"1,1"}
              ]
            }
        """.trimIndent()
        val poi = AmapPoiResolver.parsePlaceFirst(json)
        assertNotNull(poi)
        assertEquals("桂阳一中", poi!!.name)
        assertEquals(25.7542, poi.lat, 0.00001)
        assertEquals(112.7401, poi.lon, 0.00001)
    }

    @Test
    fun parsePlaceFirst_nullWhenEmpty() {
        assertNull(AmapPoiResolver.parsePlaceFirst("""{"status":"1","pois":[]}"""))
        assertNull(AmapPoiResolver.parsePlaceFirst("""{"status":"0","info":"INVALID_USER_KEY"}"""))
    }

    @Test
    fun parseGeocode_readsLocation() {
        val json = """
            {
              "status":"1",
              "geocodes":[
                {"formatted_address":"湖南省郴州市桂阳县…","location":"112.73,25.75"}
              ]
            }
        """.trimIndent()
        val poi = AmapPoiResolver.parseGeocode(json, fallbackName = "家")
        assertNotNull(poi)
        assertEquals(25.75, poi!!.lat, 0.00001)
        assertEquals(112.73, poi.lon, 0.00001)
    }

    @Test
    fun extractCityHint_prefersCity() {
        assertEquals("郴州市", AmapPoiResolver.extractCityHintFromPlace("郴州市北湖区"))
        assertEquals("北湖区", AmapPoiResolver.extractCityHintFromPlace("北湖区"))
        assertEquals("郴州市", AmapPoiResolver.extractCityHintFromPlace("郴州市一中"))
    }

    @Test
    fun looksLikeAdminRegion() {
        assertTrue(AmapPoiResolver.looksLikeAdminRegion("郴州市北湖区"))
        assertTrue(!AmapPoiResolver.looksLikeAdminRegion("桂阳一中"))
        assertTrue(!AmapPoiResolver.looksLikeAdminRegion("郴州市一中"))
        assertTrue(!AmapPoiResolver.looksLikeAdminRegion("郴州高铁站"))
    }

    @Test
    fun landmarkQueryVariants_schoolAliases() {
        val variants = AmapPoiResolver.landmarkQueryVariants("郴州市一中")
        assertTrue(variants.contains("郴州市一中"))
        assertTrue(variants.contains("郴州一中"))
        assertTrue(variants.any { it.contains("第一中学") })
    }
}
