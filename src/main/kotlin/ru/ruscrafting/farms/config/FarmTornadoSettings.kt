package ru.ruscrafting.farms.config

data class FarmTornadoSettings(
    val warningSeconds: Int = 6,
    val durationSeconds: Int = 45,
    val speed: Double = 2.4,
    val height: Double = 24.0,
    val radius: Double = 7.0,
    val debrisCount: Int = 28,
    val hitDamage: Double = 2.0,
) {
    init {
        require(warningSeconds in 3..15) { "tornado.warning-seconds must be in 3..15" }
        require(durationSeconds in 10..180) { "tornado.duration-seconds must be in 10..180" }
        require(speed.isFinite() && speed in 0.5..4.0) { "tornado.speed must be in 0.5..4.0" }
        require(height.isFinite() && height in 12.0..40.0) { "tornado.height must be in 12..40" }
        require(radius.isFinite() && radius in 4.0..10.0) { "tornado.radius must be in 4..10" }
        require(debrisCount in 8..40) { "tornado.debris-count must be in 8..40" }
        require(hitDamage.isFinite() && hitDamage in 0.0..8.0) { "tornado.hit-damage must be in 0..8" }
    }
}
