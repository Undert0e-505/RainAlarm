package com.rainalarm.app.alerts

import com.rainalarm.app.domain.QualitativeIntensity

internal object EnglishRainAlertTitleStrings : RainAlertTitleStrings {
    override fun approaching(
        placeName: String,
        likelySnow: Boolean,
        severity: QualitativeIntensity?,
    ): String {
        if (severity == null) {
            return if (likelySnow) "Snow likely approaching $placeName"
            else "Rain approaching $placeName"
        }
        val prefix = severity.englishLabel()
        return if (likelySnow) "$prefix snow likely approaching $placeName"
        else "$prefix rain approaching $placeName"
    }

    override fun wetNow(
        placeName: String,
        likelySnow: Boolean,
        severity: QualitativeIntensity?,
    ): String {
        if (severity == null) {
            return if (likelySnow) "Likely snow now at $placeName" else "Rain now at $placeName"
        }
        val prefix = severity.englishLabel()
        return if (likelySnow) "$prefix snow likely now at $placeName"
        else "$prefix rain now at $placeName"
    }

    private fun QualitativeIntensity.englishLabel(): String = when (this) {
        QualitativeIntensity.LIGHT -> "Light"
        QualitativeIntensity.MEDIUM -> "Medium"
        QualitativeIntensity.SEVERE -> "Severe"
    }
}
