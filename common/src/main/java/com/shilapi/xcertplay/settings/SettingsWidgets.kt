package com.shilapi.xcertplay.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.host.R
import android.os.Build

object SettingsWidgets {

    /**
     * Paints a switch's thumb and track from the theme's tokens, at every API level.
     *
     * This cannot go through `thumbTintList` / `trackTintList`: those are API 23+, and the CS11 is API 19,
     * so on the actual head unit the switch fell through to the framework's own Holo assets and none of the
     * palette applied — the control the owner could not pick out was drawing colours this project never
     * chose. `GradientDrawable` and `setThumbResource` / `setTrackResource` exist at API 1, so one path
     * covers the car and the tablet with no `Build.VERSION` branch and no per-state inflation.
     *
     * The colours are swapped by [checked] instead of by a state list because each is two integers; a
     * `StateListDrawable` here would mean building four drawables to express two.
     */
    private fun styleSwitch(switch: Switch, theme: SettingsTheme, checked: Boolean) {
        val context = switch.context
        val height = theme.dp(context, 26).coerceAtLeast(1)
        val trackWidth = theme.dp(context, 48).coerceAtLeast(height)
        val inset = theme.dp(context, 3)

        val track = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = height / 2f
            setColor(if (checked) theme.accentTrack else theme.trackOff)
            // `setSize` is not cosmetic: a GradientDrawable with no size reports -1 for its intrinsic
            // width/height, and `Switch.onMeasure` sizes the whole control from the track's intrinsic
            // width. Without this the switch collapsed to a one-pixel line on the car (API 19) — measured
            // on the head unit 2026-10-18, where the thumb did have a size but the track did not. The
            // platform's own track drawables ship with an intrinsic size, which is why the Holo default
            // looked right and only this override broke.
            setSize(trackWidth, height)
        }
        // The thumb is a circle inset inside the track, so "on" moves it to the far side and the colour
        // changes with it: position alone is a weak cue at arm's length in a moving car.
        //
        // The thumb is `buttonText`/`textPrimary`, not `accent`/`textSecondary`. `accent` is the *fill* of a
        // primary button — the colour a dark theme puts on white — so in NIGHT it drew a white thumb on the
        // light `accentTrack` and the pair measured about 1.9:1: the driver reported the night switches as
        // "contrast a bit small" (2026-10-18). `buttonText` is by definition the colour that contrasts with
        // the track's family, which is exactly what a thumb on a track needs, and it is 8:1 or better in
        // both themes. The two states still differ by colour as well as position.
        val thumbSize = height - inset * 2
        val thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (checked) theme.buttonText else theme.textPrimary)
            setSize(thumbSize.coerceAtLeast(1), thumbSize.coerceAtLeast(1))
        }
        switch.thumbDrawable = thumb
        switch.trackDrawable = track
        switch.switchMinWidth = trackWidth
        // The framework's own switch draws an "on"/"off" label inside the track; this design never wants one,
        // and leaving it on steals width from the row.
        //
        // Two mechanisms, because the obvious one is API 21+:
        //  * `showText` (`getShowText`/`setShowText`) arrived in API 21, so on the API 19 head unit the
        //    guard around it does not run, and the label stayed — the car showed "关闭"/"开启" inside every
        //    switch (measured 2026-10-18). Guarding `showText` at 16 instead would make every switch throw
        //    NoSuchMethodError on 4.4, because Dalvik refuses the whole method the first time it runs.
        //  * `setTextOn`/`setTextOff` are API 1 and are what the API 19 switch actually reads to size and
        //    draw that label. Emptying them is the API-19-safe way to remove it, and it is harmless on 21+
        //    where `showText` does the job.
        switch.setTextOn("")
        switch.setTextOff("")
        if (Build.VERSION.SDK_INT >= 21) {
            switch.showText = false
        }
    }

    private fun TextView.applyTextStyle(context: Context, theme: SettingsTheme, bold: Boolean = false) {
        if (theme.isOverlay) {
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            includeFontPadding = false
        } else {
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setLineSpacing(theme.dp(context, 3).toFloat(), 1f)
        }
    }

    data class SwitchRowResult(
        val rowView: View,
        val switch: Switch,
    )

    data class ChoiceRowResult<T>(
        val container: View,
        val radioGroup: RadioGroup,
    )

    data class ResolutionSliderResult(
        val container: View,
        val valueTextView: TextView,
        val seekBar: SeekBar,
    )

    /**
     * A secondary line of text: an explanation under a control, a status readout, or an inline error.
     *
     * The overlay used to draw every one of these with a raw `TextView` and a hardcoded size and colour
     * from the old palette, which is why the three-finger panel read as a different product from the
     * settings page (docs/_digest/60). Going through here keeps them on the theme's tokens.
     *
     * [emphasis] marks a value the driver is meant to read (a status, a live figure) rather than prose:
     * it takes the accent colour and one step of weight.
     */
    fun createDescription(
        context: Context,
        text: String,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        emphasis: Boolean = false,
        danger: Boolean = false,
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = 15f
        setTextColor(
            when {
                danger -> theme.danger
                emphasis -> theme.accent
                else -> theme.textSecondary
            },
        )
        applyTextStyle(context, theme, bold = emphasis)
    }

    /**
     * The button tiers. **Every button in the app is one of these.**
     *
     * The same control used to be built inline at fifteen call sites, each with its own colour, height and
     * radius, and six of them (`choose_image`, `default_icon`, `set`, `reset`, `cancel`, `save`) passed
     * nothing but `isAllCaps = false` — so they drew the framework's default button with the theme's
     * `colorAccent` on it, which is a light blue this design system does not use anywhere else. That is the
     * "one control, one visual scheme" problem in its purest form: four different buttons on one panel.
     *
     * Adding a tier here, rather than at a call site, is what keeps that from coming back.
     */
    enum class ButtonStyle {
        /** The single most important action on a surface. */
        PRIMARY,

        /** Everything else that is still an action: filled and outlined, [SettingsTheme.surfaceElevated]. */
        SECONDARY,

        /** Irreversible or destructive: filled with [SettingsTheme.danger]. */
        DANGER,
    }

    /**
     * The one button factory.
     *
     * [widthDp] / [heightDp] of 0 mean "wrap", which is how the framework sizes a button when the caller
     * supplies only a `LayoutParams` weight.
     */
    fun createButton(
        context: Context,
        title: String,
        style: ButtonStyle = ButtonStyle.SECONDARY,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        widthDp: Int = 0,
        minWidthDp: Int = 0,
        minHeightDp: Int = 52,
        textSizeSp: Float = 17f,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = title
        isAllCaps = false
        textSize = textSizeSp
        isEnabled = enabled
        gravity = Gravity.CENTER
        // A disabled control stays legible but stops reading as pressable: the label drops to the disabled
        // rank and the fill stays put, rather than the framework's own dimming.
        setTextColor(
            when {
                !enabled -> theme.textDisabled
                style == ButtonStyle.PRIMARY -> theme.buttonText
                style == ButtonStyle.DANGER -> theme.textPrimary
                else -> theme.textPrimary
            },
        )
        val fill = when (style) {
            ButtonStyle.PRIMARY -> theme.accent
            ButtonStyle.DANGER -> theme.danger
            ButtonStyle.SECONDARY -> theme.surfaceElevated
        }
        val stroke = when (style) {
            ButtonStyle.PRIMARY -> theme.accent
            ButtonStyle.DANGER -> theme.danger
            ButtonStyle.SECONDARY -> theme.controlBorder
        }
        background = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = theme.corner(context)
            setStroke(theme.dp(context, 1), stroke)
        }
        if (minHeightDp > 0) minHeight = theme.dp(context, minHeightDp)
        if (minWidthDp > 0) minWidth = theme.dp(context, minWidthDp)
        // CS11: the framework's default elevation animator is API 21+ and would otherwise be the only thing
        // left giving these buttons a shadow the rest of the design does not have.
        if (Build.VERSION.SDK_INT >= 21) stateListAnimator = null
        setOnClickListener { onClick() }
    }

    /**
     * A card: the grouped surface every list of controls sits on.
     *
     * [elevated] is for a card drawn straight on the page rather than inside another card.
     */
    fun createCard(
        context: Context,
        theme: SettingsTheme,
        paddingDp: Int = 24,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(theme.surface)
            cornerRadius = theme.corner(context)
            setStroke(theme.dp(context, 1), theme.cardBorder)
        }
        setPadding(
            theme.dp(context, paddingDp), theme.dp(context, paddingDp),
            theme.dp(context, paddingDp), theme.dp(context, paddingDp),
        )
    }

    /**
     * A single-choice radio row.
     *
     * The app had three of these groups - dock edge, driving side, hotspot mode - and each styled itself:
     * two hardcoded `Color.WHITE` and one used `textSecondary` plus a `buttonTintList` that is API 23+ (on the
     * CS11, API 19, that tint silently does nothing, so the radio dot came out in the theme's light-blue
     * `colorAccent`). Same control, three schemes, and one of them invisible on the car.
     *
     * The caller owns the [RadioGroup] and the ids, because it owns the selection logic; this only paints.
     */
    fun createRadioRow(
        context: Context,
        title: String,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        id: Int = View.NO_ID,
    ): RadioButton = RadioButton(context).apply {
        if (id != View.NO_ID) this.id = id
        text = title
        textSize = 18f
        setTextColor(theme.textPrimary)
        applyTextStyle(context, theme)
        // The radio dot itself: accent when chosen, the control border when not. Both API-1 drawable work,
        // so unlike the tint list this reaches the head unit.
        val dotOn = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.accent)
            setStroke(theme.dp(context, 1), theme.accent)
        }
        val dotOff = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.surfaceElevated)
            setStroke(theme.dp(context, 2), theme.controlBorder)
        }
        val states = SizedStateList(theme.dp(context, 20).coerceAtLeast(1)).apply {
            addState(intArrayOf(android.R.attr.state_checked), dotOn)
            addState(intArrayOf(), dotOff)
        }
        setButtonDrawable(states)
        // Size the dot through the drawables we already hold.
        //
        // This used to read the button drawable back with `buttonDrawable`, which is `getButtonDrawable()`
        // — an API 22 method. The guard said `>= 16`, so on the API 19 head unit it threw
        // `NoSuchMethodError: android.widget.RadioButton.getButtonDrawable` out of
        // `CarPlayHostActivity.onCreate`, which meant the CarPlay screen never appeared at all
        // (found the moment an iPhone was plugged in, 2026-10-18). `StateListDrawable` has no `setSize`
        // and reports no intrinsic size, so the size comes from the subclass instead; `CompoundButton`
        // measures the dot from that intrinsic size.
        val dotSize = theme.dp(context, 20).coerceAtLeast(1)
        dotOn.setSize(dotSize, dotSize)
        dotOff.setSize(dotSize, dotSize)
        states.setBounds(0, 0, dotSize, dotSize)
    }

    /**
     * A [android.graphics.drawable.StateListDrawable] that reports a size.
     *
     * `StateListDrawable` draws its children but answers -1 for `getIntrinsicWidth`/`getIntrinsicHeight`,
     * and `CompoundButton` sizes the button from exactly that, so the dot needs the size declared here.
     */
    private class SizedStateList(private val side: Int) : android.graphics.drawable.StateListDrawable() {
        override fun getIntrinsicWidth(): Int = side
        override fun getIntrinsicHeight(): Int = side
    }

    /**
     * A 1 dp rule: the heading rule under a card title, or the separator between rows.
     *
     * Two ranks on purpose: a heading rule uses [SettingsTheme.cardBorder] so the section reads as a box,
     * while a row separator uses [SettingsTheme.divider] and stays quiet.
     */
    fun createRule(context: Context, theme: SettingsTheme, strong: Boolean = false): View =
        View(context).apply {
            setBackgroundColor(if (strong) theme.cardBorder else theme.divider)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                theme.dp(context, 1),
            )
        }

    fun createCategoryHeader(
        context: Context,
        title: String,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
    ): TextView = TextView(context).apply {
        // A section is a micro-label in this system, not a headline: 12 sp, widely letterspaced, secondary
        // colour, and uppercase where the script has a case (docs/_digest/60). The hierarchy that used to
        // come from size now comes from weight, spacing and the hairlines between rows.
        text = if (theme.isOverlay) title else title.uppercase()
        textSize = if (theme.isOverlay) 16f else 12f
        setTextColor(if (theme.isOverlay) theme.accent else theme.textSecondary)
        if (Build.VERSION.SDK_INT >= 21) letterSpacing = if (theme.isOverlay) .06f else .2f
        applyTextStyle(context, theme, bold = true)
    }

    fun createSwitchRow(
        context: Context,
        label: String,
        description: String,
        checked: Boolean,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        contentDescription: String = label,
        enabled: Boolean = true,
        labelSizeSp: Float = 18f,
        onChanged: (Boolean) -> Unit,
    ): SwitchRowResult {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val switch = Switch(context).apply {
            isChecked = checked
            // A switch must always have an accessible name. Callers that pass `contentDescription = label`
            // are unaffected; the ones that pass "" for a label-only row now fall back to the label
            // instead of announcing nothing.
            this.contentDescription = contentDescription.ifEmpty { label }
            isEnabled = enabled
            // Both surfaces, one path: the palette has to reach the switch on API 19 too, and the tint-list
            // route this replaced silently did nothing below API 23 (see [styleSwitch]).
            styleSwitch(this, theme, checked)
            if (!theme.isOverlay) {
                minHeight = theme.dp(context, 52)
            }
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }

        if (theme.isOverlay) {
            // The overlay shows the description too when the caller supplies one. It used to drop it,
            // which left the three-finger panel with labels only while the settings page explained
            // itself — the same switch meant two different things on the two surfaces.
            row.setPadding(0, theme.dp(context, 6), 0, theme.dp(context, 6))
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val labelView = TextView(context).apply {
                text = label
                textSize = labelSizeSp
                setTextColor(theme.textSecondary)
                applyTextStyle(context, theme)
            }
            textCol.addView(labelView)
            if (description.isNotEmpty()) {
                textCol.addView(
                    createDescription(context, description, theme).apply {
                        setPadding(0, theme.dp(context, 2), theme.dp(context, 12), 0)
                    },
                )
            }
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            row.setPadding(0, theme.dp(context, 14), 0, theme.dp(context, 14))
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val labelView = TextView(context).apply {
                text = label
                textSize = 17f
                setTextColor(theme.textPrimary)
                applyTextStyle(context, theme, bold = true)
            }
            val descView = TextView(context).apply {
                text = description
                textSize = 13f
                setTextColor(theme.textSecondary)
                setPadding(0, theme.dp(context, 5), theme.dp(context, 16), 0)
                applyTextStyle(context, theme)
            }
            textCol.addView(labelView)
            textCol.addView(descView)
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switch)
        }

        return SwitchRowResult(row, switch)
    }

    fun <T> createChoiceRow(
        context: Context,
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onSelected: (T) -> Unit,
    ): ChoiceRowResult<T> {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 18f
            setTextColor(if (theme.isOverlay) theme.textSecondary else theme.textPrimary)
            applyTextStyle(context, theme, bold = !theme.isOverlay)
        }
        container.addView(
            labelView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, theme.dp(context, 4), 0, 0)
        }
        var selectedId = View.NO_ID
        for ((value, text) in options) {
            val button = RadioButton(context).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 17f
                setTextColor(theme.textSecondary)
                if (Build.VERSION.SDK_INT >= 21) buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(theme.accent, theme.textSecondary),
                )
                tag = value
                isChecked = value == selected
            }
            if (value == selected) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        var lastSelectedId = group.checkedRadioButtonId
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            if (checkedId == View.NO_ID) {
                lastSelectedId = View.NO_ID
                return@setOnCheckedChangeListener
            }
            val button = radioGroup.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            // RadioGroup may notify while unchecking the old button, then repeat the new ID.
            if (!button.isChecked || checkedId == lastSelectedId) return@setOnCheckedChangeListener
            @Suppress("UNCHECKED_CAST")
            val value = button.tag as? T ?: return@setOnCheckedChangeListener
            lastSelectedId = checkedId
            onSelected(value)
        }
        container.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return ChoiceRowResult(container, group)
    }

    fun createResolutionSlider(
        context: Context,
        initialPercent: Int,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onPercentChanged: (Int) -> Unit,
    ): ResolutionSliderResult {
        val initial = initialPercent.coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(context).apply {
            text = context.getString(R.string.resolution)
            // The overlay uses 18 sp, the same size a switch row and a choice row label use, so a slider
            // does not read as a different rank of control from the rows around it. The card theme keeps
            // the 20 sp it has always used.
            textSize = if (theme.isOverlay) 18f else 20f
            setTextColor(if (theme.isOverlay) theme.textSecondary else theme.textPrimary)
            applyTextStyle(context, theme, bold = !theme.isOverlay)
        }
        header.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueTextView = TextView(context).apply {
            text = "$initial%"
            textSize = 28f
            setTextColor(theme.accent)
            applyTextStyle(context, theme, bold = true)
        }
        header.addView(
            valueTextView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.dp(context, 14) },
        )

        val seekBar = SeekBar(context).apply {
            max = CarPlayDisplayScale.MAX_PERCENT - CarPlayDisplayScale.MIN_PERCENT
            progress = initial - CarPlayDisplayScale.MIN_PERCENT
            if (Build.VERSION.SDK_INT >= 21) {
                splitTrack = false
                progressTintList = ColorStateList.valueOf(theme.accent)
                thumbTintList = ColorStateList.valueOf(theme.accent)
            }
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                        val percent = (CarPlayDisplayScale.MIN_PERCENT + progress)
                            .coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
                        valueTextView.text = "$percent%"
                        onPercentChanged(percent)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar) = Unit
                },
            )
        }
        container.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.dp(context, 8) },
        )

        val range = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val minLabel = TextView(context).apply {
            text = context.getString(R.string.custom_resolution_summary, CarPlayDisplayScale.MIN_PERCENT)
            textSize = 15f
            setTextColor(theme.textSecondary)
            applyTextStyle(context, theme)
        }
        range.addView(minLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val maxLabel = TextView(context).apply {
            text = context.getString(R.string.custom_resolution_summary, CarPlayDisplayScale.MAX_PERCENT)
            textSize = 15f
            setTextColor(theme.textSecondary)
            applyTextStyle(context, theme)
        }
        range.addView(
            maxLabel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            range,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        return ResolutionSliderResult(container, valueTextView, seekBar)
    }
}
