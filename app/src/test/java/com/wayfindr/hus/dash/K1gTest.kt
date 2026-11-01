package com.wayfindr.hus.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Kotlin port must build byte-identical packets to the Pi's k1g.py (expected values were
 *  printed by k1g.py on 2026-11-01). */
class K1gTest {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun routeCard() = assertEquals(
        "0077001100000000020100054b314720000501000d416e64726f6964204175746f00050200013c05030001ff0505000200000506" +
        "0001300507000130050800043033303305540001300509000200000546000110050a000155050c000104050b00063030313030" +
        "3005550001200605000155060d0001aa",
        K1g.routeCard("Android Auto", true).hex())

    @Test fun idleRouteCard() = assertEquals(
        "0077001100000000020100054b314720000501000d416e64726f6964204175746f0005020001ff05030001ff0505000200000506" +
        "0001000507000100050800042020202005540001300509000200000546000100050a000155050c000104050b00063030313030" +
        "30055500012006050001aa060d0001aa",
        K1g.idleRouteCard("Android Auto", false).hex())

    @Test fun heartbeat() = assertEquals(
        "0049000b00000000020100054b31472000060800010506100001410603000155060400019d060f0001550601000101054c000113" +
        "052d00020000051b0001190521000132054d000132",
        K1g.heartbeat(57, true).hex())

    @Test fun nowPlaying() = assertEquals(
        "0037000200000000020100054b31472000050d0022446f20492057616e6e61204b6e6f773f00414d00417263746963204d6f6e6b657973",
        K1g.nowPlaying("Do I Wanna Know?", "AM", "Arctic Monkeys").hex())

    @Test fun hostname() = assertEquals(
        "001e000200000000020100054b31472001060b000957617966696e645200",
        K1g.hostnameAnnounce("WayfindR").hex())

    @Test fun seq() = assertEquals(
        "0016000200000000020100054b314720070517000155",
        K1g.patchSeq(K1g.musicActive(true), 7).hex())
}
