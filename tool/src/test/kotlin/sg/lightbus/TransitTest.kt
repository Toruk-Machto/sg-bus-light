package sg.lightbus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransitTest {
    @Test
    fun `a direct bus must reach the destination after boarding in the same direction`() {
        val routes = listOf(
            stop("23", 1, 2, "01012"),
            stop("23", 1, 1, "01013"),
            stop("23", 2, 3, "01013"),
            stop("23", 2, 2, "01012"),
            stop("24", 1, 1, "01012"),
            stop("24", 2, 2, "01013"),
        )

        val match = directBuses(routes, "01012", "01013").single()

        assertEquals("23", match.service)
        assertEquals(2, match.direction)
        assertEquals(2, match.board.sequence)
        assertEquals(3, match.alight.sequence)
    }

    @Test
    fun `a loop can reach a destination on a later visit to that stop`() {
        val routes = listOf(
            stop("25", 1, 1, "01013"),
            stop("25", 1, 2, "01012"),
            stop("25", 1, 4, "01012"),
            stop("25", 1, 5, "01013"),
        )

        val match = directBuses(routes, "01012", "01013").single()

        assertEquals(4, match.board.sequence)
        assertEquals(5, match.alight.sequence)
        assertTrue(directBuses(routes, "01012", "01012").isEmpty())
    }

    @Test
    fun `arrival metadata follows the sorted ETA and blank entries stay empty`() {
        val arrivals = parseArrivals(
            """
            {"BusStopCode":"01012","Services":[{"ServiceNo":"25",
             "NextBus":{"EstimatedArrival":"2026-11-13T10:05:00+08:00","Monitored":"1","VisitNumber":1},
             "NextBus2":{"EstimatedArrival":"2026-11-13T10:02:00+08:00","Monitored":0,"VisitNumber":"2",
                         "DestinationCode":"01013","Load":"SEA","Type":"DD"},
             "NextBus3":{"EstimatedArrival":"","Monitored":"","VisitNumber":""}}]}
            """.trimIndent(),
            "01012",
        )

        val merged = mergeServices(emptyList(), arrivals).single()
        val first = assertNotNull(merged.predictions.first())
        val second = assertNotNull(merged.predictions[1])

        assertEquals("01013", first.destinationCode)
        assertEquals("Seats", first.crowdingLabel)
        assertEquals("DD", first.vehicleType)
        assertEquals(2, first.visitNumber)
        assertTrue(first.scheduled)
        assertFalse(second.scheduled)
        assertEquals(1, second.visitNumber)
        assertNull(arrivals.single().third?.monitored)
        assertNull(merged.predictions[2])
        assertNull(merged.times[2])
    }

    private fun stop(service: String, direction: Int, sequence: Int, code: String) =
        BusRouteStop(stopCode = code, service = service, direction = direction, sequence = sequence)
}
