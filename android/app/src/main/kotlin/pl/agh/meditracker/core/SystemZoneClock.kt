package pl.agh.meditracker.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

// Zegar systemowy, który przy każdym odczycie bierze bieżącą strefę telefonu.
// Clock.systemDefaultZone() zapamiętuje strefę z chwili utworzenia, a Android nie restartuje procesu po jej zmianie.
object SystemZoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)

    override fun instant(): Instant = Instant.now()

    override fun millis(): Long = System.currentTimeMillis()
}
