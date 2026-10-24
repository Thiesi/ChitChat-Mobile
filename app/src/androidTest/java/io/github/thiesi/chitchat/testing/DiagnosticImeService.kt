package io.github.thiesi.chitchat.testing

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.thiesi.chitchat.test.R
import kotlin.math.roundToInt

internal enum class DiagnosticImeEvent {
    CREATE, INPUT_START, INPUT_VIEW_START, INPUT_VIEW_FINISH, WINDOW_SHOWN, WINDOW_HIDDEN,
    ROOT_ATTACHED, ROOT_VISIBLE, ROOT_INVISIBLE, ROOT_GONE, ROOT_DETACHED, DESTROY, LIMIT,
}

/** Finite scalar-only callback observations, not editor metadata or readiness/control. */
internal class DiagnosticImeEvents(private val limit: Int = 1024,
    private val uptime: () -> Long = SystemClock::uptimeMillis,
    private val emit: (String) -> Unit = { Log.i("ChitChatTestIme", it) }) {
    private var count = 0
    init { require(limit in 1..1024) }
    fun record(event: DiagnosticImeEvent) {
        if (count > limit) return
        count++
        val kind = if (count > limit) DiagnosticImeEvent.LIMIT else event
        emit("n=$count t=${uptime()} e=${kind.name}")
    }
}

/**
 * Minimal framework IME control in the TEST APK and its own process. Explicit emulator-only
 * selection is a separate diagnostic experiment, never ordinary CI, a fallback or release proof.
 * No network, editor reads, focus/visibility repairs, timers or stored EditorInfo/connections.
 */
class DiagnosticImeService : InputMethodService() {
    private val events = DiagnosticImeEvents()

    override fun onCreate() { super.onCreate(); events.record(DiagnosticImeEvent.CREATE) }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        events.record(DiagnosticImeEvent.INPUT_START)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        events.record(DiagnosticImeEvent.INPUT_VIEW_START)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        events.record(DiagnosticImeEvent.INPUT_VIEW_FINISH)
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowShown() { super.onWindowShown(); events.record(DiagnosticImeEvent.WINDOW_SHOWN) }
    override fun onWindowHidden() { events.record(DiagnosticImeEvent.WINDOW_HIDDEN); super.onWindowHidden() }

    override fun onCreateInputView(): View = object : LinearLayout(this) {
        override fun onAttachedToWindow() { super.onAttachedToWindow(); events.record(DiagnosticImeEvent.ROOT_ATTACHED) }
        override fun onDetachedFromWindow() { events.record(DiagnosticImeEvent.ROOT_DETACHED); super.onDetachedFromWindow() }
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            when (visibility) {
                View.VISIBLE -> events.record(DiagnosticImeEvent.ROOT_VISIBLE)
                View.INVISIBLE -> events.record(DiagnosticImeEvent.ROOT_INVISIBLE)
                View.GONE -> events.record(DiagnosticImeEvent.ROOT_GONE)
            }
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        val height = (200 * resources.displayMetrics.density).roundToInt()
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        minimumHeight = height
        setBackgroundColor(android.graphics.Color.DKGRAY)
        addView(TextView(context).apply { setText(R.string.diagnostic_ime_name); setTextColor(android.graphics.Color.WHITE) })
        addView(Button(context).apply {
            setText(R.string.diagnostic_ime_space)
            setOnClickListener { currentInputConnection?.commitText(" ", 1) }
        })
        addView(Button(context).apply {
            setText(R.string.diagnostic_ime_delete)
            setOnClickListener { currentInputConnection?.deleteSurroundingText(1, 0) }
        })
    }

    override fun onDestroy() { events.record(DiagnosticImeEvent.DESTROY); super.onDestroy() }
}
