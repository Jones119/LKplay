package com.shilapi.xcertplay.airplay

/**
 * Why the /info dictionary ended up with no OEM icon at all.
 *
 * Only reachable when a declaration failed: whenever a custom icon is unusable the packaged
 * `R.raw.ic_car_home` is tried as well, so an empty icon list means both candidates were refused and
 * the value names the one that was there first. That is the difference between "look at the file the
 * user uploaded" and "look at the packaged resource".
 */
enum class OemIconAbsence {
    /**
     * A custom icon file existed but is not a decodable, square image, and the packaged default did
     * not decode either. The upload is refused because the dictionary has to name one width and one
     * height per icon and the settings screen only offers a 1:1 replacement, so a non-square file
     * cannot be declared truthfully.
     */
    CUSTOM_ICON_REJECTED_AS_NOT_SQUARE,

    /** No custom icon file existed and the packaged `R.raw.ic_car_home` did not decode. */
    NO_CUSTOM_FILE_AND_PACKAGED_DEFAULT_UNDECODABLE,
}

/**
 * One line describing exactly what /info declares for CarPlay's sidebar "car" icon (the OEM icon):
 * the visible flag, the label, and per icon its pixel size and byte count.
 *
 * The car needs this because the icon never appeared on the CS11 -- not even after the CarPlay
 * pairing record was deleted and the phone re-paired, which rules out a stale capability cache on the
 * iPhone -- while /info kept declaring `oemIconVisible`, `oemIconLabel` and `oemIcons`
 * (`AirPlayInfoPlist.build`). None of that dictionary is visible from the head unit, so the next
 * connect has to state what was in it: an icon iOS quietly ignored and an icon we never sent look
 * exactly the same on screen, and only one of them is ours to fix. When the icon list is empty the
 * line names the reason as well, because that is the only case where the cause is on our side.
 *
 * Pure, and the caller logs it with the session's own tag, so the format stays unit-tested while the
 * device only contributes the values.
 */
internal fun oemIconDiagnostic(
    visible: Boolean,
    label: String,
    icons: List<AirPlayIcon>,
    absence: OemIconAbsence? = null,
): String {
    val line = StringBuilder("airplay oemIcon visible=")
        .append(visible)
        .append(" label=\"")
        .append(label)
        .append('"')
    if (icons.isEmpty()) {
        return line.append(" icons=0 reason=").append(absenceReason(absence)).toString()
    }
    line.append(" icons=").append(icons.size)
    for ((index, icon) in icons.withIndex()) {
        line.append(" icon[").append(index).append("]=")
            .append(icon.widthPixels).append('x').append(icon.heightPixels)
            .append(" bytes=").append(icon.data.size)
    }
    return line.toString()
}

/** [oemIconDiagnostic] for the dictionary that is about to be built from [config]. */
internal fun oemIconDiagnostic(config: AirPlayConfig): String = oemIconDiagnostic(
    visible = config.icons.isNotEmpty(),
    label = config.oemLabel,
    icons = config.icons,
    absence = config.oemIconAbsence,
)

/**
 * The wording of [OemIconAbsence], next to the enum so the two cannot drift.
 *
 * `null` is reported rather than guessed at: only the code that built the configuration knows which
 * candidate failed, and a line that invented a reason would be worse than one that admits it has none.
 */
private fun absenceReason(absence: OemIconAbsence?): String = when (absence) {
    OemIconAbsence.CUSTOM_ICON_REJECTED_AS_NOT_SQUARE ->
        "custom icon is not a square decodable image"
    OemIconAbsence.NO_CUSTOM_FILE_AND_PACKAGED_DEFAULT_UNDECODABLE ->
        "no custom icon file and the packaged default did not decode"
    null -> "not reported"
}
